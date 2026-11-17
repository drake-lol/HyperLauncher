package lol.drake.hyperlauncher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * Lets MiuiHome's gesture navigation and recents run while another launcher is the default home.
 *
 * MiuiHome only builds its gesture windows when it is the default home, and its Launcher activity
 * kills itself otherwise. Here it stays alive in the background as the recents host with its
 * desktop hidden, and every route "home" is handed to the real default launcher. A picture of that
 * launcher stands in for it while MiuiHome's animations run, so they look as they do on stock.
 */
public class Module extends XposedModule implements IconSync.Host {
    static final String TAG = "HyperLauncher";
    static final String FSG = "force_fsg_nav_bar";

    private static final long DEDUPE_MS = 400;
    private static final long WATCHDOG_MS = 250;
    private static final int WATCHDOG_STRIKES = 3;
    private static final int WATCHDOG_TAG = 0x7e570001;
    private static final int SNAP_TAG = 0x7e570003;
    private static final int FLOAT_TAG = 0x7e570004;
    /** The wallpaper scale the system gives a window that asks for no zoom at all. */
    private static final float WALLPAPER_REST = 1.2f;
    private static final String TARGET_IFACE = "com.miui.home.common.animate.LaunchAppAndBackHomeAnimTarget";
    private static final String WANTS = "ag_wants_fsg";
    private static final String LAST_HOME = "ag_last_home";

    private static volatile String ownPackage = "com.miui.home";

    // gesture state
    private static volatile String lastAnimType;
    private static volatile boolean touching;
    private static volatile boolean animating;
    private static volatile long animAt;
    private static volatile long openSeenAt;
    private static volatile long springAt;
    private static volatile long closingAt;
    /** The icon slot a window is flying into or out of. The picture leaves it empty: the moving icon is the icon. */
    private static volatile Rect hole;
    private static volatile boolean settled = true; // no window animation has started since the last one ended
    private static volatile boolean homeGesture; // the swipe started on the real home screen, not over an app
    private static volatile String closingPackage;
    private static volatile String gestureClosing;
    private static volatile ComponentName gestureTop; // what was in front when the finger came down
    private static volatile ComponentName closingComponent;
    private static volatile long lastClosingAt;
    private static volatile Rect lastTargetRect;
    private static volatile long lastTargetAt;

    // MiuiHome's launcher
    private static volatile boolean launcherResumed;
    private static WeakReference<Activity> launcherRef = new WeakReference<>(null);
    private static volatile long launchingAt;
    private static volatile long recentsAt;
    private static Object stableState;
    private static volatile boolean prewarming;
    private static boolean coldGesture;
    private static float gestureX, gestureY;
    private static Method getLauncher;
    private static boolean prewarmArmed;

    // hand-off to the real launcher
    private static long lastHomeStart;
    private static volatile long homeChangedAt;
    private static boolean watching;
    private static View coverView;
    private static Bitmap coverBitmap;
    private static boolean coverTouched; // a finger is down on the cover; its touches are being passed on
    private static boolean coverDone;    // the cover is no longer wanted, and goes when that finger lifts
    private static boolean coverSwapped; // the real launcher's window has been put on screen beneath the cover
    private static boolean coverReady;   // and the launcher says it has finished drawing itself

    // icon launches
    private static volatile Intent pendingLaunch;
    private static volatile Rect pendingRect;
    private static volatile long noIconLoggedAt, gestureDownAt;
    private static volatile Runnable heldUp;
    private static boolean replayingUp;
    private static volatile boolean openMode;
    private static volatile long openingUntil; // while an app opens from an icon, the home screen stays behind it

    // overlay
    private static volatile ImageView overlayView;
    private static volatile IconSync.Picture overlayPicture;
    private static Object floatTarget;
    private static Rect floatTargetRect;
    private static Bitmap floatTargetSource;
    private static ComponentName floatTargetApp;

    // reflection
    private static Class<?> overviewObserver;
    private static Method currentDefaultHome;
    private static Class<?> stateClass;
    private static Method isInState;
    private static Object overviewState;
    private static Object overviewWindowState;
    private static Object normalState;
    private static volatile String homeCache;
    private static volatile long homeCacheAt;

    private static volatile Module self;
    private static volatile int hooksOk, hooksAll;
    private ClassLoader cl;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(Log.INFO, TAG, "loaded in " + param.getProcessName() + " on " + getFrameworkName()
                + " " + getFrameworkVersion() + " api " + getApiVersion());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!param.isFirstPackage()) return;
        if (!"com.miui.home".equals(pkg) && !"com.mi.android.globallauncher".equals(pkg)) {
            try { // a third-party launcher in scope: report its icons and looks, hand over icon taps
                IconSync.installLauncher(this, pkg);
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "launcher-side hooks failed in " + pkg, t);
            }
            return;
        }
        ownPackage = pkg;
        cl = param.getClassLoader();
        try {
            installHooks();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "installHooks failed", t);
        }
        Status.serveWhenReady("system", new Status.Source() {
            @Override
            public void describe(android.os.Bundle b) {
                b.putString("hooks", hooksOk + "/" + hooksAll);
                b.putString("home", currentHome());
                b.putBoolean("hosting", proxyMode());
                b.putBoolean("linked", IconSync.imageFor(currentHome(), false) != null);
                Application app = currentApplication();
                if (app != null) b.putInt("gestures", Settings.Global.getInt(app.getContentResolver(), FSG, 0));
            }

            @Override
            public boolean run(String command) {
                Application app = currentApplication();
                if (!Status.CMD_GESTURES.equals(command) || app == null) return false;
                Settings.Global.putInt(app.getContentResolver(), WANTS, 1);
                Settings.Global.putInt(app.getContentResolver(), FSG, 1);
                return true;
            }
        });
    }

    // ---------------------------------------------------------------- hooks

    private void installHooks() {
        int ok = 0, all = 0;
        String[] names = {"default-home flag", "launcher stays alive", "app thread", "gesture tracking",
                "home hand-off", "launcher lifecycle", "leaving recents", "gesture setting", "icon target",
                "floating icon", "home blur", "home gesture blur", "wallpaper zoom",
                "touches on the picture"};
        ThrowingRunnable[] groups = {this::hookDefaultHomeFlag, this::hookFallbackHome, this::hookAppThread,
                this::hookGestureTracking, this::hookHomeHandoff, this::hookLauncherLifecycle,
                this::hookLeavingRecents, this::hookGestureSetting, this::hookIconTarget,
                this::hookFloatingIcon, this::hookHomeBlur, this::hookHomeGestureBlur, this::hookWallpaperZoom,
                this::hookPictureTouches};
        for (int i = 0; i < groups.length; i++) {
            all++;
            ok += guard(names[i], groups[i]);
        }
        IconSync.host = this;
        self = this;
        hooksOk = ok;
        hooksAll = all;
        log(Log.INFO, TAG, "hooks installed: " + ok + "/" + all + " in " + ownPackage);
    }

    private int guard(String what, ThrowingRunnable r) {
        try {
            r.run();
            return 1;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook group failed: " + what, t);
            return 0;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl);
    }

    private Class<?> safeCls(String n) {
        try {
            return cls(n);
        } catch (Throwable t) {
            return null;
        }
    }

    /** MiuiHome builds its gesture windows only when it believes it is the default home. */
    private void hookDefaultHomeFlag() throws Throwable {
        Class<?> recents = cls("com.miui.home.recents.BaseRecentsImpl");
        Field isDefault = recents.getDeclaredField("mIsUseMiuiHomeAsDefaultHome");
        isDefault.setAccessible(true);

        hook(recents.getDeclaredMethod("setIsUseMiuiHomeAsDefaultHome", boolean.class))
                .intercept(chain -> chain.proceed(new Object[]{true}));
        hook(recents.getDeclaredMethod("updateUseLauncherRecentsAndFsGesture"))
                .intercept(chain -> {
                    isDefault.setBoolean(chain.getThisObject(), true);
                    return chain.proceed();
                });
        Class<?> buildConfig = cls("com.miui.home.common.utils.BuildConfigUtils");
        currentDefaultHome = buildConfig.getDeclaredMethod("getCurrentDefaultHome");
        // Lets the gesture toggle in MiuiHome's settings skip the "make MiuiHome default" dialog.
        hook(buildConfig.getDeclaredMethod("isUseMiuiHomeAsDefaultHome", Context.class))
                .intercept(chain -> true);
        overviewObserver = cls("com.miui.home.recents.OverviewComponentObserver");
        // Recents only sends its stock task-click animation when home and overview are one and the same.
        hook(overviewObserver.getDeclaredMethod("isHomeAndOverviewSame")).intercept(chain -> true);
    }

    /** The Launcher activity finishes itself when it is not the default home; keep it as recents host. */
    private void hookFallbackHome() throws Throwable {
        Class<?> fb = cls("com.miui.home.launcher.FallbackHomeCompat");
        Field desired = fb.getDeclaredField("mDesiredHomePackageName");
        desired.setAccessible(true);
        hook(fb.getDeclaredMethod("needKillSelf")).intercept(chain -> {
            Object real = chain.proceed();
            // Keep killing itself only during setup or while no real home is chosen.
            return (Boolean) real && isSetupOrResolver((String) desired.get(chain.getThisObject()));
        });
        hook(fb.getDeclaredMethod("startFallbackHomeIfNeed")).intercept(chain -> {
            if (isSetupOrResolver((String) desired.get(chain.getThisObject()))) return chain.proceed();
            return null;
        });
    }

    private static boolean isSetupOrResolver(String pkg) {
        return pkg == null || pkg.equals("com.android.provision") || pkg.equals("android")
                || pkg.equals("com.android.intentresolver");
    }

    /** With no Launcher activity MiuiHome hands SystemUI a null app thread, which crashes it. */
    private void hookAppThread() throws Throwable {
        Class<?> util = cls("com.android.systemui.shared.recents.utilities.Utilities");
        hook(util.getDeclaredMethod("getIApplicationThread", ContextWrapper.class)).intercept(chain -> {
            if (chain.getArg(0) == null) {
                Application app = currentApplication();
                if (app != null) return chain.proceed(new Object[]{app});
            }
            return chain.proceed();
        });
    }

    /** Knows when a finger is down or a gesture animation is running, and dresses the picture for each. */
    private void hookGestureTracking() throws Throwable {
        // the moment MiuiHome has the closing app's window in hand: a lift that was kept back can go on
        Class<?> listener = safeCls("com.miui.home.recents.RecentsAnimationListenerImpl");
        boolean hooked = false;
        if (listener != null) {
            for (Method m : listener.getDeclaredMethods()) {
                if (!m.getName().equals("onAnimationStart")) continue;
                hooked = true;
                hook(m).intercept(chain -> {
                    Object r = chain.proceed();
                    Runnable held = heldUp;
                    if (held != null) {
                        main.removeCallbacks(held);
                        main.postDelayed(held, 24);
                    }
                    return r;
                });
            }
        }
        if (!hooked) log(Log.INFO, TAG, "no sign of the recents animation starting on this build; a quick swipe may wait");
        Class<?> nav = cls("com.miui.home.recents.NavStubView");
        Class<?> animType = cls("com.miui.home.recents.util.RectFSpringAnim$AnimType");
        hook(nav.getDeclaredMethod("setWindowAnimType", animType)).intercept(chain -> {
            Object t = chain.getArg(0);
            if (t != null) {
                String type = t.toString();
                long now = SystemClock.uptimeMillis();
                lastAnimType = type;
                if (type.startsWith("CLOSE_")) {
                    // An app that is closing is not opening any more, however short a time ago it was tapped:
                    // the home screen behind it must come back in, not stay faded out from that opening.
                    openMode = false;
                    openingUntil = 0;
                }
                Runnable held = heldUp;
                if (held != null) { // ready now: the lift that was kept back can go on, after this has settled in
                    main.removeCallbacks(held);
                    main.postDelayed(held, 16);
                }
                animating = true;
                animAt = now;
                settled = false;
                if (type.startsWith("OPEN_")) {
                    openSeenAt = now;
                    if (now < openingUntil) IconSync.sendOpening();
                    if (coverHoldUntil != 0) { // the window grows out of the icon from here: the picture has done its part
                        coverHoldUntil = 0;
                        main.postDelayed(() -> removeCover(true), 24);
                    }
                }
                // From here until the real launcher is back, a touch on the home screen is meant for it. A
                // window of the module's own takes those touches: MiuiHome's windows lose theirs midway
                // when the two launchers swap.
                if (type.startsWith("CLOSE_TO_HOME")) main.post(this::catchTouches);
                if (type.startsWith("CLOSE_TO_RECENTS")) {
                    homeGesture = false; // recents wants its own blur, wherever the swipe began
                    setHole(null);
                } else if (type.startsWith("CLOSE_TO_DRAG")) {
                    main.post(() -> {
                        IconSync.Picture p = overlayPicture;
                        Bitmap home = homePicture();
                        if (p != null && home != null && !openMode) p.set(home, hole);
                    });
                } else if (!type.startsWith("CLOSE_TO_HOME") && !type.startsWith("OPEN_FROM_HOME")) {
                    setHole(null); // going to another app or to recents: nothing lands on the home screen
                }
            } else if (openingUntil != 0 && openSeenAt >= launchingAt) {
                // the app has grown out of its icon: the home screen behind it has done its part
                openingUntil = 0;
                if (!launcherResumed) openMode = false;
            }
            return chain.proceed();
        });
        // MiuiHome runs its home-screen gesture (the screen shrinking under the finger, recents sliding in)
        // only when its own Launcher is on top. The real launcher counts as that; its picture stands in.
        hook(nav.getDeclaredMethod("isLauncherOnTop")).intercept(chain -> {
            if (homeGesture && proxyMode()) return true;
            return chain.proceed();
        });
        hook(nav.getDeclaredMethod("checkAndLauncherHome")).intercept(chain -> {
            if (!proxyMode()) return chain.proceed();
            bringOwnLauncherFront();
            return null;
        });
        hook(nav.getDeclaredMethod("onTouchEvent", MotionEvent.class)).intercept(chain -> {
            MotionEvent ev = (MotionEvent) chain.getArg(0);
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_UP && heldUp == null && !replayingUp && proxyMode() && !homeGesture
                    && IconSync.homeTurned // (only then is there that wait; anywhere else this would just delay the swipe)
                    && animAt < gestureDownAt && SystemClock.uptimeMillis() - gestureDownAt < 500
                    && gestureY - ev.getRawY() > 120 && ownLauncher() != null) {
                // The finger has left before MiuiHome's Launcher was ready to animate: it takes its time when
                // it first has to turn itself upright from a screen lying on its side. MiuiHome would call the
                // swipe off. The lift is kept back for the moment that takes, and the swipe goes through.
                final MotionEvent up = MotionEvent.obtain(ev);
                final Object nav0 = chain.getThisObject();
                heldUp = () -> {
                    if (heldUp == null) return;
                    heldUp = null;
                    replayingUp = true;
                    try {
                        nav0.getClass().getMethod("onTouchEvent", MotionEvent.class).invoke(nav0, up);
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "a kept-back lift could not be passed on", t);
                    } finally {
                        replayingUp = false;
                        up.recycle();
                    }
                };
                main.postDelayed(heldUp, 400);
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN) {
                gestureDownAt = SystemClock.uptimeMillis();
                if (heldUp != null) heldUp.run();
                touching = true;
                openMode = false;
                hole = null;
                gestureTop = topComponent();
                // (lingering after a swipe home, MiuiHome's Launcher is still standing in for the real one)
                boolean lingering = lingerUntil != 0 && launcherResumed;
                lingerUntil = 0;
                boolean atHome = lingering || foregroundIsHome();
                homeGesture = atHome && ownLauncher() != null && homePicture() != null;
                if (homeGesture) bringOwnLauncherFront();
                coldGesture = proxyMode() && !atHome && ownLauncher() == null;
                gestureX = ev.getRawX();
                gestureY = ev.getRawY();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                touching = false;
                if (homeGesture) {
                    main.postDelayed(this::catchTouches, 24);
                    for (long wait : new long[]{120, 280, 480, 800}) main.postDelayed(this::endHomeGestureIfRested, wait);
                }
                // Right after MiuiHome restarts its Launcher does not exist yet, and this first swipe cannot
                // be animated. It must still do what was asked: an upward swipe goes home.
                float up = gestureY - ev.getRawY();
                if (coldGesture && a == MotionEvent.ACTION_UP && up > 250 && up > Math.abs(ev.getRawX() - gestureX)) {
                    main.postDelayed(() -> {
                        if (!foregroundIsHome() && !animatingNow()) openRealHome();
                    }, 150);
                }
                coldGesture = false;
            }
            return chain.proceed();
        });
    }

    /** True while a gesture is being animated or any of MiuiHome's window animations is still moving. */
    private static boolean animatingNow() {
        long now = SystemClock.uptimeMillis();
        return (animating && now - animAt < 3000) || now - springAt < 150;
    }

    /** A finished swipe-home leaves MiuiHome's Launcher in front; hand over to the real home. */
    private void hookHomeHandoff() throws Throwable {
        Class<?> wtc = cls("com.android.systemui.shared.recents.system.WindowTransitionCompat");
        Method finish = wtc.getDeclaredMethod("finishTransition",
                boolean.class, boolean.class, boolean.class, int.class);
        hook(finish).intercept(chain -> {
            // the slot gets its icon back before MiuiHome drops the floating one, never a frame after
            settled = true; // a spring may still twitch for a frame or two; that is not movement any more
            setHole(null);
            // A launcher that turns with the screen has to be in front before MiuiHome's upright one is left
            // standing there: left alone for a moment, that one turns the whole screen upright and back.
            boolean early = IconSync.homeTurned && (Boolean) chain.getArg(0) && lastAnimType != null
                    && lastAnimType.startsWith("CLOSE_TO_HOME") && SystemClock.uptimeMillis() - launchingAt >= 2500;
            if (early) startRealHomeNow();
            Object r = chain.proceed();
            String type = lastAnimType;
            lastAnimType = null;
            animating = false;
            springAt = 0;
            // Ending in recents reports toHome too; only a real swipe-home leaves for the default launcher.
            if (SystemClock.uptimeMillis() - launchingAt < 2500) {
                return r; // an app is already being opened from the picture; nothing to go home to
            }
            if (early) return r;
            if ((Boolean) chain.getArg(0) && type != null && type.startsWith("CLOSE_TO_HOME")) {
                linger();
            } else { // an app closed some other way and left MiuiHome's Launcher in front
                Activity a = launcherRef.get();
                if (a != null) main.postDelayed(() -> leaveIfIdle(a), 32);
            }
            return r;
        });
    }

    private void hookLauncherLifecycle() throws Throwable {
        Class<?> bl = cls("com.miui.home.launcher.BaseLauncher");
        stateClass = cls("com.miui.home.launcher.LauncherState");
        isInState = bl.getMethod("isInState", stateClass);

        hook(bl.getDeclaredMethod("onResume")).intercept(chain -> {
            Object r = chain.proceed();
            Activity a = (Activity) chain.getThisObject();
            launcherResumed = true;
            launcherRef = new WeakReference<>(a);
            View decor = a.getWindow().getDecorView();
            IconSync.listen(a);
            hideDesktop(a);
            installOverlay(a);
            startWatchdog(a);
            Intent pend = pendingLaunch;
            Rect prect = pendingRect;
            if (pend != null) {
                pendingLaunch = null;
                decor.post(() -> {
                    boolean ok = false;
                    try {
                        ok = runIconLaunch(pend, prect, IconSync.imageFor(currentHome(), true));
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "icon launch failed", t);
                    }
                    if (!ok) {
                        log(Log.INFO, TAG, "icon launch: MiuiHome's animation refused it, starting plainly");
                        startDirect(pend); // never lose the tap
                    }
                });
            } else if (prewarming) { // created ahead of time: hand the screen straight back
                prewarming = false;
                // Not before it has really drawn, picture and all: the first frame of a new Launcher takes
                // many times longer than any later one, and that is the wait this is here to get out of the way.
                PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
                boolean[] left = {false};
                Runnable back = () -> {
                    if (left[0]) return;
                    left[0] = true;
                    openRealHome();
                    if (++warmRuns == 1) main.postDelayed(prewarm, 500); // once more, from having been put away
                };
                if (pm.isInteractive()) {
                    int[] frames = {0};
                    Runnable[] next = new Runnable[1];
                    next[0] = () -> decor.getViewTreeObserver().registerFrameCommitCallback(() -> {
                        if (++frames[0] >= 3) decor.post(back);
                        else decor.post(() -> {
                            next[0].run();
                            decor.invalidate();
                        });
                    });
                    next[0].run();
                    decor.invalidate();
                    decor.postDelayed(back, 700);
                } else {
                    decor.postDelayed(back, 300);
                }
            } else if (!homeGesture) {
                // in front with no gesture behind it: an app above it closed. Nothing of its own to show.
                decor.postDelayed(() -> leaveIfIdle(a), 150);
                decor.postDelayed(() -> {
                    if (!touching && !openMode && SystemClock.uptimeMillis() - launchingAt > 2500) catchTouches();
                }, 40);
            }
            return r;
        });
        hook(bl.getDeclaredMethod("onPause")).intercept(chain -> {
            Activity a = (Activity) chain.getThisObject();
            animating = false;
            launcherResumed = false;
            homeGesture = false;
            // (an app opening from an icon pauses this Launcher at once, with the home screen still to fade
            // out behind the growing window)
            if (SystemClock.uptimeMillis() >= openingUntil) openMode = false;
            stopWatchdog(a);
            return chain.proceed();
        });
    }

    /** Leaving recents by any route ends on MiuiHome's empty desktop; go to the real home at once. */
    private void hookLeavingRecents() throws Throwable {
        Class<?> sm = cls("com.miui.home.launcher.LauncherStateManager");
        hook(sm.getDeclaredMethod("onStateTransitionEnd", stateClass)).intercept(chain -> {
            Object r = chain.proceed();
            Object st = chain.getArg(0);
            Object prev = stableState;
            stableState = st;
            resolveStates();
            if (st != null && st == normalState && prev != null
                    && (prev == overviewState || prev == overviewWindowState)) {
                Activity a = launcherRef.get();
                if (a != null) main.postDelayed(() -> whenRested(() -> leaveIfIdle(a), 500), 30);
            }
            return r;
        });
        // opening a task from recents passes through the same state change; that is not a way home
        Class<?> task = cls("com.miui.home.recents.views.TaskView");
        for (Method m : task.getDeclaredMethods()) {
            if (!m.getName().equals("launchTask")) continue;
            hook(m).intercept(chain -> {
                launchingAt = SystemClock.uptimeMillis();
                return chain.proceed();
            });
        }
        // recents asked for by a key or shortcut, with no gesture: leave the Launcher in front for it
        Class<?> och = safeCls("com.miui.home.recents.OverviewCommandHelper");
        if (och != null) {
            for (Method m : och.getDeclaredMethods()) {
                if (!m.getName().equals("onOverviewToggle") && !m.getName().equals("onOverviewShown")) continue;
                hook(m).intercept(chain -> {
                    recentsAt = SystemClock.uptimeMillis();
                    return chain.proceed();
                });
            }
        }
    }

    private static void resolveStates() {
        try {
            if (overviewState == null) { // LauncherState initialises only once the app is up
                overviewState = stateClass.getField("OVERVIEW").get(null);
                overviewWindowState = stateClass.getField("OVERVIEW_WINDOW").get(null);
                normalState = stateClass.getField("NORMAL").get(null);
            }
        } catch (Throwable t) {
            // stays unknown; callers treat that as "do nothing"
        }
    }

    /** Changing the default launcher makes the system turn gesture navigation off; put it back. */
    private void hookGestureSetting() throws Throwable {
        hook(overviewObserver.getDeclaredMethod("updateOverviewTargets")).intercept(chain -> {
            homeChangedAt = SystemClock.uptimeMillis();
            homeCacheAt = 0;
            Application app = currentApplication();
            if (app != null) IconSync.listen(app);
            watchGestureSetting();
            schedulePrewarm();
            return chain.proceed();
        });
    }

    /** The swipe-home window shrinks into the app's icon in the real launcher when it is known. */
    private void hookIconTarget() throws Throwable {
        Class<?> nav = cls("com.miui.home.recents.NavStubView");
        Field targetRect = nav.getDeclaredField("mAppToHomeTargetRect");
        targetRect.setAccessible(true);
        hook(nav.getDeclaredMethod("getClosingApp", cls("com.miui.home.launcher.BaseLauncher"), boolean.class))
                .intercept(chain -> {
                    Object r = chain.proceed();
                    if (r != null) {
                        ComponentName cn = (ComponentName) r.getClass().getField("componentName").get(r);
                        gestureClosing = cn == null ? null : cn.getPackageName();
                        if (cn != null) closingComponent = cn;
                    }
                    return r;
                });
        // With no icon of its own to aim at, MiuiHome sets a default spot; swap in the real launcher's icon.
        hook(nav.getDeclaredMethod("findClosingAnimTarget", boolean.class, ActivityManager.RunningTaskInfo.class))
                .intercept(chain -> {
                    gestureClosing = null;
                    ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo) chain.getArg(1);
                    if (task != null && task.baseActivity != null) {
                        gestureClosing = task.baseActivity.getPackageName();
                        closingComponent = task.baseActivity;
                    }
                    Object r = chain.proceed();
                    long now = SystemClock.uptimeMillis();
                    // MiuiHome does not always say which app this is. The app that was in front when the
                    // finger came down is the one being closed; never one remembered from an earlier gesture.
                    ComponentName top = gestureTop;
                    if (top != null) { // MiuiHome's own idea of the last app can be an older one
                        gestureClosing = top.getPackageName();
                        closingComponent = top;
                    }
                    if (gestureClosing != null) lastClosingAt = now;
                    String pkg = gestureClosing;
                    Application app = currentApplication();
                    if (pkg == null || app == null || !proxyMode()) return r;
                    Rect icon = IconSync.lookup(app, pkg, currentHome());
                    if (icon == null) {
                        long t = SystemClock.uptimeMillis();
                        if (t - noIconLoggedAt > 1000) {
                            noIconLoggedAt = t;
                            log(Log.INFO, TAG, "closing " + pkg + ": no icon known for it; " + IconSync.known(currentHome()));
                        }
                        return r;
                    }
                    // MiuiHome's own hidden desktop may hold an icon for this app; the real one wins
                    ((Rect) targetRect.get(chain.getThisObject())).set(icon);
                    aimed(pkg, icon, "gesture");
                    lastTargetRect = new Rect(icon);
                    setHole(icon);
                    lastTargetAt = now;
                    return floatingTarget(((View) chain.getThisObject()).getContext(), icon,
                            homePicture(), closingComponent);
                });

        // the same aim for windows that close without a gesture: back at an app's first screen, or its exit
        Class<?> wap = cls("com.miui.home.recents.anim.WindowAnimParamsProvider");
        for (Method m : wap.getDeclaredMethods()) {
            String n = m.getName();
            if (n.equals("getClosingWindowAnimParams")) {
                hook(m).intercept(chain -> {
                    closingPackage = null;
                    closingAt = SystemClock.uptimeMillis();
                    settled = false;
                    try {
                        Object set = chain.getArg(2);
                        Object first = set.getClass().getMethod("getFirstTarget").invoke(set);
                        if (first != null) closingPackage = (String) first.getClass().getField("packageName").get(first);
                    } catch (Throwable t) {
                        // falls back to the default spot
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        closingPackage = null;
                    }
                });
            } else if (n.equals("findClosingAnimTarget") && m.getParameterCount() == 2) {
                hook(m).intercept(chain -> {
                    Application app = currentApplication();
                    if (app == null || !proxyMode()) return chain.proceed();
                    // MiuiHome's hidden desktop has icons of its own; only the real launcher's may be flown to
                    String pkg = closingPackage;
                    Rect icon = pkg != null ? IconSync.lookup(app, pkg, currentHome()) : null;
                    if (icon == null) {
                        long t = SystemClock.uptimeMillis();
                        if (t - noIconLoggedAt > 1000) {
                            noIconLoggedAt = t;
                            log(Log.INFO, TAG, "closing " + pkg + " (no gesture): no icon known for it; "
                                    + IconSync.known(currentHome()));
                        }
                        return null;
                    }
                    aimed(pkg, icon, "no gesture");
                    lastTargetRect = new Rect(icon);
                    setHole(icon);
                    lastTargetAt = SystemClock.uptimeMillis();
                    return floatingTarget(app, icon, homePicture(), new ComponentName(pkg, pkg));
                });
            } else if (n.equals("getIconRect")) {
                hook(m).intercept(chain -> {
                    String pkg = closingPackage;
                    Application app = currentApplication();
                    if (pkg != null && app != null && proxyMode()) {
                        Rect r = IconSync.lookup(app, pkg, currentHome());
                        if (r != null) return r;
                    }
                    return chain.proceed();
                });
            }
        }
    }

    private static volatile long aimLoggedAt;

    private static void aimed(String pkg, Rect icon, String how) {
        long t = SystemClock.uptimeMillis();
        if (t - aimLoggedAt < 400) return;
        aimLoggedAt = t;
        Log.i(TAG, "closing " + pkg + " (" + how + "): aiming at its icon " + icon.toShortString());
    }

    /** Follows a closing window, to empty the icon slot it is flying into until it gets there. */
    private void hookHomeBlur() throws Throwable {
        Class<?> spring = cls("com.miui.home.recents.util.RectFSpringAnim");
        Field cur = spring.getDeclaredField("mCurrentRect");
        Field tgt = spring.getDeclaredField("mTargetRect");
        cur.setAccessible(true);
        tgt.setAccessible(true);
        Method upd = null;
        for (Method m : spring.getDeclaredMethods()) if (m.getName().equals("onUpdate") && m.getParameterCount() == 1) upd = m;
        if (upd == null) throw new NoSuchMethodException("RectFSpringAnim.onUpdate");
        Field kind = spring.getDeclaredField("mLastAminType");
        kind.setAccessible(true);
        hook(upd).intercept(chain -> {
            Object r = chain.proceed();
            if (settled) return r;
            springAt = SystemClock.uptimeMillis();
            // (the slot stays empty until MiuiHome drops the flying icon: filled any sooner, the icon in the
            // picture shows around the flying one while that is still settling)
            return r;
        });
    }

    /** A swipe up from the real home screen has nothing to open, so MiuiHome's blur must not touch it. */
    private void hookHomeGestureBlur() throws Throwable {
        Class<?> bu = cls("com.miui.home.launcher.common.BlurUtils");
        for (Method m : bu.getDeclaredMethods()) {
            String n = m.getName();
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
            if (!n.equals("fastBlurWhenGestureAppModeStart") && !n.equals("fastBlurWhenUseCompleteRecentsBlur")
                    && !n.equals("fastBlurWhenEnterRecents")) continue;
            hook(m).intercept(chain -> chain.proceed());
        }
    }

    private static boolean downOurs;

    /**
     * While MiuiHome's Launcher is in front showing the picture of the real home screen, touches land on
     * MiuiHome's own hidden desktop. They must never reach it: an unseen icon there would open. A tap on
     * the picture is treated as a tap on the real launcher instead: on a known icon it opens that app at
     * once, as on stock; anywhere else it is passed on once the real launcher is back.
     */
    private void hookPictureTouches() throws Throwable {
        Method dispatch = cls("com.miui.home.launcher.BaseLauncher")
                .getMethod("dispatchTouchEvent", MotionEvent.class);
        hook(dispatch).intercept(chain -> {
            Activity a = (Activity) chain.getThisObject();
            MotionEvent ev = (MotionEvent) chain.getArg(0);
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                String type = lastAnimType;
                downOurs = proxyMode() && !inOverview(a)
                        && (type == null || !type.startsWith("CLOSE_TO_RECENTS"))
                        && SystemClock.uptimeMillis() - recentsAt > 1500;
            }
            if (!downOurs) return chain.proceed();
            pictureTouch(ev);
            return true;
        });
        // while a swipe home is still animating, touches arrive by this route instead
        Method consumer = cls("com.miui.home.recents.NavStubView")
                .getDeclaredMethod("onInputConsumerEvent", MotionEvent.class);
        hook(consumer).intercept(chain -> {
            if (!proxyMode()) return chain.proceed();
            // recents takes its own touches; everything else would land on MiuiHome's hidden desktop
            String type = lastAnimType;
            Activity a = launcherRef.get();
            if ((type != null && type.startsWith("CLOSE_TO_RECENTS")) || a == null || inOverview(a)
                    || SystemClock.uptimeMillis() - recentsAt < 1500) return chain.proceed();
            pictureTouch((MotionEvent) chain.getArg(0));
            return true;
        });
    }

    /**
     * A touch on the picture of home is meant for the real launcher, which alone knows what is under the
     * finger: an icon, a folder, a widget, a page to swipe. Every such touch is passed on as it happens and
     * the launcher is brought back for it at once, cutting short whatever was still animating.
     */
    private void pictureTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        MotionEvent copy = MotionEvent.obtain(ev);
        main.post(() -> {
            IconSync.sendEvent(copy, false);
            copy.recycle();
            // bringing the launcher back now would cancel this touch midway; do it as the finger lifts
            if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && launcherResumed) {
                homeGesture = false;
                openRealHome();
            }
        });
    }

    /**
     * MiuiHome zooms the wallpaper between its own "home" and "app" levels. The real launcher keeps the
     * wallpaper at the system's resting level, so every hand-over in or out would end with the wallpaper
     * snapping. Here MiuiHome holds that resting level throughout.
     */
    private void hookWallpaperZoom() throws Throwable {
        Class<?> param = cls("com.miui.home.recents.anim.WallpaperParam");
        java.lang.reflect.Constructor<?> make = param.getDeclaredConstructor(float.class, float.class, float.class);
        make.setAccessible(true);
        int hooked = 0;
        for (String name : new String[]{"SystemWallpaperElement", "LocalWallpaperElement"}) {
            Class<?> c = safeCls("com.miui.home.recents.anim." + name);
            if (c == null) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("animTo") && !m.getName().equals("setTo")) continue;
                if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != param) continue;
                hook(m).intercept(chain -> {
                    // a launcher the module runs in rests at MiuiHome's own level; nothing to adjust then
                    if (!proxyMode() || IconSync.wallpaperHeldBy(currentHome())) return chain.proceed();
                    return chain.proceed(new Object[]{make.newInstance(WALLPAPER_REST, 1.05f, 0.6f)});
                });
                hooked++;
            }
        }
        if (hooked == 0) throw new NoSuchMethodException("wallpaper zoom");
    }

    // ---------------------------------------------------------------- floating icon

    /** An icon view the window animations can fly to and from: it reports the real launcher's icon. */
    private synchronized Object floatingTarget(Context c, Rect icon, Bitmap source, ComponentName app) {
        try {
            if (floatTarget != null && icon.equals(floatTargetRect) && source == floatTargetSource
                    && (app == null ? floatTargetApp == null : app.equals(floatTargetApp))) return floatTarget;
            Drawable d = IconSync.iconDrawable(c, icon, source);
            if (d == null) return null;
            View v = (View) cls("com.miui.home.launcher.ShortcutIcon").getConstructor(Context.class).newInstance(c);
            v.setTag(FLOAT_TAG, new Object[]{new Rect(icon), d});
            // MiuiHome's floating icon layers read the icon's tag as a ShortcutInfo
            Object info = cls("com.miui.home.launcher.ShortcutInfo").getConstructor().newInstance();
            if (app != null) {
                Field mi = info.getClass().getDeclaredField("mIntent");
                mi.setAccessible(true);
                mi.set(info, new Intent(Intent.ACTION_MAIN).setComponent(app));
            }
            v.setTag(info);
            floatTarget = v;
            floatTargetRect = new Rect(icon);
            floatTargetSource = source;
            floatTargetApp = app;
            return v;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "floating icon target failed", t);
            return null;
        }
    }

    private static Object[] floatMark(Object view) {
        return view instanceof View ? (Object[]) ((View) view).getTag(FLOAT_TAG) : null;
    }

    private interface MarkedValue {
        Object get(Object[] mark);
    }

    private void hookFloating(String name, MarkedValue value, Class<?>... params) {
        for (Class<?> c : new Class<?>[]{safeCls("com.miui.home.launcher.ShortcutIcon"), safeCls(TARGET_IFACE)}) {
            if (c == null) continue;
            try {
                Method m = c.getDeclaredMethod(name, params);
                hook(m).intercept(chain -> {
                    Object[] mark = floatMark(chain.getThisObject());
                    return mark == null ? chain.proceed() : value.get(mark);
                });
            } catch (NoSuchMethodException e) {
                // not declared here; the other class may
            } catch (Throwable t) {
                log(Log.WARN, TAG, "float hook " + name + " failed", t);
            }
        }
    }

    private void hookFloatingIcon() {
        hookFloating("getAnimTargetOriginalLocation", m -> new Rect((Rect) m[0]));
        hookFloating("getIconLocation", m -> new Rect((Rect) m[0]));
        hookFloating("getContentDrawable", m -> m[1]);
        hookFloating("getIconRadius", m -> ((Rect) m[0]).width() * 0.225f);
        hookFloating("isLauncherView", m -> false);
        hookFloating("isTargetAttachedToWindow", m -> true);
        hookFloating("getAnimTargetVisibility", m -> 0);
        hookFloating("getIconTransparentEdge", m -> 0);
        // the real icon belongs to the other launcher; nothing here may hide, fade or reset it
        hookFloating("setAnimTargetVisibility", m -> null, int.class);
        hookFloating("setAnimTargetAlpha", m -> null, float.class);
        hookFloating("onEnterHomeAnimStart", m -> null);
        hookFloating("onEnterHomeAnimFinish", m -> null);
        hookFloating("showIcon", m -> null);
    }

    // ---------------------------------------------------------------- icon launches

    /** Another launcher's icon was tapped: open the app with MiuiHome's own animation, from that icon. */
    @Override
    public boolean launch(Intent request, int[] r, int seq) {
        try {
            Application app = currentApplication();
            ComponentName cn = request.getComponent();
            String why = app == null || cn == null ? "nothing to launch" : !proxyMode() ? "not the gesture host"
                    : !upright(app) ? "screen is rotated"
                    : IconSync.imageFor(currentHome(), true) == null ? "no picture of the launcher"
                    : seq < 0 || seq > IconSync.openSeq ? "picture " + seq + " never arrived (have " + IconSync.openSeq + ")"
                    : !Intent.ACTION_MAIN.equals(request.getAction()) || !request.hasCategory(Intent.CATEGORY_LAUNCHER)
                    || request.getData() != null ? "not a plain app launch" : null;
            Intent launch = null;
            Rect rect = null;
            if (why == null) {
                // rebuilt from scratch: MiuiHome starts this itself, so nothing else from the sender rides along
                launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                rect = new Rect(r[0], r[1], r[2], r[3]);
                if (app.getPackageManager().queryIntentActivities(launch, 0).isEmpty()) why = "activity not found";
                else if (rect.isEmpty()) why = "empty icon rect";
            }
            if (why != null) {
                log(Log.INFO, TAG, "icon launch declined: " + why);
                return false;
            }
            homeGesture = false;
            openMode = true;
            openingUntil = SystemClock.uptimeMillis() + 1200;
            lingerUntil = 0;
            standIn = false;
            main.removeCallbacks(standInOver);
            // Its animation draws inside MiuiHome's own window, so that has to come to the front. The app is
            // started at the same moment rather than after: the window swap and the app start run side by side.
            final Intent fLaunch = launch;
            final Rect fRect = rect;
            long began = SystemClock.uptimeMillis();
            Runnable[] go = new Runnable[1];
            go[0] = () -> {
                // A swap the other way may have only just been asked for (the tap came down as the last app
                // finished closing). Two swaps on each other's heels leave the wallpaper black for a frame,
                // so this one waits until that one is on screen.
                long now = SystemClock.uptimeMillis();
                long since = now - swapAppliedAt;
                if (!launcherResumed && now - began < 250 && (swapPendingAt > now - 300 || since < 9)) {
                    main.postDelayed(go[0], swapPendingAt > now - 300 ? 4 : 9 - since);
                    return;
                }
                proceedLaunch(fLaunch, fRect);
            };
            go[0].run();
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "icon launch refused", t);
            return false;
        }
    }

    private void proceedLaunch(Intent launch, Rect rect) {
        try {
            boolean wasFront = launcherResumed;
            View cover = coverView;
            if (!wasFront && cover != null && coverBitmap != null && !coverDone) {
                // The picture of home is still up from the swap that just went the other way. It stays until
                // the opening animation is on screen, so the swap back is not seen either.
                coverHoldUntil = SystemClock.uptimeMillis() + 250;
                cover.animate().cancel();
                cover.setAlpha(1f);
                main.postDelayed(() -> {
                    if (coverView == cover) removeCover(true);
                }, 260);
            }
            if (!wasFront) bringOwnLauncherFront();
            if (runIconLaunch(launch, rect, IconSync.imageFor(currentHome(), true))) return;
            if (wasFront) {
                log(Log.INFO, TAG, "icon launch: MiuiHome's animation refused it, starting it plainly");
                startDirect(launch);
                return;
            }
            // refused while still behind: try once more when it is in front
            pendingLaunch = launch;
            pendingRect = rect;
            main.postDelayed(() -> {
                if (pendingLaunch == launch) { // the Launcher never came up
                    pendingLaunch = null;
                    log(Log.INFO, TAG, "icon launch: MiuiHome's Launcher did not come to the front in time");
                    startDirect(launch);
                }
            }, 700);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "icon launch failed, starting it plainly", t);
            startDirect(launch);
        }
    }

    private static volatile long swapPendingAt, swapAppliedAt;

    private boolean runIconLaunch(Intent launch, Rect rect, Bitmap picture) throws Throwable {
        Application app = currentApplication();
        if (app == null) return false;
        Object icon = floatingTarget(app, rect, picture, launch.getComponent());
        if (icon == null) return false;
        launchingAt = SystemClock.uptimeMillis();
        closingComponent = launch.getComponent();
        settled = false;
        if (coverView != null && coverBitmap == null) removeCover(true); // nothing to wait for on the home screen now
        setHole(rect); // the icon leaves its slot with the window that grows out of it
        Class<?> infoCls = cls("com.miui.home.recents.event.IconClickEventInfo");
        Object info = infoCls.getConstructor(Intent.class, Object.class, View.class).newInstance(launch, new Object(), icon);
        Object event = cls("com.miui.home.recents.event.IconClickEvent").getConstructor(infoCls).newInstance(info);
        Class<?> sm = cls("com.miui.home.recents.anim.StateManager");
        Object companion = sm.getField("Companion").get(null);
        Object state = companion.getClass().getMethod("getInstance").invoke(companion);
        long asked = SystemClock.uptimeMillis();
        boolean sent = (Boolean) state.getClass().getMethod("sendEvent", cls("com.miui.home.common.recents.event.Event"))
                .invoke(state, event);
        if (sent) {
            // MiuiHome can accept the tap and still drop it, when it arrives while the last animation is being
            // put away. Its open animation normally begins within a frame or two: if it has not, ask once
            // more, and if the app is still not in front after that, start it plainly. It must open.
            main.postDelayed(() -> {
                if (openSeenAt >= asked || topPackage().equals(launch.getComponent().getPackageName())) return;
                // MiuiHome is holding the tap back until the previous animation's window has been put away,
                // and that is not going to happen by itself here. Putting it away releases the tap.
                try {
                    // (only the animations already set aside: cancelling everything would also cancel the
                    // opening animation that this very call lets begin)
                    state.getClass().getMethod("cancelOldList", String.class).invoke(state, "icon tapped");
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "could not release a held icon launch", t);
                }
            }, 48);
            main.postDelayed(() -> {
                // on top means it opened. Anything else: MiuiHome dropped the tap after all
                if (topPackage().equals(launch.getComponent().getPackageName())) return;
                // (opened, and the user has already left it again: that is not a lost tap)
                if (openSeenAt >= asked && !ownLauncherOnTop(launcherRef.get())) return;
                log(Log.INFO, TAG, "icon launch: the app is not in front (animation seen: " + (openSeenAt >= asked)
                        + "), starting it plainly");
                startDirect(launch);
            }, 240);
        }
        return sent;
    }

    private void startDirect(Intent launch) {
        try {
            launchingAt = SystemClock.uptimeMillis();
            currentApplication().startActivity(launch);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "direct start failed", t);
            openRealHome();
        }
    }

    // ---------------------------------------------------------------- state

    private static Application currentApplication() {
        try {
            return (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The default home's package, or null when it cannot be told. */
    private static String currentHome() {
        long now = SystemClock.uptimeMillis();
        if (now - homeCacheAt < 1500) return homeCache;
        String home = null;
        try {
            ComponentName cn = (ComponentName) currentDefaultHome.invoke(null);
            home = cn == null ? null : cn.getPackageName();
        } catch (Throwable t) {
            // unknown
        }
        homeCache = home;
        homeCacheAt = now;
        return home;
    }

    @Override
    public String defaultHome() {
        return proxyMode() ? currentHome() : null;
    }

    /** True while another launcher is the default home and MiuiHome only hosts gestures and recents. */
    private static boolean proxyMode() {
        String home = currentHome();
        return home != null && !ownPackage.equals(home) && !isSetupOrResolver(home);
    }

    /** The picture and the icon positions are of an upright screen; anything else falls back to plain. */
    private static boolean upright(Context c) {
        try {
            Display d = c.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            return d != null && d.getRotation() == Surface.ROTATION_0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean inOverview(Activity a) {
        try {
            resolveStates();
            if (overviewState == null) return true;
            return (Boolean) isInState.invoke(a, overviewState)
                    || (Boolean) isInState.invoke(a, overviewWindowState);
        } catch (Throwable t) {
            return true; // when unsure, never pull recents away
        }
    }

    /**
     * A swipe up from the home screen that did not end in recents leaves MiuiHome's Launcher in front. As
     * soon as its desktop has settled back, the real launcher gets the screen again.
     */
    private void endHomeGestureIfRested() {
        Activity a = launcherRef.get();
        if (!homeGesture || touching || a == null) return;
        if (inOverview(a)) {
            homeGesture = false; // it became recents; leaving that has its own ways home
            return;
        }
        View desk = desktopLayer;
        if (!rested()) return; // still settling
        homeGesture = false;
        openRealHome();
    }

    private static volatile long broughtFrontAt;

    /** Puts MiuiHome's Launcher in front, unseen: it shows the picture of what it replaces. */
    private void bringOwnLauncherFront() {
        long now = SystemClock.uptimeMillis();
        if (launcherResumed || now - broughtFrontAt < 500) return;
        broughtFrontAt = now;
        try {
            Application app = currentApplication();
            android.os.Bundle opts = noAnimation(app).toBundle();
            // (Moving the existing task forward instead would be a few milliseconds quicker, but the
            // wallpaper then goes black behind the home screen when it follows another swap closely.)
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ownPackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            app.startActivity(home, opts);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "bringing the Launcher to the front failed", t);
        }
    }

    /** MiuiHome's Launcher activity if it exists, else null. */
    private Object ownLauncher() {
        try {
            if (getLauncher == null) {
                getLauncher = cls("com.miui.home.launcher.Application").getDeclaredMethod("getLauncher");
            }
            return getLauncher.invoke(null);
        } catch (Throwable t) {
            return this; // unknown: assume it is there
        }
    }

    @SuppressWarnings("deprecation")
    private static ComponentName topComponent() {
        try {
            ActivityManager am = (ActivityManager) currentApplication().getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            if (t == null || t.isEmpty() || t.get(0).topActivity == null) return null;
            ComponentName c = t.get(0).baseActivity != null ? t.get(0).baseActivity : t.get(0).topActivity;
            String home = currentHome();
            return ownPackage.equals(c.getPackageName()) || c.getPackageName().equals(home) ? null : c;
        } catch (Throwable e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static String topPackage() {
        try {
            ActivityManager am = (ActivityManager) currentApplication().getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            return t == null || t.isEmpty() || t.get(0).topActivity == null ? "" : t.get(0).topActivity.getPackageName();
        } catch (Throwable e) {
            return "";
        }
    }

    /** True when the real home screen, not an app, is what the finger is swiping up from. */
    @SuppressWarnings("deprecation")
    private static boolean foregroundIsHome() {
        try {
            ActivityManager am = (ActivityManager) currentApplication().getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            if (t == null || t.isEmpty() || t.get(0).topActivity == null) return false;
            String home = currentHome();
            return home != null && proxyMode() && home.equals(t.get(0).topActivity.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    /** True when MiuiHome's Launcher is in front with nothing of its own going on. */
    private static boolean idleInFront(Activity a) {
        long now = SystemClock.uptimeMillis();
        if (now < lingerUntil) return false;
        if (!launcherResumed || a.isDestroyed() || !proxyMode() || touching || animatingNow() || prewarming
                || pendingLaunch != null || now - launchingAt < 2500 || now - recentsAt < 1500) return false;
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        return pm.isInteractive() && !inOverview(a) && ownLauncherOnTop(a);
    }

    /** Going "home" from here only makes sense if MiuiHome's Launcher is what the user is looking at. */
    @SuppressWarnings("deprecation")
    private static boolean ownLauncherOnTop(Activity a) {
        try {
            ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> t = am.getRunningTasks(1);
            return t == null || t.isEmpty() || t.get(0).topActivity == null
                    || ownPackage.equals(t.get(0).topActivity.getPackageName());
        } catch (Throwable e) {
            return true;
        }
    }

    private static final long LINGER_MS = 1500;
    private static volatile long lingerUntil;

    /**
     * Home again, with MiuiHome's Launcher showing the picture of the real one. An icon tapped now opens
     * straight from here, with no swap of launchers first, so the real launcher is given the screen back
     * only after a moment: at once on any touch that is not on an icon, and in any case after the moment.
     */
    private void linger() {
        // Only after a swipe up. An app that closed some other way (back from its first screen, its own
        // exit) has the system bring the real launcher forward by itself, beneath, with its entrance
        // playing: that must be behind the picture at once, not a moment later.
        boolean swiped = SystemClock.uptimeMillis() - gestureDownAt < 3000;
        if (!swiped || coverView == null || coverBitmap != null || !launcherResumed || homePicture() == null) {
            openRealHome();
            return;
        }
        lingerUntil = SystemClock.uptimeMillis() + LINGER_MS;
        main.removeCallbacks(lingerOver);
        main.postDelayed(lingerOver, LINGER_MS);
        main.removeCallbacks(coverTimeout);
        main.postDelayed(coverTimeout, LINGER_MS + 2000);
    }

    private final Runnable lingerOver = () -> {
        if (lingerUntil == 0) return;
        lingerUntil = 0;
        if (standIn || touching || homeGesture || !launcherResumed) return; // something else has it in hand
        if (SystemClock.uptimeMillis() - launchingAt < 2500) return;
        openRealHome();
    };

    /**
     * True once MiuiHome's hidden desktop, which the picture copies, is back at full size and fully there.
     * Its spring creeps over the last hundredth for a good many frames; swapping to the real launcher before
     * it is done shows as the whole home screen jumping a few pixels.
     */
    private static boolean rested() {
        View desk = desktopLayer;
        return desk == null || (desk.getAlpha() >= 0.995f && Math.abs(desk.getScaleX() - 1f) <= 0.0015f);
    }

    /** Runs {@code then} as soon as the desktop has come to rest, or after {@code withinMs} whatever it is doing. */
    private void whenRested(Runnable then, long withinMs) {
        long until = SystemClock.uptimeMillis() + withinMs;
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> {
            if (rested() || SystemClock.uptimeMillis() >= until) then.run();
            else main.postDelayed(poll[0], 8);
        };
        poll[0].run();
    }

    private void leaveIfIdle(Activity a) {
        if (idleInFront(a)) openRealHome();
    }

    // ---------------------------------------------------------------- the picture of home

    private void setHole(Rect r) {
        hole = r == null ? null : new Rect(r);
        Runnable apply = () -> {
            IconSync.Picture p = overlayPicture;
            if (p != null) p.setHole(hole);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run();
        else main.postAtFrontOfQueue(apply);
    }

    private static boolean fits(Bitmap b, View v) {
        return b != null && !b.isRecycled() && v.getWidth() > 0
                && (b.getWidth() < b.getHeight()) == (v.getWidth() < v.getHeight());
    }

    /**
     * While the real launcher is not on screen, MiuiHome's animations would run over bare wallpaper. A
     * picture of the real home screen sits behind them instead, as a resident launcher would.
     *
     * MiuiHome still animates its own, hidden, desktop exactly as on stock: fading and zooming it in as an
     * app closes, out as one opens or recents comes up. The picture copies that, so it moves as the stock
     * home screen does.
     */
    private void installOverlay(Activity a) {
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        if (decor.getTag(SNAP_TAG) != null) return;
        ImageView iv = new ImageView(a);
        IconSync.Picture pic = new IconSync.Picture();
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setImageDrawable(pic);
        iv.setAlpha(0f);
        iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        decor.addView(iv, 0, new ViewGroup.LayoutParams(-1, -1)); // behind MiuiHome's own layers
        decor.setTag(SNAP_TAG, iv);
        overlayView = iv;
        overlayPicture = pic;
        boolean[] shown = {false};
        float[] last = {1f, 1f};
        float[] openRef = {1f, 1f};
        float[] lastOpenAlpha = {1f};
        long[] openFor = {0};
        long[] movedAt = {0};
        decor.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (overlayView != iv) return true;
            long now = SystemClock.uptimeMillis();
            Bitmap img = IconSync.imageFor(currentHome(), openMode);
            // on its way to the front for an icon launch or a swipe up from home counts: its first frame must
            // already hold the picture
            boolean front = launcherResumed || ((openMode || homeGesture) && now - broughtFrontAt < 600)
                    || (openMode && now < openingUntil);
            boolean want = front && proxyMode() && fits(img, iv);
            if (want != shown[0]) {
                shown[0] = want;
                if (want) {
                    pic.set(img, hole);
                } else {
                    openMode = false;
                    hole = null;
                }
            }
            if (coverView != null && coverBitmap == null && inOverview(a)) removeCover(true); // recents takes its own touches
            float alpha = 0f, scale = 1f;
            if (want) {
                View desk = desktopLayer;
                float da = desk == null ? 1f : desk.getAlpha(), ds = desk == null ? 1f : desk.getScaleX();
                if (da != last[0] || ds != last[1]) movedAt[0] = now;
                last[0] = da;
                last[1] = ds;
                // at rest MiuiHome may have left its desktop faded out from the last app it opened
                boolean live = touching || homeGesture || animatingNow() || now - movedAt[0] < 250 || inOverview(a);
                alpha = live ? da : 1f;
                scale = live ? ds : 1f;
                if (openMode) {
                    // The launcher was fully there when the icon was tapped. MiuiHome's hidden desktop may
                    // be anywhere, cut off mid-fade by the tap: start from full and only ever fade out.
                    boolean fresh = openFor[0] != launchingAt; // the first frame drawn for this launch
                    openFor[0] = launchingAt;
                    if (fresh) lastOpenAlpha[0] = 1f;
                    // (and while the real launcher is still on screen beneath, waiting to be swapped out, the
                    // picture stays exactly on top of it: moving sooner shows the two side by side. The swap is
                    // not always reported when the app's own start joins it, hence the limit)
                    boolean swapping = swapAppliedAt >= broughtFrontAt ? now - swapAppliedAt < 17
                            : now - broughtFrontAt < 100;
                    if (fresh || swapping || openSeenAt < launchingAt) {
                        openRef[0] = Math.max(da, 0.05f);
                        openRef[1] = ds;
                        alpha = 1f;
                        scale = 1f;
                    } else {
                        alpha = Math.min(1f, da / openRef[0]);
                        scale = openRef[1] > 0f ? Math.min(1f, ds / openRef[1]) : ds;
                        if (alpha > lastOpenAlpha[0]) alpha = lastOpenAlpha[0];
                    }
                    lastOpenAlpha[0] = alpha;
                } else {
                    lastOpenAlpha[0] = 1f;
                }
                if (homeGesture && !touching && rested()) main.post(this::endHomeGestureIfRested);
                if (desk != null && desk.getWidth() > 0) {
                    iv.setPivotX(desk.getPivotX() * iv.getWidth() / desk.getWidth());
                    iv.setPivotY(desk.getPivotY() * iv.getHeight() / desk.getHeight());
                }
            }
            if (want && alpha > 0f && pictureFor != broughtFrontAt) {
                final long trip = broughtFrontAt;
                pictureFor = trip;
                decor.getViewTreeObserver().registerFrameCommitCallback(() -> {
                    pictureShownFor = trip;
                    List<Runnable> waiting;
                    synchronized (awaitingPicture) {
                        waiting = new ArrayList<>(awaitingPicture);
                        awaitingPicture.clear();
                    }
                    for (Runnable r : waiting) r.run();
                });
            }
            if (coverOver) alpha = 0f;
            if (iv.getAlpha() != alpha) iv.setAlpha(alpha);
            if (iv.getScaleX() != scale) {
                iv.setScaleX(scale);
                iv.setScaleY(scale);
            }
            return true;
        });
    }

    private static volatile long pictureFor, pictureShownFor; // which trip to the front the picture was drawn, and seen, for
    private static final List<Runnable> awaitingPicture = new ArrayList<>();

    /**
     * Runs {@code done} when MiuiHome's Launcher, on its way to the front, has the picture of the real
     * launcher on screen; at once when it is not on its way there or already has, and after a short wait at
     * the latest. The real launcher is kept on screen beneath for that long.
     */
    private void whenPictureShown(Runnable done) {
        boolean[] ran = {false};
        Runnable once = () -> {
            synchronized (ran) {
                if (ran[0]) return;
                ran[0] = true;
            }
            done.run();
        };
        long trip = broughtFrontAt;
        boolean coming = (openMode || homeGesture) && SystemClock.uptimeMillis() - trip < 600;
        if (!coming || pictureShownFor == trip) {
            once.run();
            return;
        }
        synchronized (awaitingPicture) {
            awaitingPicture.add(once);
        }
        main.postDelayed(once, 180);
        main.post(() -> { // a frame has to be drawn for the picture to get there
            View ov = overlayView;
            if (ov != null) ov.invalidate();
        });
    }

    private static Method transitionAlpha;
    static volatile View desktopLayer;

    /**
     * MiuiHome's own desktop must not show; only its recents UI is wanted. It is hidden without touching the
     * alpha and scale MiuiHome animates on it, so those can be read and applied to the picture instead.
     */
    private static void hideDesktop(Activity a) {
        int id = a.getResources().getIdentifier("shortcut_menu_layer", "id", a.getPackageName());
        View v = id == 0 ? null : a.findViewById(id);
        if (v == null || v.getTag(id) != null) return;
        v.setTag(id, Boolean.TRUE);
        desktopLayer = v;
        try {
            transitionAlpha = View.class.getMethod("setTransitionAlpha", float.class);
        } catch (Throwable t) {
            transitionAlpha = null;
        }
        boolean[] hidden = {false};
        v.getViewTreeObserver().addOnPreDrawListener(() -> {
            boolean proxy = proxyMode();
            if (proxy != hidden[0]) {
                hidden[0] = proxy;
                try {
                    if (transitionAlpha != null) transitionAlpha.invoke(v, proxy ? 0f : 1f);
                    else v.setAlpha(proxy ? 0f : 1f);
                } catch (Throwable t) {
                    v.setAlpha(proxy ? 0f : 1f);
                }
            }
            return true;
        });
    }

    /**
     * The real launcher needs a moment to draw itself after it is brought back. A picture of it is put up
     * in a window of its own first, then {@code start} runs beneath it. The picture is lifted as soon as the
     * launcher says it is ready. With no picture to show, {@code start} simply runs.
     */
    private void coverThen(Application app, Runnable start) {
        coverThen(app, start, false);
    }

    /** @param behind the picture goes up although MiuiHome's Launcher is not the one in front */
    private void coverThen(Application app, Runnable start, boolean behind) {
        main.post(() -> {
            boolean[] started = {false};
            Runnable once = () -> {
                if (started[0]) return;
                started[0] = true;
                start.run();
            };
            try {
                Bitmap b = homePicture();
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                if (b == null || !(launcherResumed || behind) || !pm.isInteractive() || !upright(app)) {
                    once.run();
                    return;
                }
                View v = coverView;
                boolean wasUp = v != null && coverBitmap == null; // already on screen, catching touches
                if (v == null || coverBitmap != null) { // none yet, or an old one still fading
                    coverHoldUntil = 0;
                    removeCover(true);
                    v = makeCover(app);
                    if (v == null) {
                        once.run();
                        return;
                    }
                }
                IconSync.Picture pic = new IconSync.Picture();
                pic.set(b, null);
                v.setBackground(pic);
                if (wasUp && launcherResumed) { // both are drawn in this same frame: one appears as the other goes
                    coverOver = true;
                    View ov = overlayView;
                    if (ov != null) ov.invalidate();
                }
                coverBitmap = b;
                coverSwapped = instant == null; // without the instant transition nothing reports the swap
                coverReady = false;
                // go on once the picture is really on screen, or after a short wait if that is never reported
                final View cv = v;
                v.getViewTreeObserver().registerFrameCommitCallback(() -> {
                    // the picture is up: the same picture beneath it, in MiuiHome's Launcher, would show
                    // through its see-through parts and make them twice as strong
                    if (coverView == cv && coverBitmap != null) {
                        coverOver = true;
                        View ov = overlayView;
                        if (ov != null) ov.invalidate();
                    }
                    main.postDelayed(once, 8);
                });
                main.postDelayed(once, 120);
                main.removeCallbacks(coverTimeout);
                main.postDelayed(coverTimeout, 1300);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "handoff cover failed", t);
                once.run();
            }
        });
    }

    /**
     * A see-through cover that only catches touches. It goes up the moment MiuiHome's Launcher is left
     * standing in for the real one with nothing more to animate, so that a touch made before the real
     * launcher is back still reaches it whole: a window swap beneath this one does not cut a gesture off.
     */
    private void catchTouches() {
        Application app = currentApplication();
        Activity a = launcherRef.get();
        String type = lastAnimType;
        if (app == null || a == null || coverView != null || !launcherResumed || !proxyMode() || !upright(app)
                || inOverview(a) || (type != null && type.startsWith("CLOSE_TO_RECENTS"))) return;
        try {
            if (makeCover(app) != null) {
                main.removeCallbacks(coverTimeout);
                main.postDelayed(coverTimeout, 2000);
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "touch cover failed", t);
        }
    }

    private View makeCover(Application app) {
        WindowManager wm = app.getSystemService(WindowManager.class);
        Rect bounds = wm.getMaximumWindowMetrics().getBounds();
        if (bounds.width() > bounds.height()) return null;
        View v = new View(app);
        // while the two launchers swap there is a moment when neither can be touched; the cover takes
        // those touches itself and passes every one on to the launcher, so none is lost
        v.setOnTouchListener((view, ev) -> {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                coverTouched = true;
                if (coverBitmap == null) {
                    if (launcherResumed && IconSync.onIcon(currentHome(), (int) ev.getRawX(), (int) ev.getRawY())) {
                        // It may be a tap on an icon, and then MiuiHome can open the app from right here. The
                        // launcher is handed the touch where it is, and comes to the front only if the touch
                        // turns out to be for something else.
                        standIn = true;
                        if (standInDown != null) standInDown.recycle();
                        standInDown = MotionEvent.obtain(ev);
                        standInX = ev.getRawX();
                        standInY = ev.getRawY();
                        main.removeCallbacks(standInOver);
                        main.postDelayed(standInOver, 140); // longer than a tap, shorter than a long press
                    } else { // the real launcher is wanted now, not when MiuiHome has settled
                        homeGesture = false;
                        openRealHome();
                    }
                }
            } else if (standIn && action == MotionEvent.ACTION_MOVE
                    && Math.hypot(ev.getRawX() - standInX, ev.getRawY() - standInY) > standInSlop(view)) {
                standInOver.run(); // a swipe
            }
            IconSync.sendEvent(ev, standIn);
            if (standIn && action == MotionEvent.ACTION_UP) {
                main.removeCallbacks(standInOver);
                main.postDelayed(standInOver, 120); // the launcher says sooner if it was not an icon
            } else if (standIn && action == MotionEvent.ACTION_CANCEL) {
                standInOver.run();
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                coverTouched = false;
                if (coverDone && coverView == view) removeCover(true);
            }
            return true;
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(bounds.width(), bounds.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("HyperLauncherCover");
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setFitInsetsTypes(0); // the picture is of the whole display; keep it exactly there
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        wm.addView(v, lp);
        coverView = v;
        coverBitmap = null;
        coverTouched = false;
        coverDone = false;
        coverSwapped = false;
        coverReady = false;
        return v;
    }

    private static volatile boolean standIn;
    private static float standInX, standInY;

    private static float standInSlop(View v) {
        return android.view.ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
    }

    /** The touch being passed on is not an icon tap after all: the real launcher takes over. */
    private final Runnable standInOver = () -> {
        if (!standIn) return;
        standIn = false;
        MotionEvent down = standInDown;
        standInDown = null;
        if (down != null) {
            if (coverTouched) {
                // The finger is still down: a press or a swipe. The launcher should see that from its start
                // once it is in front, so what it was shown so far is called off and the touch begins again.
                MotionEvent cancel = MotionEvent.obtain(down);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                IconSync.sendEvent(cancel, true);
                cancel.recycle();
                IconSync.sendEvent(down, false);
            }
            down.recycle();
        }
        homeGesture = false;
        openRealHome();
    };

    private static MotionEvent standInDown;

    @Override
    public void wantsFront() {
        main.post(() -> {
            main.removeCallbacks(standInOver);
            if (standIn) standInOver.run();
            else if (launcherResumed && proxyMode()) openRealHome();
        });
    }

    /** The picture of plain home, if it is of the launcher that is the default right now. */
    private static Bitmap homePicture() {
        return IconSync.imageFor(currentHome(), false);
    }

    private final Runnable coverTimeout = () -> removeCover(false);

    private static volatile long coverHoldUntil;
    private static volatile boolean coverOver; // the picture cover is showing, over MiuiHome's Launcher

    private void removeCover(boolean instant) {
        View v = coverView;
        if (v == null || SystemClock.uptimeMillis() < coverHoldUntil) return;
        main.removeCallbacks(coverTimeout);
        if (coverTouched) { // taking it away now would cut the gesture off: just stop showing it
            coverDone = true;
            v.setAlpha(0f);
            return;
        }
        Runnable finish = () -> {
            if (coverView != v) return;
            if (coverTouched) { // a finger came down while it faded; it goes when that finger lifts
                coverDone = true;
                return;
            }
            coverView = null;
            coverBitmap = null;
            coverOver = false;
            try {
                v.getContext().getSystemService(WindowManager.class).removeViewImmediate(v);
            } catch (Throwable t) {
                // already gone
            }
        };
        if (instant) finish.run();
        else v.animate().alpha(0f).setDuration(60).withEndAction(finish).start();
    }

    @Override
    public boolean showing(Bitmap picture) {
        IconSync.Picture p = overlayPicture;
        return (p != null && p.bitmap() == picture) || coverBitmap == picture || floatTargetSource == picture;
    }

    @Override
    public void pictureArrived() {
        if (ownLauncher() != null) return;
        main.removeCallbacks(prewarm);
        main.postDelayed(prewarm, 400);
    }

    @Override
    public void launcherReady(boolean touched) {
        main.post(() -> {
            if (touched) { // a touch reached the launcher, so it is on screen: get out of the way
                main.postDelayed(() -> removeCover(true), 34);
                return;
            }
            coverReady = true;
            liftCover();
            main.postDelayed(() -> { // the swap was never reported; do not sit on a ready launcher
                if (coverReady && coverView != null) removeCover(false);
            }, 350);
        });
    }

    private void swapApplied() {
        main.post(() -> {
            coverSwapped = true;
            liftCover();
        });
    }

    /** Lifts the cover once the launcher is both on screen and done drawing; a frame early shows through. */
    private void liftCover() {
        if (coverView == null || coverBitmap == null || !coverSwapped || !coverReady) return;
        View v = coverView;
        // (the launcher shows itself the moment it says it is ready; a frame of both is better than one of neither)
        main.postDelayed(() -> {
            if (coverView == v) removeCover(true);
        }, 2);
    }

    // ---------------------------------------------------------------- getting home

    /**
     * Backstop for any way MiuiHome's Launcher can end up in front with nothing to show that the direct
     * routes above missed. Runs only while it is in front.
     */
    private void startWatchdog(Activity a) {
        View decor = a.getWindow().getDecorView();
        Runnable old = (Runnable) decor.getTag(WATCHDOG_TAG);
        if (old != null) decor.removeCallbacks(old);
        int[] strikes = {0};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (a.isDestroyed() || !launcherResumed || launcherRef.get() != a) return;
                if (idleInFront(a)) {
                    if (++strikes[0] >= WATCHDOG_STRIKES) {
                        strikes[0] = 0;
                        openRealHome();
                    }
                } else {
                    strikes[0] = 0;
                }
                decor.postDelayed(this, WATCHDOG_MS);
            }
        };
        decor.setTag(WATCHDOG_TAG, tick);
        decor.postDelayed(tick, WATCHDOG_MS);
    }

    private static void stopWatchdog(Activity a) {
        View decor = a.getWindow().getDecorView();
        Runnable old = (Runnable) decor.getTag(WATCHDOG_TAG);
        if (old != null) decor.removeCallbacks(old);
    }

    /**
     * The first gesture after MiuiHome starts has to create the Launcher mid-swipe and fails. Create it
     * ahead of time, while the screen is off or locked so nothing flashes, and put the real home back on top.
     */
    private synchronized void schedulePrewarm() {
        Application app = currentApplication();
        if (app == null || prewarmArmed) return;
        prewarmArmed = true;
        main.postDelayed(this::prewarmNow, 900);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                main.postDelayed(Module.this::prewarmNow, 1500);
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
    }

    private final Runnable prewarm = this::prewarmNow;

    private void prewarmNow() {
        try {
            Application app = currentApplication();
            if (app == null || !proxyMode()) return;
            if (ownLauncher() != null) { // already alive
                // ...but a Launcher is slow the first time it comes back from being put away as well, which
                // would be the first icon tapped. That return is got out of the way here too.
                if (warmRuns == 1 && !launcherResumed && pm().isInteractive()) {
                    Intent again = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ownPackage)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    warmBehindPicture(app, again);
                }
                return;
            }
            warmRuns = 0;
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            KeyguardManager km = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
            boolean unseen = !pm.isInteractive() || (km != null && km.isKeyguardLocked());
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(ownPackage)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (unseen) {
                prewarming = true;
                app.startActivity(home, noAnimation(app).toBundle());
                main.postDelayed(() -> prewarming = false, 3000);
                return;
            }
            // in use: only while the real home screen is showing and its picture is at hand. MiuiHome's
            // Launcher then comes up showing that same picture and hands straight back, with nothing to see.
            warmBehindPicture(app, home);
        } catch (Throwable t) {
            prewarming = false;
            log(Log.WARN, TAG, "prewarm failed", t);
        }
    }

    private static volatile int warmRuns; // how often the Launcher now alive has been brought up ahead of use

    private static PowerManager pm() {
        return (PowerManager) currentApplication().getSystemService(Context.POWER_SERVICE);
    }

    /**
     * Brings MiuiHome's Launcher up and puts it away again while the real home screen is showing, behind a
     * picture of that home screen, so there is nothing to see.
     */
    private void warmBehindPicture(Application app, Intent home) {
        if (touching || animatingNow() || homePicture() == null || !upright(app) || !foregroundIsHome()
                || coverView != null || lingerUntil != 0 || SystemClock.uptimeMillis() - launchingAt < 3000) {
            if (warmRuns == 1) main.postDelayed(prewarm, 4000); // not now: later
            return;
        }
        android.os.Bundle opts = noAnimation(app).toBundle();
        coverThen(app, () -> {
            try {
                if (touching || !foregroundIsHome()) return;
                prewarming = true;
                app.startActivity(home, opts);
                main.postDelayed(() -> prewarming = false, 3000);
            } catch (Throwable t) {
                prewarming = false;
                log(Log.WARN, TAG, "prewarm failed", t);
            }
        }, true);
    }

    private static Object instant;
    private static Method withRemoteTransition;
    private static boolean instantLooked;

    /**
     * Start options for swapping one home screen for the other unseen: the swap carries its own
     * transition, which only jumps to the end. Without it the system slides the outgoing one for a frame.
     */
    private static ActivityOptions noAnimation(Context c) {
        ActivityOptions o = plainNoAnimation(c);
        try { // a starting window would flash black, or show bare wallpaper, until the activity has drawn
            ActivityOptions.class.getMethod("setDisableStartingWindow", boolean.class).invoke(o, true);
        } catch (Throwable t) {
            // not on this build
        }
        return o;
    }

    private static ActivityOptions plainNoAnimation(Context c) {
        try {
            if (!instantLooked) {
                instantLooked = true;
                Object t = new android.window.RemoteTransition(new InstantTransition(), "HyperLauncherInstant");
                InstantTransition.whenShown = done -> {
                    Module m = self;
                    if (m == null) done.run();
                    else m.whenPictureShown(done);
                };
                InstantTransition.onApplied = () -> {
                    swapPendingAt = 0;
                    swapAppliedAt = SystemClock.uptimeMillis();
                    Module m = self;
                    if (m != null) m.swapApplied();
                };
                withRemoteTransition = ActivityOptions.class.getMethod("makeRemoteTransition", t.getClass());
                instant = t;
            }
            if (instant != null) {
                swapPendingAt = SystemClock.uptimeMillis();
                return (ActivityOptions) withRemoteTransition.invoke(null, instant);
            }
        } catch (Throwable t) {
            instant = null;
            Log.w(TAG, "no instant transition on this build", t);
        }
        return ActivityOptions.makeCustomAnimation(c, 0, 0);
    }

    /** The real launcher, started this instant with nothing put over the swap. */
    private void startRealHomeNow() {
        try {
            Application app = currentApplication();
            homeCacheAt = 0;
            ComponentName home = (ComponentName) currentDefaultHome.invoke(null);
            if (app == null || home == null || ownPackage.equals(home.getPackageName())
                    || isSetupOrResolver(home.getPackageName())) return;
            synchronized (Module.class) {
                lastHomeStart = SystemClock.uptimeMillis();
            }
            lingerUntil = 0;
            removeCover(true);
            app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .putExtra(IconSync.HANDOFF, true).setComponent(home)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION), ActivityOptions.makeCustomAnimation(app, 0, 0).toBundle());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "starting the real home failed", t);
        }
    }

    private void openRealHome() {
        if (standIn) return; // a touch is being passed on from here; it decides when
        lingerUntil = 0;
        main.removeCallbacks(lingerOver);
        synchronized (Module.class) {
            long now = SystemClock.uptimeMillis();
            if (now - lastHomeStart < DEDUPE_MS) return;
            lastHomeStart = now;
        }
        try {
            Application app = currentApplication();
            homeCacheAt = 0;
            ComponentName home = (ComponentName) currentDefaultHome.invoke(null);
            if (app == null || home == null || ownPackage.equals(home.getPackageName())
                    || isSetupOrResolver(home.getPackageName())) return;
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .putExtra(IconSync.HANDOFF, true)
                    .setComponent(home)
                    // MiuiHome's animation already ran; a second activity animation would slide in on top of it
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ComponentName closing = closingComponent;
            if (closing != null && SystemClock.uptimeMillis() - lastClosingAt < 6000) {
                i.putExtra("gesture_nav_contract_v1", IconSync.contract(closing, home.getPackageName()));
            }
            android.os.Bundle opts = noAnimation(app).toBundle();
            // the picture of home goes up first, so nothing of the swap beneath it is seen
            coverThen(app, () -> {
                try {
                    i.putExtra(IconSync.COVERED, coverView != null && coverBitmap != null);
                    app.startActivity(i, opts);
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "starting the real home failed", t);
                }
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "openRealHome failed", t);
        }
    }

    // ---------------------------------------------------------------- gesture setting

    /**
     * Changing the default launcher makes the system turn gesture navigation off. What the user wanted and
     * which launcher was home are kept in system settings, so a MiuiHome restart around the change (or a
     * reset that lands while it is not running) is still put right.
     */
    private synchronized void watchGestureSetting() {
        Application app = currentApplication();
        if (app == null) return;
        ContentResolver cr = app.getContentResolver();
        reconcileGestures(cr);
        if (watching) return;
        watching = true;
        cr.registerContentObserver(Settings.Global.getUriFor(FSG), false, new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                if (Settings.Global.getInt(cr, FSG, 0) == 1) {
                    Settings.Global.putInt(cr, WANTS, 1);
                    return;
                }
                // the system's reset can land just before or just after MiuiHome notices the new default home
                long off = SystemClock.uptimeMillis();
                main.postDelayed(() -> {
                    homeCacheAt = 0;
                    if (Settings.Global.getInt(cr, FSG, 0) == 1) return;
                    boolean homeJustChanged = Math.abs(homeChangedAt - off) < 6000;
                    if (homeJustChanged && Settings.Global.getInt(cr, WANTS, 0) == 1) {
                        log(Log.INFO, TAG, "default home just changed, restoring gesture navigation");
                        Settings.Global.putInt(cr, FSG, 1);
                    } else if (!reconcileGestures(cr) && Settings.Global.getInt(cr, FSG, 0) == 0) {
                        Settings.Global.putInt(cr, WANTS, 0); // switched off on purpose
                    }
                }, 2500);
            }
        });
    }

    /** Puts gesture navigation back if it was only lost because the default launcher changed. */
    private boolean reconcileGestures(ContentResolver cr) {
        homeCacheAt = 0;
        String home = currentHome();
        if (home == null || isSetupOrResolver(home)) return false;
        String last = Settings.Global.getString(cr, LAST_HOME);
        boolean fsg = Settings.Global.getInt(cr, FSG, 0) == 1;
        boolean restored = false;
        if (fsg) {
            Settings.Global.putInt(cr, WANTS, 1);
        } else if (Settings.Global.getInt(cr, WANTS, 0) == 1 && !home.equals(last)) {
            log(Log.INFO, TAG, "default home changed to " + home + ", restoring gesture navigation");
            Settings.Global.putInt(cr, FSG, 1);
            restored = true;
        }
        Settings.Global.putString(cr, LAST_HOME, home);
        return restored;
    }
}
