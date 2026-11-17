package lol.drake.hyperlauncher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * The link between the real launcher and MiuiHome.
 *
 * The launcher side runs inside the third-party launcher. It tells MiuiHome where the launcher's icons are
 * and what the home screen looks like, hands icon taps over so MiuiHome's own open animation can run, and
 * says when the launcher is ready to be seen again after a swipe home. The MiuiHome side keeps the latest
 * of all that. Everything travels over one Messenger, and MiuiHome only listens to the default launcher.
 */
final class IconSync {
    static final String ACTION_CONNECT = "lol.drake.hyperlauncher.CONNECT";
    static final String HANDOFF = "lol.drake.hyperlauncher.HANDOFF";
    /** Set when MiuiHome holds a picture of this launcher over the screen while it comes back. */
    static final String COVERED = "lol.drake.hyperlauncher.COVERED";
    private static final String TAG = Module.TAG;
    private static final String[] TARGETS = {"com.miui.home", "com.mi.android.globallauncher"};

    private static final int MSG_ICONS = 1;
    private static final int MSG_SNAP = 2;
    private static final int MSG_LAUNCH = 3;
    private static final int MSG_LAUNCH_RESULT = 4;
    private static final int MSG_READY = 5;
    private static final int MSG_TOUCH = 6;
    private static final int MSG_EVENT = 8;
    private static final int MSG_SWAP = 9;
    private static final int MSG_FOLD = 10;
    private static final int MSG_OPENING = 11;

    private static final int HOME_TAG = 0x7e570002;
    private static final int FRAMES_TAG = 0x7e570005;
    private static final long SETTLE_MS = 250;

    private IconSync() {}

    // ------------------------------------------------------------ launcher side

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Handler io;
    private static final ThreadLocal<View> clicked = new ThreadLocal<>();
    /** Icon rects learned from taps on the home screen, by package. */
    private static final Map<String, int[]> learned = new LinkedHashMap<>();
    /** The very view each learned icon was. Another page has other icons in the same places. */
    private static final Map<String, WeakReference<View>> learnedViews = new HashMap<>();
    /** Every view seen while the launcher was known to be showing plain home: no drawer, folder or popup. */
    private static final WeakHashMap<View, Boolean> homeViews = new WeakHashMap<>();
    private static final Map<Class<?>, Object> tagMethods = new HashMap<>();
    private static List<int[]> visible = new ArrayList<>();
    private static WeakReference<Activity> homeRef = new WeakReference<>(null);
    private static String ownPkg;
    private static boolean resumed;
    private static long trustedUntil;
    private static boolean homeIntentPending;
    private static boolean handoffPending;

    private static int frameSeq;
    private static int snapFrameSeq = -1;
    private static int snapSeq;
    private static int sentSeq = -1;
    private static boolean copying;
    private static boolean again;
    private static long lastScanAt;
    private static Bitmap scratch;
    private static int downFrame;
    private static volatile View tapped;
    private static volatile long tappedAt;

    private static Method zoomOut;
    private static volatile Messenger link;
    private static boolean connecting;
    private static boolean frozen;
    private static boolean bypass;
    private static Runnable pendingFallback;
    private static int launchId;

    private static boolean handoffActive;
    private static int handoffFrame;
    private static float savedScale = -1f;

    private static XposedModule module;
    private static Class<?> resumedHooked;

    static void installLauncher(XposedModule m, String pkg) {
        module = m;
        ownPkg = pkg;
        Status.serveWhenReady("launcher", new Status.Source() {
            @Override
            public void describe(Bundle b) {
                b.putBoolean("linked", link != null);
                b.putBoolean("seen", homeRef.get() != null);
            }

            @Override
            public boolean run(String command) {
                return false;
            }
        });
        HandlerThread t = new HandlerThread("HyperLauncherIo", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        t.start();
        io = new Handler(t.getLooper());

        attempt("click", () -> m.hook(View.class.getDeclaredMethod("performClick")).intercept(chain -> {
            View prev = clicked.get();
            clicked.set((View) chain.getThisObject());
            try {
                return chain.proceed();
            } finally {
                clicked.set(prev);
            }
        }));
        for (Method e : Instrumentation.class.getDeclaredMethods()) {
            if (e.getName().equals("execStartActivity")) {
                Class<?>[] p = e.getParameterTypes();
                int at = -1, optsAt = -1;
                for (int i = 0; i < p.length; i++) {
                    if (p[i] == Intent.class) at = i;
                    if (p[i] == Bundle.class) optsAt = i;
                }
                if (at < 0 || p.length == 0 || p[0] != Context.class) continue;
                final int idx = at, oidx = optsAt;
                attempt("start", () -> m.hook(e).intercept(chain -> {
                    Object who = chain.getArg(0);
                    Intent in = (Intent) chain.getArg(idx);
                    if (!bypass && who instanceof Context && in != null) {
                        Bundle opts = oidx < 0 ? null : (Bundle) chain.getArg(oidx);
                        Context ctx = (Context) who;
                        ComponentName cn = in.getComponent();
                        int[] rect = learn(cn != null ? cn.getPackageName() : in.getPackage());
                        if (handOver(ctx, in, rect, () -> ctx.startActivity(in, opts))) return null;
                    }
                    return chain.proceed();
                }));
            } else if (e.getName().equals("callActivityOnNewIntent")) {
                attempt("new intent", () -> m.hook(e).intercept(chain -> {
                    try {
                        Object a = chain.getArg(0), in = chain.getArg(1);
                        if (a instanceof Activity && in instanceof Intent) noteNewIntent((Activity) a, (Intent) in);
                    } catch (Throwable t2) {
                        Log.w(TAG, "new intent", t2);
                    }
                    return chain.proceed();
                }));
            }
        }
        attempt("launcher apps", () -> m.hook(android.content.pm.LauncherApps.class.getDeclaredMethod(
                "startMainActivity", ComponentName.class, UserHandle.class, Rect.class, Bundle.class))
                .intercept(chain -> {
                    ComponentName cn = (ComponentName) chain.getArg(0);
                    UserHandle user = (UserHandle) chain.getArg(1);
                    Activity top = homeRef.get();
                    if (cn != null && !bypass && top != null && android.os.Process.myUserHandle().equals(user)) {
                        int[] rect = learn(cn.getPackageName());
                        Object la = chain.getThisObject();
                        Rect bounds = (Rect) chain.getArg(2);
                        Bundle opts = (Bundle) chain.getArg(3);
                        Intent in = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        if (handOver(top, in, rect, () -> ((android.content.pm.LauncherApps) la)
                                .startMainActivity(cn, user, bounds, opts))) return null;
                    }
                    return chain.proceed();
                }));
        // MiuiHome zooms the wallpaper as on stock, between a home level and an app level. This launcher must
        // rest at the same home level, or the wallpaper would snap whenever the two hand over.
        attempt("wallpaper", () -> {
            zoomOut = android.app.WallpaperManager.class.getMethod("setWallpaperZoomOut", IBinder.class, float.class);
            m.hook(zoomOut).intercept(chain -> chain.proceed(new Object[]{chain.getArg(0), 1f}));
        });
        attempt("resume", () -> m.hook(Activity.class.getDeclaredMethod("onResume")).intercept(chain -> {
            Object r = chain.proceed();
            try {
                onResumed((Activity) chain.getThisObject());
            } catch (Throwable t2) {
                Log.w(TAG, "resume", t2);
            }
            return r;
        }));
        attempt("pause", () -> m.hook(Activity.class.getDeclaredMethod("onPause")).intercept(chain -> {
            if (chain.getThisObject() == homeRef.get()) {
                resumed = false;
                settledLaunch = launchId;
                endHandoff(false);
            }
            return chain.proceed();
        }));
        attempt("stop", () -> m.hook(Activity.class.getDeclaredMethod("onStop")).intercept(chain -> {
            if (chain.getThisObject() == homeRef.get()) unfreeze(); // out of sight now: free to draw again
            return chain.proceed();
        }));
        attempt("touch", () -> m.hook(Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class))
                .intercept(chain -> {
                    Activity a = (Activity) chain.getThisObject();
                    if (a == homeRef.get()) {
                        MotionEvent ev = (MotionEvent) chain.getArg(0);
                        int action = ev.getActionMasked();
                        if (action == MotionEvent.ACTION_DOWN) {
                            // The picture MiuiHome holds may be a few frames behind: a clock ticked, the
                            // launcher just came back. That is close enough. Many frames behind means the
                            // page or a panel changed, and then the launcher opens the app its own way.
                            downFrame = frameSeq;
                            settledLaunch = launchId;
                            if (frameSeq != snapFrameSeq && !copying && resumed && !frozen) scan(a); // refresh while the finger is down
                            trustedUntil = 0; // from here on the user may have opened something
                            endHandoff(true);
                        } else if (action == MotionEvent.ACTION_UP) {
                            // some launchers start apps from their own touch handling, with no click at all
                            tapped = hitTest(a.getWindow().getDecorView(), (int) ev.getRawX(), (int) ev.getRawY());
                            tappedAt = SystemClock.uptimeMillis();
                            noteFolderTap(tapped);
                        }
                    }
                    return chain.proceed();
                }));
    }

    private interface Risky {
        void run() throws Throwable;
    }

    private static void attempt(String what, Risky r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.w(TAG, "launcher hook failed: " + what, t);
        }
    }

    private static void noteNewIntent(Activity a, Intent in) {
        if (!isHome(a)) return;
        if (in.hasCategory(Intent.CATEGORY_HOME)) homeIntentPending = true;
        if (in.getBooleanExtra(HANDOFF, false)) {
            handoffPending = true;
            coveredPending = in.getBooleanExtra(COVERED, false);
        }
    }

    private static void onResumed(Activity a) {
        if (!isHome(a)) return;
        boolean first = homeRef.get() != a;
        if (first) {
            homeRef = new WeakReference<>(a);
            homeViews.clear();
            trustWhileBehind(a);
        }
        resumed = true;
        loadFolders(a);
        watchFrames(a);
        connect(a);
        holdWallpaper(a);
        Intent it = a.getIntent();
        boolean handoff = handoffPending;
        if (first && it != null && it.getBooleanExtra(HANDOFF, false)) {
            it.removeExtra(HANDOFF);
            handoff = true;
            coveredPending = it.getBooleanExtra(COVERED, false);
        }
        handoffPending = false;
        // a launcher that was just created, or was just sent home, is showing plain home
        if (first || handoff || homeIntentPending) trustedUntil = SystemClock.uptimeMillis() + (first ? 6000 : 3000);
        homeIntentPending = false;
        if (handoff) beginHandoff(a, coveredPending);
        coveredPending = false;
        standIn = false;
        main.post(() -> {
            Activity h = homeRef.get();
            if (h != null && resumed) playWaiting(h);
            Runnable f = afterResume;
            afterResume = null;
            if (f == null || !resumed) return;
            unfreeze();
            bypass = true;
            try {
                f.run();
            } catch (Throwable t) {
                Log.w(TAG, "start put off until the launcher was back failed", t);
            } finally {
                bypass = false;
            }
        });
        main.removeCallbacks(settle);
        main.postDelayed(settle, 400);
    }

    /**
     * Launcher3 and its relatives put an app start off until they are resumed. While MiuiHome passes a tap
     * on to a launcher standing behind it, the launcher is as good as in front: the start is handed straight
     * back to MiuiHome.
     */
    private static void trustWhileBehind(Activity a) {
        XposedModule m = module;
        if (m == null || resumedHooked == a.getClass()) return;
        resumedHooked = a.getClass();
        for (Class<?> c = a.getClass(); c != null && c != Activity.class; c = c.getSuperclass()) {
            try {
                Method has = c.getDeclaredMethod("hasBeenResumed");
                if (has.getReturnType() != boolean.class) continue;
                attempt("resumed", () -> m.hook(has).intercept(chain ->
                        standIn && chain.getThisObject() == homeRef.get() ? (Object) true : chain.proceed()));
                return;
            } catch (NoSuchMethodException e) {
                // not this class
            }
        }
    }

    private static void holdWallpaper(Activity a) {
        try {
            IBinder token = a.getWindow().getDecorView().getWindowToken();
            if (zoomOut != null && token != null) {
                zoomOut.invoke(android.app.WallpaperManager.getInstance(a), token, 1f);
            }
        } catch (Throwable t) {
            // the wallpaper then rests wherever the launcher leaves it
        }
    }

    /** True for the activity that is the device's default home right now. */
    private static boolean isHome(Activity a) {
        if (a.getWindow() == null) return false;
        View decor = a.getWindow().getDecorView();
        if (decor.getTag(HOME_TAG) != null) return true;
        try {
            ResolveInfo ri = a.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && ownPkg.equals(ri.activityInfo.packageName)
                    && a.getComponentName().getClassName().equals(ri.activityInfo.name)) {
                decor.setTag(HOME_TAG, Boolean.TRUE);
                return true;
            }
        } catch (Throwable t) {
            // not a home activity as far as we can tell
        }
        return false;
    }

    // ---- frames: scanning waits until the screen has stopped changing

    private static final Runnable settle = () -> {
        Activity a = homeRef.get();
        if (a == null || !resumed || frozen || a.isDestroyed()) return;
        scan(a);
    };

    private static void watchFrames(Activity a) {
        View decor = a.getWindow().getDecorView();
        if (decor.getTag(FRAMES_TAG) != null) return;
        decor.setTag(FRAMES_TAG, Boolean.TRUE);
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (homeRef.get() != a) return true;
            if (frozen) { // hold the last frame while MiuiHome takes over
                if (thawFrames <= 0) return false;
                thawFrames--; // (but first the one in which the tapped icon leaves its slot)
                return true;
            }
            frameSeq++;
            main.removeCallbacks(settle);
            main.postDelayed(settle, SETTLE_MS);
            if (handoffActive) {
                main.removeCallbacks(handoffIdle);
                main.postDelayed(handoffIdle, 80);
            }
            long now = SystemClock.uptimeMillis();
            if (resumed && now - lastScanAt > 2500) { // something animates without pause: scan anyway
                lastScanAt = now;
                main.post(() -> {
                    Activity h = homeRef.get();
                    if (h != null && resumed && !frozen) scan(h);
                });
            }
            return true;
        });
    }

    // ---- scanning

    private static void scan(Activity a) {
        try {
            lastScanAt = SystemClock.uptimeMillis();
            if (a.getWindow() == null) return;
            turned = rotationOf(a);
            View decor = a.getWindow().getDecorView();
            int w = decor.getWidth(), h = decor.getHeight();
            if (w <= 0 || h <= 0) return;
            boolean trusted = lastScanAt < trustedUntil || homeViews.isEmpty();
            List<int[]> rects = new ArrayList<>();
            Map<String, int[]> icons = new HashMap<>();
            long[] foreign = {0};
            walk(decor, trusted, IN, true, w, h, rects, icons, foreign, 0);
            visible = rects;
            // a big newcomer on screen is a drawer, folder or panel: not what home looks like on return
            boolean homeState = trusted || foreign[0] * 4 < (long) w * h;
            lastHomeState = homeState;
            if (homeState) publish(icons, rects, w, h);
            capture(a, homeState);
        } catch (Throwable t) {
            Log.w(TAG, "scan failed", t);
        }
    }

    private static final int OUT = 0, IN = 1, WIDGET = 2;

    private static void walk(View v, boolean trusted, int parentIn, boolean chain, int sw, int sh,
                             List<int[]> rects, Map<String, int[]> icons, long[] foreign, int depth) {
        if (v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0
                || v.getAlpha() < 0.02f || depth > 40) return;
        // a widget rebuilds its insides whenever it updates; those are still home, not something opened over it
        boolean in = trusted || parentIn == WIDGET || homeViews.containsKey(v);
        if (trusted) homeViews.put(v, Boolean.TRUE);
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int[] r = {loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight()};
        rects.add(r);
        if (!in && parentIn != OUT) {
            long ow = Math.min(r[2], sw) - Math.max(r[0], 0), oh = Math.min(r[3], sh) - Math.max(r[1], 0);
            if (ow > 0 && oh > 0) foreign[0] += ow * oh;
        }
        if (chain) {
            ComponentName cn = tagComponent(v.getTag());
            if (cn == null && v.isClickable() && v.getWidth() <= sw * 2 / 5 && v.getHeight() <= sh / 4) {
                cn = modelComponent(v);
            }
            int[] icon = null;
            String key = null;
            if (cn != null) {
                icon = iconRect(v);
                key = "pkg:" + cn.getPackageName();
            } else if (v instanceof TextView) {
                CharSequence s = ((TextView) v).getText();
                if (s != null && s.length() > 0 && s.length() < 60) {
                    icon = labelIcon((TextView) v, loc);
                    key = "label:" + s.toString().trim();
                }
            }
            if (icon != null && tile(icon, sw) && inside(icon, sw, sh)) icons.put(key, icon);
            if (cn == null && v.isClickable() && v.getWidth() <= sw * 2 / 5 && v.getHeight() <= sh / 4) {
                // a folder: the apps inside it close into the folder's icon, as they do on MiuiHome's own desktop
                java.util.Set<String> inside = folderPackages(v);
                if (inside != null) {
                    int[] fr = iconRect(v);
                    if (fr != null && tile(fr, sw) && inside(fr, sw, sh)) {
                        for (String p : inside) icons.put("fold:" + p, fr);
                    }
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int mine = !in ? OUT : parentIn == WIDGET || v instanceof android.appwidget.AppWidgetHostView ? WIDGET : IN;
            for (int i = 0; i < g.getChildCount(); i++) {
                walk(g.getChildAt(i), trusted, mine, chain && in, sw, sh, rects, icons, foreign, depth + 1);
            }
        }
    }

    /** The app a Launcher3-style item view stands for, read from its tag. */
    private static ComponentName tagComponent(Object tag) {
        if (tag == null || tag instanceof String || tag instanceof Number || tag instanceof Boolean) return null;
        Class<?> c = tag.getClass();
        Object m = tagMethods.get(c);
        if (m == null) {
            try {
                Method found = c.getMethod("getTargetComponent");
                m = found.getReturnType() == ComponentName.class ? found : Boolean.FALSE;
            } catch (Throwable t) {
                m = Boolean.FALSE;
            }
            tagMethods.put(c, m);
        }
        if (!(m instanceof Method)) return null;
        try {
            return (ComponentName) ((Method) m).invoke(tag);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final Map<Class<?>, Object> viewModels = new HashMap<>();
    private static final Map<Class<?>, Object> folderModels = new HashMap<>();
    private static final Map<Class<?>, Object> itemModels = new HashMap<>();

    /** The packages of the apps inside a folder icon view, or null when the view is not one. */
    private static java.util.Set<String> folderPackages(View v) {
        Class<?> c = v.getClass();
        if (framework(c.getName())) return null;
        Object known = folderModels.get(c);
        try {
            // (one view class may serve both for apps and for folders: then there is no folder this time)
            if (known instanceof Object[]) return packagesIn(followAny(v, (Object[]) known));
            int misses = known instanceof Integer ? (Integer) known : 0;
            if (misses >= 12) return null;
            Object[] path = searchFolder(v);
            if (path == null) {
                folderModels.put(c, misses + 1);
                return null;
            }
            folderModels.put(c, path);
            return packagesIn(followAny(v, path));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object followAny(Object o, Object[] path) throws IllegalAccessException {
        for (Object step : path) {
            if (o == null) return null;
            o = ((java.lang.reflect.Field) step).get(o);
        }
        return o;
    }

    /** The app one entry of a folder's list stands for. */
    private static ComponentName itemComponent(Object e) {
        if (e == null) return null;
        ComponentName cn = asComponent(e);
        if (cn != null) return cn;
        cn = tagComponent(e);
        if (cn != null) return cn;
        Class<?> c = e.getClass();
        if (framework(c.getName())) return null;
        Object known = itemModels.get(c);
        try {
            if (known instanceof Object[]) return asComponent(follow(e, (Object[]) known));
            if (known != null) return null;
            Object[] path = searchModel(e);
            itemModels.put(c, path == null ? (Object) Boolean.FALSE : path);
            return path == null ? null : asComponent(follow(e, path));
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.util.Set<String> packagesIn(Object o) {
        if (o != null && o.getClass().isArray() && !o.getClass().getComponentType().isPrimitive()) {
            o = java.util.Arrays.asList((Object[]) o);
        }
        if (!(o instanceof java.util.Collection)) return null;
        java.util.Collection<?> col = (java.util.Collection<?>) o;
        if (col.size() < 2 || col.size() > 120) return null;
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Object e : col) {
            ComponentName cn = itemComponent(e);
            if (cn != null) out.add(cn.getPackageName());
        }
        return out.size() >= 2 ? out : null;
    }

    /** Looks behind a view for a list of two or more apps; the way there is a chain of fields. */
    private static Object[] searchFolder(View root) {
        java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>(); // {object, path}
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        queue.add(new Object[]{root, new Object[0]});
        seen.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 200) {
            Object[] node = queue.poll();
            Object o = node[0];
            Object[] path = (Object[]) node[1];
            for (Class<?> c = o.getClass(); c != null && !framework(c.getName()); c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (v == null || v instanceof View || v instanceof Context || v instanceof Drawable
                            || v instanceof Bitmap || v instanceof Handler || v instanceof CharSequence) continue;
                    Object[] next = extend(path, f);
                    if (packagesIn(v) != null) return next;
                    if (path.length >= 3 || !seen.add(v)) continue;
                    if (!(v instanceof java.util.Collection) && !v.getClass().isArray()
                            && !framework(v.getClass().getName())) {
                        queue.add(new Object[]{v, next});
                    }
                }
            }
        }
        return null;
    }

    /**
     * The app an icon view of an unknown launcher stands for. Launchers keep that in a model object
     * somewhere behind the view; the way to it is searched for once per view class and then remembered.
     */
    private static ComponentName modelComponent(View v) {
        Class<?> c = v.getClass();
        if (framework(c.getName())) return null;
        Object known = viewModels.get(c);
        try {
            if (known instanceof Object[]) return asComponent(follow(v, (Object[]) known));
            int misses = known instanceof Integer ? (Integer) known : 0;
            if (misses >= 6) return null;
            Object[] path = searchModel(v);
            if (path == null) {
                viewModels.put(c, misses + 1);
                return null;
            }
            viewModels.put(c, path);
            return asComponent(follow(v, path));
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean framework(String name) {
        return name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("java.")
                || name.startsWith("javax.") || name.startsWith("kotlin") || name.startsWith("com.android.internal.")
                || name.startsWith("dalvik.") || name.startsWith("com.google.android.material.");
    }

    private static ComponentName asComponent(Object o) {
        ComponentName cn = null;
        try {
            if (o instanceof ComponentName) {
                cn = (ComponentName) o;
            } else if (o instanceof Intent) {
                cn = ((Intent) o).getComponent();
            } else if (o instanceof String) {
                String s = (String) o;
                if (s.length() > 400 || s.indexOf('/') < 0 || s.indexOf(' ') >= 0) return null;
                if (s.contains("component=")) cn = Intent.parseUri(s, 0).getComponent();
                else cn = ComponentName.unflattenFromString(s);
            }
        } catch (Throwable t) {
            return null;
        }
        return cn == null || cn.getPackageName().equals(ownPkg) || cn.getPackageName().indexOf('.') < 0 ? null : cn;
    }

    /** A step is a field, or 0 for "the only element of this collection". */
    private static Object follow(Object o, Object[] path) throws IllegalAccessException {
        for (Object step : path) {
            if (o == null) return null;
            if (step instanceof java.lang.reflect.Field) {
                o = ((java.lang.reflect.Field) step).get(o);
            } else {
                if (!(o instanceof java.util.Collection) || ((java.util.Collection<?>) o).size() != 1) return null;
                o = ((java.util.Collection<?>) o).iterator().next();
            }
        }
        return o;
    }

    private static Object[] searchModel(Object root) {
        java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>(); // {object, path}
        java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        queue.add(new Object[]{root, new Object[0]});
        seen.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 300) {
            Object[] node = queue.poll();
            Object o = node[0];
            Object[] path = (Object[]) node[1];
            if (o instanceof java.util.Collection) {
                java.util.Collection<?> col = (java.util.Collection<?>) o;
                if (col.size() != 1) continue; // several entries: a folder or a list, not one app
                Object e = col.iterator().next();
                Object[] next = extend(path, 0);
                if (asComponent(e) != null) return next;
                if (e != null && path.length < 5 && !framework(e.getClass().getName()) && seen.add(e)) {
                    queue.add(new Object[]{e, next});
                }
                continue;
            }
            for (Class<?> c = o.getClass(); c != null && !framework(c.getName()); c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (v == null || v instanceof View || v instanceof Context || v instanceof Drawable
                            || v instanceof Bitmap || v instanceof Handler) continue;
                    Object[] next = extend(path, f);
                    if (asComponent(v) != null) return next;
                    if (path.length >= 5 || !seen.add(v)) continue;
                    if (v instanceof java.util.Collection || !framework(v.getClass().getName())) {
                        queue.add(new Object[]{v, next});
                    }
                }
            }
        }
        return null;
    }

    private static Object[] extend(Object[] path, Object step) {
        Object[] next = java.util.Arrays.copyOf(path, path.length + 1);
        next[path.length] = step;
        return next;
    }

    private static boolean tile(int[] r, int sw) {
        int w = r[2] - r[0], h = r[3] - r[1];
        return w >= 40 && h >= 40 && w <= sw * 2 / 5 && w * 10 >= h * 6 && h * 10 >= w * 6;
    }

    private static boolean inside(int[] r, int sw, int sh) {
        return r[0] >= 0 && r[1] >= 0 && r[2] <= sw && r[3] <= sh;
    }

    /** Icon bounds for a label view: its top drawable, or the image sitting beside it. */
    private static int[] labelIcon(TextView t, int[] loc) {
        try { // Launcher3's BubbleTextView knows exactly where it draws its icon
            Rect b = new Rect();
            t.getClass().getMethod("getIconBounds", Rect.class).invoke(t, b);
            if (b.width() > 0 && b.height() > 0) return new int[]{loc[0] + b.left, loc[1] + b.top, loc[0] + b.right, loc[1] + b.bottom};
        } catch (Throwable e) {
            // not a Launcher3 view
        }
        Drawable top = t.getCompoundDrawables()[1];
        if (top != null && top.getBounds().width() > 0) {
            Rect b = top.getBounds();
            int l = loc[0] + (t.getWidth() - b.width()) / 2;
            int tp = loc[1] + t.getPaddingTop();
            return new int[]{l, tp, l + b.width(), tp + b.height()};
        }
        if (t.getParent() instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) t.getParent();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof ImageView && shown(c) && c.getWidth() > 0) return screenRect(c);
            }
        }
        return null;
    }

    /** Like isShown(), but while the launcher stands behind MiuiHome its window being hidden does not count. */
    private static boolean shown(View v) {
        if (!standIn || resumed) return v.isShown();
        if (!v.isAttachedToWindow()) return false;
        View root = v.getRootView();
        for (View c = v; c != null && c != root; c = c.getParent() instanceof View ? (View) c.getParent() : null) {
            if (c.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }

    private static int[] iconRect(View v) {
        if (v instanceof TextView) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int[] r = labelIcon((TextView) v, loc);
            if (r != null) return r;
        }
        if (v instanceof ViewGroup) {
            View img = firstImage((ViewGroup) v, 0);
            if (img != null) return screenRect(img);
        }
        return screenRect(v);
    }

    private static View firstImage(ViewGroup g, int depth) {
        if (depth > 6) return null;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof ImageView && c.getVisibility() == View.VISIBLE && c.getWidth() > 0) return c;
            if (c instanceof ViewGroup) {
                View r = firstImage((ViewGroup) c, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static int[] screenRect(View v) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return new int[]{loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight()};
    }

    private static boolean onScreen(int[] r, List<int[]> views) {
        for (int[] v : views) {
            if (Math.abs(v[0] - r[0]) <= 3 && Math.abs(v[1] - r[1]) <= 3
                    && Math.abs(v[2] - r[2]) <= 3 && Math.abs(v[3] - r[3]) <= 3) return true;
        }
        return false;
    }

    private static void publish(Map<String, int[]> icons, List<int[]> views, int sw, int sh) {
        synchronized (learned) {
            for (Map.Entry<String, int[]> e : learned.entrySet()) {
                String key = "pkg:" + e.getKey();
                if (icons.containsKey(key)) continue; // the launcher's own answer beats a remembered tap
                int[] v = e.getValue();
                int[] icon = {v[0], v[1], v[2], v[3]};
                WeakReference<View> ref = learnedViews.get(e.getKey());
                View view = ref == null ? null : ref.get();
                if (view == null || !view.isAttachedToWindow() || !view.isShown() || !inside(icon, sw, sh)) continue;
                int[] now = screenRect(view);
                if (now[0] != v[4] || now[1] != v[5] || now[2] != v[6] || now[3] != v[7]) continue; // it moved
                icons.put(key, icon);
            }
        }
        synchronized (folders) {
            for (Map.Entry<String, int[]> e : folders.entrySet()) {
                String pkg = e.getKey();
                if (icons.containsKey("pkg:" + pkg) || icons.containsKey("fold:" + pkg)) continue;
                int[] v = e.getValue();
                int[] icon = {v[0], v[1], v[2], v[3]}, cell = {v[4], v[5], v[6], v[7]};
                if (!inside(icon, sw, sh) || !onScreen(cell, views)) continue; // the folder is not there now
                boolean app = false; // an app's own icon sits there now
                for (Map.Entry<String, int[]> i : icons.entrySet()) {
                    int[] r = i.getValue();
                    int cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2;
                    if (i.getKey().startsWith("pkg:") && cx >= cell[0] && cx < cell[2] && cy >= cell[1] && cy < cell[3]) app = true;
                }
                if (!app) icons.put("fold:" + pkg, icon);
            }
        }
        fitFolders(icons);
        String[] keys = icons.keySet().toArray(new String[0]);
        int[] flat = new int[keys.length * 4];
        for (int i = 0; i < keys.length; i++) System.arraycopy(natural(icons.get(keys[i]), turned, sw, sh), 0, flat, i * 4, 4);
        Bundle b = new Bundle();
        b.putStringArray("keys", keys);
        b.putIntArray("rects", flat);
        send(MSG_ICONS, 0, b, null);
    }

    /**
     * A folder's view is often its whole cell, name included. What a window should close into is the
     * picture in it, which sits where the app icons of the same row sit in theirs.
     */
    private static void fitFolders(Map<String, int[]> icons) {
        List<int[]> apps = new ArrayList<>();
        for (Map.Entry<String, int[]> e : icons.entrySet()) if (e.getKey().startsWith("pkg:")) apps.add(e.getValue());
        if (apps.isEmpty()) return;
        int[] widths = new int[apps.size()];
        for (int i = 0; i < widths.length; i++) widths[i] = apps.get(i)[2] - apps.get(i)[0];
        java.util.Arrays.sort(widths);
        int size = widths[widths.length / 2];
        if (size <= 0) return;
        for (Map.Entry<String, int[]> e : icons.entrySet()) {
            if (!e.getKey().startsWith("fold:")) continue;
            int[] f = e.getValue();
            int w = f[2] - f[0], h = f[3] - f[1];
            if (w <= size * 5 / 4 && h <= size * 5 / 4) continue;
            int cx = (f[0] + f[2]) / 2, top = f[1] + Math.max(0, (w - size) / 2), bottom = top + size;
            for (int[] a : apps) { // an app icon in the same row says exactly where
                if (a[1] >= f[1] - 4 && a[3] <= f[3] + 4) {
                    top = a[1];
                    bottom = a[3];
                    break;
                }
            }
            e.setValue(new int[]{cx - size / 2, top, cx + size / 2, bottom});
        }
    }

    // ---- a launcher that turns with the screen

    /** How the screen was turned (Surface.ROTATION_*) at the latest scan. */
    private static int turned;

    private static int rotationOf(Activity a) {
        try {
            return a.getWindowManager().getDefaultDisplay().getRotation();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * MiuiHome's Launcher only ever lies upright; while the screen is turned the system turns its whole
     * window for it. So everything it is told is told as the upright screen has it: a place on the turned
     * screen becomes the place on the upright one that is under the same spot of glass.
     *
     * @param sw the turned screen's width, {@code sh} its height
     */
    private static int[] natural(int[] r, int rotation, int sw, int sh) {
        if (rotation == 1) return new int[]{sh - r[3], r[0], sh - r[1], r[2]};
        if (rotation == 3) return new int[]{r[1], sw - r[2], r[3], sw - r[0]};
        if (rotation == 2) return new int[]{sw - r[2], sh - r[3], sw - r[0], sh - r[1]};
        return r;
    }

    /** The picture as the upright screen would hold it: the same glass, read the upright way. */
    private static Bitmap natural(Bitmap b, int rotation) {
        if (rotation == 0) return b;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(rotation == 1 ? 90 : rotation == 3 ? 270 : 180);
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, false);
    }

    /** A picture of the launcher as it is now, shared with MiuiHome without compressing it. */
    private static void capture(Activity a, boolean homeState) {
        if (link == null || hiddenDecor != null) return; // (unseen just now: there is nothing to take a picture of)
        if (copying) {
            again = true;
            return;
        }
        View d = a.getWindow().getDecorView();
        int w = d.getWidth(), h = d.getHeight();
        try {
            if (scratch == null || scratch.getWidth() != w || scratch.getHeight() != h) {
                scratch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            final Bitmap dst = scratch;
            final int fs = frameSeq, seq = ++snapSeq, rot = rotationOf(a);
            copying = true;
            PixelCopy.request(a.getWindow(), dst, result -> {
                boolean ok = false;
                if (result == PixelCopy.SUCCESS) {
                    try {
                        Bundle b = new Bundle();
                        Bitmap up = natural(dst, rot);
                        Bitmap shared = up.asShared();
                        if (up != dst && up != shared) up.recycle();
                        b.putParcelable("image", shared);
                        b.putBoolean("home", homeState);
                        b.putInt("turned", rot);
                        ok = send(MSG_SNAP, seq, b, null);
                    } catch (Throwable t) {
                        Log.w(TAG, "snapshot failed", t);
                    }
                }
                final boolean sent = ok;
                main.post(() -> {
                    copying = false;
                    if (sent) {
                        sentSeq = seq;
                        snapFrameSeq = fs;
                    }
                    Runnable waiting = whenFresh;
                    if (waiting != null && sent && fs >= downFrame - 1) {
                        whenFresh = null;
                        waiting.run();
                    }
                    if (again) {
                        again = false;
                        Activity now = homeRef.get();
                        if (now != null && resumed && !frozen) scan(now);
                    }
                });
            }, io);
        } catch (Throwable t) {
            copying = false; // no surface to copy from right now
        }
    }

    // ---- taps

    /** The icon cell under a point: the largest tile-sized view containing it. */
    private static View hitTest(View root, int x, int y) {
        View[] best = {null};
        findTile(root, x, y, root.getWidth() * 2 / 5, root.getHeight() / 4, best, 0);
        return best[0];
    }

    private static void findTile(View v, int x, int y, int maxW, int maxH, View[] best, int depth) {
        // (the window itself is hidden while the launcher stands behind MiuiHome; its contents still count)
        if ((depth > 0 && v.getVisibility() != View.VISIBLE) || v.getWidth() <= 0 || depth > 40) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (x < loc[0] || x >= loc[0] + v.getWidth() || y < loc[1] || y >= loc[1] + v.getHeight()) return;
        int w = v.getWidth(), h = v.getHeight();
        if (w >= 40 && h >= 40 && w <= maxW && h <= maxH) {
            View b = best[0];
            if (b == null || w * h > b.getWidth() * b.getHeight()) best[0] = v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findTile(g.getChildAt(i), x, y, maxW, maxH, best, depth + 1);
        }
    }

    /** True when the view sits on plain home, not in a drawer, folder or search result. */
    private static boolean onHome(View v) {
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (!homeViews.containsKey(p)) return false;
        }
        return !homeViews.isEmpty();
    }

    // ---- folders, learned from use

    /** package -> {icon rect, tile rect} of the home-screen folder it was last opened from */
    private static final Map<String, int[]> folders = new HashMap<>();
    private static int[] folderTap;
    private static long folderTapAt;
    private static boolean lastHomeState = true;
    private static boolean foldersLoaded;

    /**
     * A tap on a home-screen tile that is no app is, as far as can be told from outside, a folder being
     * opened. An app started from whatever that brought up belongs to it: when the app is closed again it
     * flies back into that folder, as on MiuiHome's own desktop.
     */
    private static void noteFolderTap(View tile) {
        try {
            if (tile == null) {
                if (lastHomeState) folderTap = null; // a tap elsewhere on plain home
                return;
            }
            if (!onHome(tile)) return; // inside something that is open
            if (tagComponent(tile.getTag()) != null || modelComponent(tile) != null) {
                folderTap = null;
                return;
            }
            int[] r = iconRect(tile), cell = screenRect(tile);
            if (r == null) return;
            folderTap = new int[]{r[0], r[1], r[2], r[3], cell[0], cell[1], cell[2], cell[3]};
            folderTapAt = SystemClock.uptimeMillis();
        } catch (Throwable t) {
            folderTap = null;
        }
    }

    private static void learnFolder(String target, View from) {
        int[] tap = folderTap;
        Activity a = homeRef.get();
        if (tap == null || target == null || a == null || target.equals(ownPkg)
                || SystemClock.uptimeMillis() - folderTapAt > 45000) return;
        try {
            if (from != null && onHome(from)) return; // started from the home screen itself
        } catch (Throwable t) {
            return;
        }
        synchronized (folders) {
            int[] old = folders.get(target);
            if (old != null && java.util.Arrays.equals(old, tap)) return;
            folders.put(target, tap);
            if (folders.size() > 300) folders.remove(folders.keySet().iterator().next());
            saveFolders(a);
        }
        // MiuiHome hears of it now: the next scan of the home screen comes only after this app is closed
        Bundle b = new Bundle();
        b.putString("pkg", target);
        View decor = a.getWindow().getDecorView();
        b.putIntArray("rect", natural(new int[]{tap[0], tap[1], tap[2], tap[3]}, rotationOf(a), decor.getWidth(), decor.getHeight()));
        send(MSG_FOLD, 0, b, null);
    }

    private static void loadFolders(Context c) {
        if (foldersLoaded) return;
        foldersLoaded = true;
        try {
            Map<String, ?> all = c.getSharedPreferences("hyperlauncher_folders", Context.MODE_PRIVATE).getAll();
            synchronized (folders) {
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    String[] p = String.valueOf(e.getValue()).split(",");
                    if (p.length != 8) continue;
                    int[] v = new int[8];
                    for (int i = 0; i < 8; i++) v[i] = Integer.parseInt(p[i]);
                    folders.put(e.getKey(), v);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "remembered folders could not be read", t);
        }
    }

    private static void saveFolders(Context c) {
        try {
            android.content.SharedPreferences.Editor ed =
                    c.getSharedPreferences("hyperlauncher_folders", Context.MODE_PRIVATE).edit().clear();
            for (Map.Entry<String, int[]> e : folders.entrySet()) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < 8; i++) b.append(i == 0 ? "" : ",").append(e.getValue()[i]);
                ed.putString(e.getKey(), b.toString());
            }
            ed.apply();
        } catch (Throwable t) {
            Log.w(TAG, "remembered folders could not be saved", t);
        }
    }

    /** The icon rect of the view that is starting {@code target}; remembered when it is a home-screen icon. */
    private static int[] learn(String target) {
        try {
            View v = clicked.get();
            if (v == null && SystemClock.uptimeMillis() - tappedAt < 1500) v = tapped;
            learnFolder(target, v);
            if (v == null || target == null || target.equals(ownPkg) || !(resumed || standIn) || homeRef.get() == null) return null;
            int[] r = iconRect(v);
            if (r == null || !shown(v)) return null;
            launchView = new WeakReference<>(v);
            if (onHome(v)) {
                int[] cell = screenRect(v);
                synchronized (learned) {
                    learned.remove(target);
                    learned.put(target, new int[]{r[0], r[1], r[2], r[3], cell[0], cell[1], cell[2], cell[3]});
                    learnedViews.put(target, new WeakReference<>(v));
                    if (learned.size() > 256) learned.remove(learned.keySet().iterator().next());
                }
            }
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "learn failed", t);
            return null;
        }
    }

    /**
     * Opening an app from an icon is handed to MiuiHome, whose own icon-to-window animation then runs from
     * the real icon. Only when MiuiHome holds a picture of exactly what is on screen; otherwise, and whenever
     * MiuiHome does not answer at once, the launcher opens the app its own way.
     */
    private static boolean handOver(Context ctx, Intent in, int[] rect, Runnable fallback) {
        try {
            Activity a = homeRef.get();
            if (bypass) return false;
            boolean behind = standIn && !resumed; // MiuiHome is showing this launcher's picture and passing touches on
            if (behind) standInLaunched = true;
            String why = rect == null ? "no icon view" : a == null || !(resumed || standIn) ? "launcher not in front"
                    : link == null ? "no link to MiuiHome" : pendingFallback != null ? "another launch pending"
                    // MiuiHome's Launcher would turn the whole screen upright to play it
                    : rotationOf(a) != 0 ? "the screen is turned" : null;
            if (why == null) {
                View decor = a.getWindow().getDecorView();
                if (!tile(rect, decor.getWidth()) || !inside(rect, decor.getWidth(), decor.getHeight())) {
                    why = "not an icon-sized view " + java.util.Arrays.toString(rect);
                }
            }
            ComponentName cn = in.getComponent();
            Bundle extras = in.getExtras();
            if (why == null && (cn == null || !Intent.ACTION_MAIN.equals(in.getAction())
                    || !in.hasCategory(Intent.CATEGORY_LAUNCHER) || in.getData() != null
                    || (extras != null && !extras.isEmpty()))) {
                why = "not a plain app launch: " + in;
            }
            if (why == null) {
                try { // off with `settings put global ag_launch 0`
                    if (Settings.Global.getInt(ctx.getContentResolver(), "ag_launch", 1) != 1) why = "switched off";
                } catch (Throwable t) {
                    // unreadable here: leave it on
                }
            }
            if (why != null) {
                Log.i(TAG, "own animation for " + (cn == null ? in.getPackage() : cn.getPackageName()) + ": " + why);
                if (!behind) return false;
                // not in front, so this is no moment to start anything: it goes ahead once MiuiHome has
                // put the launcher back
                afterResume = fallback;
                send(MSG_SWAP, 0, null, null);
                return true;
            }
            pendingFallback = fallback;
            final int id = ++launchId;
            Runnable go = () -> {
                if (launchId != id || pendingFallback == null) return;
                Bundle b = new Bundle();
                b.putParcelable("launch", new Intent(in));
                b.putIntArray("rect", rect);
                b.putInt("seq", sentSeq);
                if (!send(MSG_LAUNCH, id, b, inbox)) {
                    Log.i(TAG, "own animation for " + cn.getPackageName() + ": MiuiHome unreachable");
                    fallBack();
                    return;
                }
                freeze();
                main.postDelayed(() -> {
                    if (launchId == id && pendingFallback != null) {
                        Log.i(TAG, "own animation for " + cn.getPackageName() + ": no answer from MiuiHome");
                        fallBack(); // MiuiHome is busy or gone
                    }
                }, 300);
            };
            // MiuiHome must hold a picture of the screen as it was when the finger came down. One is taken
            // at that moment; if it is still on its way, wait the few milliseconds it needs.
            if (sentSeq >= 0 && (behind || snapFrameSeq >= downFrame - 1)) { // behind: MiuiHome's picture is what was tapped
                go.run();
            } else {
                whenFresh = go;
                if (!copying) scan(a);
                main.postDelayed(() -> {
                    if (whenFresh != go) return;
                    whenFresh = null;
                    if (launchId == id && pendingFallback != null) {
                        Log.i(TAG, "own animation for " + cn.getPackageName() + ": no fresh picture in time");
                        fallBack();
                    }
                }, 160);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "hand-over failed", t);
            pendingFallback = null;
            return false;
        }
    }

    private static Runnable whenFresh;
    private static int settledLaunch = -1; // the hand-over that is known to have gone through, or been overtaken

    private static final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        if (!fromMiuiHome(msg.sendingUid)) return true;
        if (msg.what == MSG_LAUNCH_RESULT && msg.arg1 == launchId && pendingFallback != null) {
            if (msg.arg2 == 1) {
                // MiuiHome has it. Should the launcher all the same be back in front shortly with nothing
                // opened, the tap went astray on the way; open the app the launcher's own way then.
                Runnable f = pendingFallback;
                pendingFallback = null;
                int id = launchId;
                main.postDelayed(() -> {
                    // (in front again is not "still in front": if it left the screen meanwhile, or the user
                    // has touched it since, the app did open and whatever is happening now is something else)
                    if (launchId != id || !resumed || settledLaunch == id) return;
                    Log.i(TAG, "own animation: handed to MiuiHome but the launcher is still in front");
                    unfreeze();
                    bypass = true;
                    try {
                        f.run();
                    } catch (Throwable t) {
                        Log.w(TAG, "late fallback failed", t);
                    } finally {
                        bypass = false;
                    }
                }, 900);
            } else {
                Log.i(TAG, "own animation: MiuiHome declined");
                fallBack();
            }
        } else if (msg.what == MSG_OPENING) {
            // MiuiHome's flying icon is on screen, over the tapped one: now that one can go, unseen
            if (frozen && resumed) hideIcon();
        } else if (msg.what == MSG_EVENT) {
            Bundle d = msg.peekData();
            if (d != null) {
                d.setClassLoader(MotionEvent.class.getClassLoader());
                MotionEvent ev = d.getParcelable("ev", MotionEvent.class);
                if (ev != null) passedOn(ev, msg.arg1 == 1);
            }
        }
        return true;
    }));

    private static int miuiHomeUid = -1;

    private static boolean fromMiuiHome(int uid) {
        if (uid == miuiHomeUid) return true;
        Activity a = homeRef.get();
        String[] pkgs = a == null ? null : a.getPackageManager().getPackagesForUid(uid);
        if (pkgs != null) {
            for (String p : pkgs) {
                for (String t : TARGETS) {
                    if (t.equals(p)) {
                        miuiHomeUid = uid;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final List<MotionEvent> waiting = new ArrayList<>();

    /**
     * A touch made on MiuiHome's picture of this launcher while the two were swapping. It belongs here: it
     * is played into the launcher as it arrives, or as soon as the launcher is back in front.
     */
    private static void passedOn(MotionEvent ev, boolean direct) {
        Activity a = homeRef.get();
        if (a == null || a.isDestroyed()) {
            ev.recycle();
            return;
        }
        if (!resumed && direct && waiting.isEmpty()) {
            // MiuiHome stays in front for now: if this turns out to be a tap on an icon, it can open the app
            // from where it stands, with no swap of launchers at all.
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                standIn = true;
                standInLaunched = false;
                directDown = true;
            }
            play(a, ev);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                directDown = false;
                // a click is acted on a moment after the finger lifts; if no app was started by then, the
                // touch was for something else, and the launcher itself has to show it
                main.post(() -> main.post(() -> {
                    if (!standIn) return;
                    standIn = false;
                    if (!standInLaunched) send(MSG_SWAP, 0, null, null);
                }));
            }
            return;
        }
        if (!resumed) {
            if (waiting.size() < 400) {
                waiting.add(ev);
            } else {
                Log.i(TAG, "a passed-on touch was dropped: too many waiting");
                ev.recycle();
            }
            return;
        }
        playWaiting(a); // anything still waiting goes first: touches must arrive in the order they were made
        play(a, ev);
    }

    private static boolean standIn, standInLaunched, directDown;
    private static Runnable afterResume;

    private static void play(Activity a, MotionEvent ev) {
        try {
            a.dispatchTouchEvent(ev);
        } catch (Throwable t) {
            Log.w(TAG, "passed-on touch", t);
        } finally {
            ev.recycle();
        }
    }

    private static void playWaiting(Activity a) {
        if (waiting.isEmpty()) return;
        List<MotionEvent> list = new ArrayList<>(waiting);
        waiting.clear();
        long now = SystemClock.uptimeMillis();
        boolean fresh = now - list.get(0).getEventTime() < 1500
                && (list.get(0).getActionMasked() == MotionEvent.ACTION_DOWN || directDown); // or its start was played already
        directDown = false;
        if (!fresh) Log.i(TAG, "passed-on touches dropped: " + list.size() + " events, too old or incomplete");
        for (MotionEvent ev : list) {
            if (fresh) play(a, ev);
            else ev.recycle();
        }
    }

    private static void fallBack() {
        Runnable f = pendingFallback;
        pendingFallback = null;
        if (f == null) return;
        if (!resumed) { // the tap was passed on by MiuiHome: wait until the launcher is back in front
            afterResume = f;
            send(MSG_SWAP, 0, null, null);
            return;
        }
        unfreeze();
        bypass = true;
        try {
            f.run();
        } catch (Throwable t) {
            Log.w(TAG, "fallback start failed", t);
        } finally {
            bypass = false;
        }
    }

    /** Stops the launcher drawing, so its own launch effects do not show under MiuiHome's animation. */
    private static void freeze() {
        frozen = true;
        main.removeCallbacks(thaw);
        main.postDelayed(thaw, 900);
    }

    private static final Runnable thaw = IconSync::unfreeze;

    private static int thawFrames;
    private static WeakReference<View> launchView;
    private static Runnable showIcon;

    /**
     * MiuiHome's flying icon takes over from the tapped one, but for the frame or two until this launcher is
     * swapped out the real icon would still show beneath it. It leaves its slot now, name and all else staying.
     */
    private static void hideIcon() {
        restoreIcon();
        View v = launchView == null ? null : launchView.get();
        if (v == null || !v.isAttachedToWindow()) return;
        try {
            Runnable undo = null;
            Method m = null;
            try {
                m = v.getClass().getMethod("setIconVisible", boolean.class); // Launcher3 and its relatives
            } catch (NoSuchMethodException e) {
                // some other launcher
            }
            // Other launchers are left alone: letting them draw one more frame also lets through whatever
            // effect of their own they have begun, and a whole home screen blinking is worse than a sliver.
            if (m == null) return;
            final Method set = m;
            set.invoke(v, false);
            undo = () -> {
                try {
                    set.invoke(v, true);
                } catch (Throwable t) {
                    // the view is gone
                }
            };
            v.invalidate();
            showIcon = undo;
            thawFrames = 1;
        } catch (Throwable t) {
            Log.w(TAG, "the tapped icon could not be hidden", t);
        }
    }

    private static void restoreIcon() {
        Runnable r = showIcon;
        showIcon = null;
        thawFrames = 0;
        if (r == null) return;
        try {
            r.run();
        } catch (Throwable t) {
            // nothing to put back
        }
    }

    private static void unfreeze() {
        restoreIcon();
        if (!frozen) return;
        frozen = false;
        main.removeCallbacks(thaw);
        Activity a = homeRef.get();
        if (a != null && a.getWindow() != null) a.getWindow().getDecorView().invalidate();
    }

    // ---- coming back after a swipe home

    private static final Runnable handoffIdle = () -> {
        if (!handoffActive) return;
        if (frameSeq == handoffFrame) return; // nothing drawn yet; the cap below still ends it
        endHandoff(false);
    };

    private static final Runnable handoffCap = () -> endHandoff(false);

    /**
     * MiuiHome's animation has already brought the user home, so the launcher's own entrance must not play
     * again on top of it: its animations are cut to nothing until it has settled, then MiuiHome is told.
     */
    private static boolean coveredPending;
    private static WeakReference<View> hiddenDecor;

    /**
     * Under MiuiHome's picture of it the launcher stays unseen until it has settled. Seen through the
     * picture, everything in it that is see-through (a glass folder, a search bar) would show twice over,
     * brighter, until the picture is lifted.
     */
    private static void hide(Activity a, boolean hide) {
        try {
            View decor = hide ? a.getWindow().getDecorView() : hiddenDecor == null ? null : hiddenDecor.get();
            hiddenDecor = hide ? new WeakReference<>(decor) : null;
            if (decor == null) return;
            View.class.getMethod("setTransitionAlpha", float.class).invoke(decor, hide ? 0f : 1f);
            decor.invalidate();
        } catch (Throwable t) {
            hiddenDecor = null;
        }
    }

    private static void beginHandoff(Activity a, boolean covered) {
        if (covered) hide(a, true);
        handoffActive = true;
        handoffFrame = frameSeq;
        try {
            if (savedScale < 0f) {
                savedScale = ValueAnimator.getDurationScale();
                ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, 0f);
            }
        } catch (Throwable t) {
            savedScale = -1f;
        }
        main.removeCallbacks(handoffCap);
        main.postDelayed(handoffCap, 450);
    }

    private static void endHandoff(boolean touched) {
        if (hiddenDecor != null) hide(null, false);
        if (!handoffActive) return;
        handoffActive = false;
        main.removeCallbacks(handoffCap);
        main.removeCallbacks(handoffIdle);
        if (savedScale >= 0f) {
            try {
                ValueAnimator.class.getMethod("setDurationScale", float.class).invoke(null, savedScale);
            } catch (Throwable t) {
                // nothing else to try
            }
            savedScale = -1f;
        }
        send(touched ? MSG_TOUCH : MSG_READY, 0, null, null);
    }

    // ---- link

    private static void connect(Context ctx) {
        connect(ctx.getApplicationContext(), 0);
    }

    private static void connect(Context app, int target) {
        if (link != null || connecting || target >= TARGETS.length) return;
        connecting = true;
        try {
            app.sendOrderedBroadcast(new Intent(ACTION_CONNECT).setPackage(TARGETS[target]), null,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context c, Intent i) {
                            connecting = false;
                            Bundle ex = getResultExtras(false);
                            IBinder b = ex == null ? null : ex.getBinder("link");
                            if (b == null) {
                                connect(app, target + 1);
                                return;
                            }
                            try {
                                b.linkToDeath(() -> main.post(() -> {
                                    link = null;
                                    sentSeq = -1;
                                    // MiuiHome restarts by itself; find it again without waiting for a resume
                                    for (long wait : new long[]{1500, 3500, 7000, 15000}) {
                                        main.postDelayed(() -> {
                                            Activity h = homeRef.get();
                                            if (link == null && h != null) connect(h);
                                        }, wait);
                                    }
                                }), 0);
                                link = new Messenger(b);
                                main.removeCallbacks(settle);
                                main.postDelayed(settle, 50);
                            } catch (Throwable t) {
                                link = null;
                            }
                        }
                    }, main, 0, null, null);
        } catch (Throwable t) {
            connecting = false;
        }
    }

    private static boolean send(int what, int arg1, Bundle data, Messenger replyTo) {
        Messenger l = link;
        if (l == null) return false;
        Message m = Message.obtain();
        m.what = what;
        m.arg1 = arg1;
        if (data != null) m.setData(data);
        m.replyTo = replyTo == null ? inbox : replyTo; // MiuiHome answers, and sends taps back, through this
        m.arg2 = zoomOut != null ? 1 : 0; // whether this launcher holds the wallpaper at the stock level
        try {
            l.send(m);
            return true;
        } catch (Throwable t) {
            link = null;
            sentSeq = -1;
            return false;
        }
    }

    // ------------------------------------------------------------ MiuiHome side

    /** What the MiuiHome side needs from the module. */
    interface Host {
        String defaultHome();

        boolean launch(Intent launch, int[] rect, int seq);

        void launcherReady(boolean touched);

        /** The launcher was passed a touch while behind MiuiHome and has to come to the front for it. */
        void wantsFront();

        void pictureArrived();

        boolean showing(Bitmap picture);
    }

    static volatile Host host;
    /** The launcher as it looks on plain home, and as it looked at its latest scan whatever was open. */
    private static volatile Bitmap homeImage;
    private static volatile Bitmap openImage;
    static volatile int openSeq = -1;
    /** True when the real launcher was lying on its side, with the screen, when last heard from. */
    static volatile boolean homeTurned;
    private static volatile Map<String, int[]> published = new HashMap<>();
    private static volatile String publishedFrom;
    private static final Map<String, int[]> contractRects = new HashMap<>();
    private static final Map<String, String> labels = new HashMap<>();
    private static Context serverContext;
    private static boolean registered;
    private static int okUid = -1;
    private static String okHome;

    /** True when the point is on, or just beside, an app icon of {@code home}'s home screen as last reported. */
    static boolean onIcon(String home, int x, int y) {
        if (home == null || !home.equals(publishedFrom)) return false;
        for (Map.Entry<String, int[]> e : published.entrySet()) {
            if (!e.getKey().startsWith("pkg:")) continue; // a folder, or a name: the launcher's to deal with
            int[] r = e.getValue();
            int w = r[2] - r[0], h = r[3] - r[1];
            if (x >= r[0] - w / 4 && x < r[2] + w / 4 && y >= r[1] - h / 5 && y < r[3] + h / 2) return true; // the label counts
        }
        return false;
    }

    /** The picture of {@code home}: of plain home, or ({@code open}) of whatever it showed at its latest scan. */
    static Bitmap imageFor(String home, boolean open) {
        if (home == null || !home.equals(publishedFrom)) return null;
        Bitmap b = open ? openImage : homeImage;
        return b == null || b.isRecycled() ? null : b;
    }

    private static final Messenger server = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        try {
            String home = verifiedSender(msg.sendingUid);
            if (home != null) receive(msg, home);
        } catch (Throwable t) {
            Log.w(TAG, "link message failed", t);
        }
        return true;
    }));

    static synchronized void listen(Context c) {
        if (registered) return;
        registered = true;
        serverContext = c.getApplicationContext();
        serverContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent i) {
                if (!isOrderedBroadcast()) return;
                Bundle b = new Bundle();
                b.putBinder("link", server.getBinder());
                setResultExtras(b);
            }
        }, new IntentFilter(ACTION_CONNECT), Context.RECEIVER_EXPORTED);
    }

    /** The default launcher's package if {@code uid} is that launcher, else null: nobody else is heard. */
    private static String verifiedSender(int uid) {
        Host h = host;
        String home = h == null ? null : h.defaultHome();
        if (home == null || serverContext == null) return null;
        if (uid == okUid && home.equals(okHome)) return home;
        String[] pkgs = serverContext.getPackageManager().getPackagesForUid(uid);
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p.equals(home)) {
                    okUid = uid;
                    okHome = home;
                    return home;
                }
            }
        }
        return null;
    }

    private static volatile Messenger client;

    /** Frees a picture that has been replaced, unless it is still on screen somewhere. */
    private static void release(Bitmap old) {
        if (old == null || old == openImage || old == homeImage || old.isRecycled()) return;
        Host h = host;
        if (h == null || h.showing(old)) return;
        old.recycle();
    }

    /** Tells the real launcher that the opening animation it handed over has begun. */
    static void sendOpening() {
        Messenger c = client;
        if (c == null) return;
        try {
            c.send(Message.obtain(null, MSG_OPENING));
        } catch (Throwable t) {
            client = null;
        }
    }

    /** Passes a touch made on the cover on to the real launcher. */
    static void sendEvent(MotionEvent ev, boolean direct) {
        Messenger c = client;
        if (c == null) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) Log.i(TAG, "a touch could not be passed on: the launcher has not been heard from");
            return;
        }
        try {
            Message m = Message.obtain(null, MSG_EVENT, direct ? 1 : 0, 0);
            Bundle b = new Bundle();
            b.putParcelable("ev", ev);
            m.setData(b);
            c.send(m);
        } catch (Throwable t) {
            client = null;
        }
    }

    private static volatile boolean wallpaperHeld;

    /** True when the real launcher keeps the wallpaper at MiuiHome's own home level. */
    static boolean wallpaperHeldBy(String home) {
        return wallpaperHeld && home != null && home.equals(publishedFrom);
    }

    private static void receive(Message msg, String home) throws Throwable {
        if (msg.replyTo != null) client = msg.replyTo;
        if (msg.what != MSG_LAUNCH) wallpaperHeld = msg.arg2 == 1;
        Bundle d = msg.peekData();
        if (d != null) d.setClassLoader(Bitmap.class.getClassLoader());
        if (!home.equals(publishedFrom)) { // a different launcher than before: nothing kept applies to it
            if (msg.replyTo == null) client = null;
            published = new HashMap<>();
            homeImage = null;
            openImage = null;
            openSeq = -1;
            publishedFrom = home;
        }
        switch (msg.what) {
            case MSG_ICONS: {
                String[] keys = d == null ? null : d.getStringArray("keys");
                int[] flat = d == null ? null : d.getIntArray("rects");
                if (keys == null || flat == null || flat.length < keys.length * 4) return;
                Map<String, int[]> m = new HashMap<>();
                for (int k = 0; k < keys.length; k++) {
                    m.put(keys[k], new int[]{flat[k * 4], flat[k * 4 + 1], flat[k * 4 + 2], flat[k * 4 + 3]});
                }
                published = m;
                break;
            }
            case MSG_SNAP: {
                Bitmap b = d == null ? null : d.getParcelable("image", Bitmap.class);
                if (b == null || b.getWidth() <= 0) return;
                Bitmap oldOpen = openImage, oldHome = homeImage;
                openImage = b;
                openSeq = msg.arg1;
                homeTurned = d.getInt("turned", 0) != 0;
                if (d.getBoolean("home", false)) {
                    homeImage = b;
                    Host h = host;
                    if (h != null) h.pictureArrived(); // a chance to get MiuiHome's Launcher ready, if it is not
                }
                release(oldOpen);
                release(oldHome);
                break;
            }
            case MSG_LAUNCH: {
                boolean ok = false;
                Host h = host;
                Intent launch = d == null ? null : d.getParcelable("launch", Intent.class);
                int[] rect = d == null ? null : d.getIntArray("rect");
                if (h != null && launch != null && rect != null && rect.length == 4) {
                    ok = h.launch(launch, rect, d.getInt("seq", -2));
                }
                if (msg.replyTo != null) {
                    msg.replyTo.send(Message.obtain(null, MSG_LAUNCH_RESULT, msg.arg1, ok ? 1 : 0));
                }
                break;
            }
            case MSG_FOLD: {
                String pkg = d == null ? null : d.getString("pkg");
                int[] rect = d == null ? null : d.getIntArray("rect");
                if (pkg == null || rect == null || rect.length != 4 || !home.equals(publishedFrom)) return;
                Map<String, int[]> m = new HashMap<>(published);
                if (!m.containsKey("pkg:" + pkg)) m.put("fold:" + pkg, rect);
                published = m;
                break;
            }
            case MSG_SWAP: {
                Host h = host;
                if (h != null) h.wantsFront();
                break;
            }
            case MSG_READY:
            case MSG_TOUCH: {
                Host h = host;
                if (h != null) h.launcherReady(msg.what == MSG_TOUCH);
                break;
            }
            default:
                break;
        }
    }

    /**
     * The gesture-nav contract every Launcher3-style launcher speaks: the home intent carries the app that
     * is closing and a messenger, and the launcher answers with that app's exact icon bounds.
     */
    static Bundle contract(ComponentName app, String home) {
        Message cb = Message.obtain();
        cb.what = 1;
        cb.replyTo = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
            Bundle d = msg.getData();
            d.setClassLoader(RectF.class.getClassLoader());
            RectF r = d.getParcelable("gesture_nav_contract_icon_position", RectF.class);
            Object surface = d.getParcelable("gesture_nav_contract_surface_control", android.view.SurfaceControl.class);
            Message finish = d.getParcelable("gesture_nav_contract_finish_callback", Message.class);
            if (r != null && r.width() > 0) {
                synchronized (contractRects) {
                    contractRects.put(home + "|" + app.getPackageName(),
                            new int[]{(int) r.left, (int) r.top, (int) r.right, (int) r.bottom});
                }
            }
            // the launcher hides the icon until told the animation is over; ours already is
            try {
                if (finish != null && finish.replyTo != null) finish.replyTo.send(Message.obtain(finish));
                if (surface instanceof android.view.SurfaceControl) ((android.view.SurfaceControl) surface).release();
            } catch (Throwable t) {
                Log.w(TAG, "contract finish failed", t);
            }
            return true;
        }));
        Bundle b = new Bundle();
        b.putParcelable("android.intent.extra.COMPONENT_NAME", app);
        b.putParcelable("android.intent.extra.USER", android.os.Process.myUserHandle());
        b.putParcelable("android.intent.extra.REMOTE_CALLBACK", cb);
        return b;
    }

    /** The icon at a screen rect, cut from the launcher's own picture so it matches what it shows. */
    static Drawable iconDrawable(Context c, Rect r, Bitmap s) {
        if (s == null || s.isRecycled() || r.width() <= 0 || r.height() <= 0) return null;
        int l = Math.max(0, r.left), t = Math.max(0, r.top);
        int w = Math.min(r.right, s.getWidth()) - l, h = Math.min(r.bottom, s.getHeight()) - t;
        if (w <= 0 || h <= 0) return null;
        return new BitmapDrawable(c.getResources(), Bitmap.createBitmap(s, l, t, w, h));
    }

    /** What is known of the launcher's icons right now, for the log. */
    static String known(String home) {
        Map<String, int[]> m = published;
        StringBuilder b = new StringBuilder(m.size() + " icons from " + publishedFrom + (home != null && home.equals(publishedFrom) ? "" : " (home is " + home + ")") + ":");
        for (String k : m.keySet()) {
            if (k.startsWith("pkg:")) b.append(' ').append(k.substring(4));
            else if (k.startsWith("fold:")) b.append(" [").append(k.substring(5)).append(']');
        }
        return b.toString();
    }

    /** Icon rect for the closing app in the default launcher, or null when it is not on screen there. */
    static Rect lookup(Context c, String pkg, String home) {
        if (pkg == null || home == null) return null;
        Map<String, int[]> m = home.equals(publishedFrom) ? published : new HashMap<>();
        int[] r = m.get("pkg:" + pkg);
        int[] exact;
        synchronized (contractRects) {
            exact = contractRects.get(home + "|" + pkg);
        }
        if (r == null) r = m.get("fold:" + pkg); // inside a folder on the home screen: its icon is the way back
        if (r == null && !m.isEmpty()) {
            String label;
            synchronized (labels) {
                label = labels.get(pkg);
            }
            if (label == null) {
                try {
                    PackageManager pm = c.getPackageManager();
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim();
                } catch (Throwable t) {
                    label = "";
                }
                synchronized (labels) {
                    labels.put(pkg, label);
                }
            }
            if (!label.isEmpty()) r = m.get("label:" + label);
        }
        // The launcher's own answer is exact, but it is from the last time. It only refines a position the
        // latest scan still vouches for; alone it could point at whatever sits there now.
        if (exact != null && r != null && Math.abs((exact[0] + exact[2]) / 2 - (r[0] + r[2]) / 2) < 150
                && Math.abs((exact[1] + exact[3]) / 2 - (r[1] + r[3]) / 2) < 150) {
            r = exact;
        }
        return r == null ? null : new Rect(r[0], r[1], r[2], r[3]);
    }

    /** The launcher's picture, drawn to fill, with an optional cut-out where a floating icon will land. */
    static final class Picture extends Drawable {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap bmp;
        private Rect hole;

        void set(Bitmap b, Rect h) {
            bmp = b;
            hole = h == null ? null : new Rect(h);
            invalidateSelf();
        }

        void setHole(Rect h) {
            hole = h == null ? null : new Rect(h);
            invalidateSelf();
        }

        Bitmap bitmap() {
            return bmp;
        }

        @Override
        public void draw(Canvas c) {
            Bitmap b = bmp;
            if (b == null || b.isRecycled()) return;
            Rect o = getBounds();
            int save = c.save();
            if (hole != null) {
                float sx = o.width() / (float) b.getWidth(), sy = o.height() / (float) b.getHeight();
                c.clipOutRect(o.left + (hole.left - 6) * sx, o.top + (hole.top - 6) * sy,
                        o.left + (hole.right + 6) * sx, o.top + (hole.bottom + 6) * sy);
            }
            c.drawBitmap(b, null, o, paint);
            c.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
