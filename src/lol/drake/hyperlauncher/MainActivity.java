package lol.drake.hyperlauncher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The setup screen: says whether everything the module needs is in place, and puts right what it can.
 * Built in code, on the system's own colours, so the module needs no libraries.
 */
public final class MainActivity extends Activity {
    private static final String SYSTEM_LAUNCHER = "com.miui.home";
    private static final String GESTURES = "force_fsg_nav_bar";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Palette c;
    private float dp;

    private FrameLayout hero;
    private Cookie cookie;
    private TextView headline, supporting;
    private LinearLayout rows, actions;

    // what the last check found
    private String home, homeLabel;
    private Bundle system, launcher;
    private boolean checking;
    private int round;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        c = new Palette(this);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(c.surface);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        LinearLayout page = column();
        page.setPadding(px(20), px(12), px(20), px(28));
        scroll.addView(page, new ViewGroup.LayoutParams(-1, -2));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            page.setPadding(px(20) + i.left, px(12) + i.top, px(20) + i.right, px(28) + i.bottom);
            return insets;
        });

        TextView name = text("HyperLauncher", 15, c.onSurfaceVariant, true);
        name.setLetterSpacing(0.02f);
        page.addView(name, margins(-2, -2, 6, 4, 0, 14));

        hero = new FrameLayout(this);
        hero.setBackground(shape(c.primaryContainer, 44));
        LinearLayout heroColumn = column();
        heroColumn.setPadding(px(26), px(26), px(26), px(28));
        cookie = new Cookie(this);
        heroColumn.addView(cookie, new LinearLayout.LayoutParams(px(104), px(104)));
        headline = text("Checking", 40, c.onPrimaryContainer, true);
        headline.setLineSpacing(0, 0.95f);
        headline.setLetterSpacing(-0.02f);
        heroColumn.addView(headline, margins(-1, -2, 0, 22, 0, 0));
        supporting = text("", 16, c.onPrimaryContainer, false);
        supporting.setAlpha(0.86f);
        supporting.setLineSpacing(px(2), 1f);
        heroColumn.addView(supporting, margins(-1, -2, 0, 8, 0, 0));
        hero.addView(heroColumn);
        page.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        page.addView(label("Status"), margins(-2, -2, 6, 26, 0, 10));
        rows = column();
        page.addView(rows, new LinearLayout.LayoutParams(-1, -2));

        page.addView(label("Actions"), margins(-2, -2, 6, 26, 0, 10));
        actions = column();
        page.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        TextView foot = text("Needs root and LSPosed. Enable the module there and tick System launcher and your "
                + "launcher in its scope; this screen checks the rest.", 13, c.onSurfaceVariant, false);
        foot.setLineSpacing(px(2), 1f);
        page.addView(foot, margins(-1, -2, 6, 22, 6, 0));

        setContentView(scroll);
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null && !c.dark) {
            bars.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        check();
    }

    // ---------------------------------------------------------------- checking

    private void check() {
        final int mine = ++round;
        checking = true;
        cookie.spin(true);
        ResolveInfo ri = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        home = ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
        homeLabel = ri == null ? null : String.valueOf(ri.loadLabel(getPackageManager()));
        if (home != null && (home.equals("android") || home.contains("resolver"))) home = null; // no default chosen
        ask(SYSTEM_LAUNCHER, null, sys -> {
            if (mine != round) return;
            system = sys;
            if (home == null || home.equals(SYSTEM_LAUNCHER)) {
                launcher = null;
                done(mine);
            } else {
                ask(home, null, la -> {
                    if (mine != round) return;
                    launcher = la;
                    done(mine);
                });
            }
        });
        if (rows.getChildCount() == 0) render();
    }

    private void done(int mine) {
        // a moment of the turning shape, so a check that takes no time still reads as having happened
        main.postDelayed(() -> {
            if (mine != round) return;
            checking = false;
            cookie.spin(false);
            render();
        }, 350);
    }

    private interface Reply {
        void got(Bundle answer);
    }

    /** Asks the module inside {@code pkg}; the answer is null when it is not running there. */
    private void ask(String pkg, String command, Reply reply) {
        Intent i = new Intent(Status.ACTION).setPackage(pkg);
        if (command != null) i.putExtra(Status.CMD, command);
        boolean[] answered = {false};
        Runnable none = () -> {
            if (answered[0]) return;
            answered[0] = true;
            reply.got(null);
        };
        try {
            Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
            sendOrderedBroadcast(i, null, options, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (answered[0]) return;
                    answered[0] = true;
                    reply.got(getResultCode() == 1 ? getResultExtras(true) : null);
                }
            }, main, 0, null, null);
            main.postDelayed(none, 2500);
        } catch (Throwable t) {
            none.run();
        }
    }

    // ---------------------------------------------------------------- doing

    private void restart(String pkg, String what) {
        toast("Restarting " + what);
        ask(pkg, Status.CMD_RESTART, answer -> {
            if (answer != null) {
                main.postDelayed(this::check, 3000);
                return;
            }
            // the module is not in there to do it: root can
            root("am force-stop " + pkg, ok -> {
                if (ok) {
                    main.postDelayed(this::check, 3000);
                } else {
                    toast("No root access. Force stop it from its App info screen.");
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                    } catch (Throwable t) {
                        // nothing more to offer
                    }
                }
            });
        });
    }

    private void turnGesturesOn() {
        ask(SYSTEM_LAUNCHER, Status.CMD_GESTURES, answer -> {
            if (answer != null && answer.getBoolean("done")) {
                main.postDelayed(this::check, 600);
                return;
            }
            root("settings put global " + GESTURES + " 1", ok -> {
                if (!ok) toast("Could not turn gestures on: the module is not running and there is no root access.");
                main.postDelayed(this::check, 600);
            });
        });
    }

    private interface Outcome {
        void was(boolean ok);
    }

    private void root(String command, Outcome outcome) {
        new Thread(() -> {
            boolean ok;
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
                ok = p.waitFor() == 0;
            } catch (Throwable t) {
                ok = false;
            }
            final boolean result = ok;
            main.post(() -> outcome.was(result));
        }, "root").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- showing

    private static final int OK = 0, BAD = 1, UNKNOWN = 2;

    private static final class Item {
        final int state;
        final String title, detail, action;
        final Runnable run;

        Item(int state, String title, String detail, String action, Runnable run) {
            this.state = state;
            this.title = title;
            this.detail = detail;
            this.action = action;
            this.run = run;
        }
    }

    private List<Item> items() {
        List<Item> out = new ArrayList<>();
        String their = homeLabel == null ? "your launcher" : homeLabel;
        boolean thirdParty = home != null && !home.equals(SYSTEM_LAUNCHER);

        if (system == null) {
            out.add(new Item(BAD, "System launcher", "The module is not running in it. Enable HyperLauncher in "
                    + "LSPosed with System launcher ticked, then restart it.", "Restart",
                    () -> restart(SYSTEM_LAUNCHER, "the system launcher")));
        } else if (!Status.VERSION.equals(system.getString("version"))) {
            out.add(new Item(BAD, "System launcher", "Still running version " + system.getString("version")
                    + ". Restart it to load " + Status.VERSION + ".", "Restart",
                    () -> restart(SYSTEM_LAUNCHER, "the system launcher")));
        } else {
            String hooks = system.getString("hooks", "");
            String[] n = hooks.split("/");
            boolean all = n.length == 2 && n[0].equals(n[1]);
            out.add(new Item(all ? OK : BAD, "System launcher", all ? "Module running, all " + n[0] + " hook groups in place."
                    : "Module running, but only " + hooks + " hook groups took. This HyperOS build may not be supported.",
                    null, null));
        }

        if (!thirdParty) {
            out.add(new Item(BAD, "Default launcher", home == null ? "No default launcher is set."
                    : "The system launcher is the default. Choose the launcher you want to use.", "Choose", () -> {
                try {
                    startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
                } catch (Throwable t) {
                    toast("Open Settings and set a default launcher there.");
                }
            }));
        } else {
            out.add(new Item(OK, "Default launcher", their + " is the home screen.", null, null));
            if (launcher == null) {
                out.add(new Item(BAD, "Module in " + their, "Not running there. Tick " + their + " in the module's "
                        + "scope in LSPosed, then restart it and open the home screen once.", "Restart",
                        () -> restart(home, their)));
            } else if (!Status.VERSION.equals(launcher.getString("version"))) {
                out.add(new Item(BAD, "Module in " + their, "Still running version " + launcher.getString("version")
                        + ". Restart it to load " + Status.VERSION + ".", "Restart", () -> restart(home, their)));
            } else {
                out.add(new Item(OK, "Module in " + their, "Running. Icon taps and positions are shared.", null, null));
            }
        }

        boolean gestures = Settings.Global.getInt(getContentResolver(), GESTURES, 0) == 1;
        out.add(gestures ? new Item(OK, "Gesture navigation", "Full-screen gestures are on.", null, null)
                : new Item(BAD, "Gesture navigation", "Turned off. The system does this when the default launcher "
                + "changes.", "Turn on", this::turnGesturesOn));

        if (system != null && thirdParty && launcher != null) {
            boolean linked = system.getBoolean("linked");
            out.add(new Item(linked ? OK : UNKNOWN, "Home screen link", linked
                    ? "The system launcher has a picture of " + their + " for its animations."
                    : "No picture of " + their + " yet. Open its home screen once and come back.", null, null));
        }
        return out;
    }

    private void render() {
        List<Item> items = items();
        int bad = 0;
        for (Item i : items) if (i.state == BAD) bad++;
        boolean first = rows.getChildCount() == 0;

        int container = checking && first ? c.surfaceHigh : bad == 0 ? c.primaryContainer : c.errorContainer;
        int on = checking && first ? c.onSurface : bad == 0 ? c.onPrimaryContainer : c.onErrorContainer;
        ((GradientDrawable) hero.getBackground()).setColor(container);
        headline.setTextColor(on);
        supporting.setTextColor(on);
        if (checking && first) {
            headline.setText("Checking");
            supporting.setText("Asking the system launcher and your launcher.");
            cookie.set(c.onSurfaceVariant, c.surfaceHigh, Cookie.NONE);
        } else if (bad == 0) {
            headline.setText("All set");
            supporting.setText("Gestures, recents and the stock animations are working with "
                    + (homeLabel == null ? "your launcher" : homeLabel) + ".");
            cookie.set(c.primary, c.onPrimary, Cookie.CHECK);
        } else {
            headline.setText(bad == 1 ? "One thing\nto fix" : bad + " things\nto fix");
            supporting.setText("Work down the list below. Each item says what it needs.");
            cookie.set(c.error, c.onError, Cookie.BANG);
        }
        if (!first || !checking) cookie.pop();

        rows.removeAllViews();
        for (int k = 0; k < items.size(); k++) rows.addView(row(items.get(k), k == 0, k == items.size() - 1), margins(-1, -2, 0, k == 0 ? 0 : 3, 0, 0));

        actions.removeAllViews();
        actions.addView(button("Restart system launcher", c.primary, c.onPrimary,
                () -> restart(SYSTEM_LAUNCHER, "the system launcher")), margins(-1, 64, 0, 0, 0, 0));
        if (home != null && !home.equals(SYSTEM_LAUNCHER)) {
            actions.addView(button("Restart " + homeLabel, c.secondaryContainer, c.onSecondaryContainer,
                    () -> restart(home, homeLabel)), margins(-1, 64, 0, 10, 0, 0));
        }
        actions.addView(button("Check again", c.surfaceHigh, c.onSurface, this::check), margins(-1, 64, 0, 10, 0, 0));
    }

    private View row(Item item, boolean top, boolean bottom) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(16), px(16), px(16), px(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c.surfaceContainer);
        float big = px(28), small = px(6);
        bg.setCornerRadii(new float[]{top ? big : small, top ? big : small, top ? big : small, top ? big : small,
                bottom ? big : small, bottom ? big : small, bottom ? big : small, bottom ? big : small});
        row.setBackground(bg);

        Cookie mark = new Cookie(this);
        mark.round = true;
        if (item.state == OK) mark.set(c.primaryContainer, c.onPrimaryContainer, Cookie.CHECK);
        else if (item.state == BAD) mark.set(c.errorContainer, c.onErrorContainer, Cookie.BANG);
        else mark.set(c.surfaceHigh, c.onSurfaceVariant, Cookie.DOTS);
        row.addView(mark, new LinearLayout.LayoutParams(px(44), px(44)));

        LinearLayout words = column();
        words.addView(text(item.title, 17, c.onSurface, true));
        TextView detail = text(item.detail, 14, c.onSurfaceVariant, false);
        detail.setLineSpacing(px(2), 1f);
        words.addView(detail, margins(-1, -2, 0, 2, 0, 0));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(0, -2, 1f);
        wl.leftMargin = px(14);
        row.addView(words, wl);

        if (item.action != null) {
            View b = button(item.action, c.primary, c.onPrimary, item.run);
            ((TextView) b).setTextSize(14);
            b.setPadding(px(18), 0, px(18), 0);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, px(44));
            bl.leftMargin = px(12);
            row.addView(b, bl);
        }
        return row;
    }

    /** A pill that squeezes its corners and size under the finger and springs back. */
    private View button(String label, int fill, int ink, Runnable run) {
        TextView b = text(label, 17, ink, true);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(px(100));
        b.setBackground(bg);
        b.setClickable(true);
        b.setOnTouchListener((v, ev) -> {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                morph(bg, v.getHeight() / 2f, px(16));
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120).start();
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                morph(bg, px(16), v.getHeight() / 2f);
                v.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new OvershootInterpolator(3f)).start();
                if (a == MotionEvent.ACTION_UP && ev.getX() >= 0 && ev.getX() <= v.getWidth()
                        && ev.getY() >= 0 && ev.getY() <= v.getHeight()) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
                    if (run != null) run.run();
                }
            }
            return true;
        });
        return b;
    }

    private static void morph(GradientDrawable d, float from, float to) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(220);
        a.addUpdateListener(x -> d.setCornerRadius((Float) x.getAnimatedValue()));
        a.start();
    }

    // ---------------------------------------------------------------- pieces

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean strong) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(strong ? "sans-serif-medium" : "sans-serif", strong && sp >= 30 ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14, c.primary, true);
        t.setLetterSpacing(0.03f);
        return t;
    }

    private GradientDrawable shape(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : px(w), h < 0 ? h : px(h));
        p.setMargins(px(l), px(t), px(r), px(b));
        return p;
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    /** The scalloped shape that carries the state: a tick, a mark, or nothing while it turns. */
    private static final class Cookie extends View {
        static final int NONE = 0, CHECK = 1, BANG = 2, DOTS = 3;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int glyph;
        boolean round;
        private ValueAnimator turning;

        Cookie(Context c) {
            super(c);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
        }

        void set(int fillColor, int inkColor, int glyph) {
            fill.setColor(fillColor);
            ink.setColor(inkColor);
            this.glyph = glyph;
            invalidate();
        }

        void spin(boolean on) {
            if (on == (turning != null)) return;
            if (!on) {
                turning.cancel();
                turning = null;
                animate().rotation(Math.round(getRotation() / 30f) * 30f).setDuration(300).start();
                return;
            }
            turning = ValueAnimator.ofFloat(0f, 360f);
            turning.setDuration(2400);
            turning.setRepeatCount(ValueAnimator.INFINITE);
            turning.setInterpolator(new android.view.animation.LinearInterpolator());
            float from = getRotation();
            turning.addUpdateListener(a -> setRotation(from + (Float) a.getAnimatedValue()));
            turning.start();
        }

        void pop() {
            setScaleX(0.7f);
            setScaleY(0.7f);
            animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(new OvershootInterpolator(2.6f)).start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, r = Math.min(w, h) / 2f;
            if (round) {
                canvas.drawCircle(cx, cy, r, fill);
            } else {
                path.reset();
                int lobes = 12, steps = 180;
                for (int i = 0; i <= steps; i++) {
                    double a = 2 * Math.PI * i / steps;
                    double rr = r * (0.93 + 0.07 * Math.cos(lobes * a));
                    float x = cx + (float) (rr * Math.cos(a)), y = cy + (float) (rr * Math.sin(a));
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                canvas.drawPath(path, fill);
            }
            // the mark stays upright while the shape turns
            canvas.save();
            canvas.rotate(-getRotation(), cx, cy);
            ink.setStrokeWidth(r * 0.17f);
            if (glyph == CHECK) {
                path.reset();
                path.moveTo(cx - r * 0.36f, cy + r * 0.02f);
                path.lineTo(cx - r * 0.10f, cy + r * 0.28f);
                path.lineTo(cx + r * 0.38f, cy - r * 0.26f);
                canvas.drawPath(path, ink);
            } else if (glyph == BANG) {
                canvas.drawLine(cx, cy - r * 0.40f, cx, cy + r * 0.08f, ink);
                canvas.drawPoint(cx, cy + r * 0.40f, ink);
            } else if (glyph == DOTS) {
                for (int i = -1; i <= 1; i++) canvas.drawPoint(cx + i * r * 0.34f, cy, ink);
            }
            canvas.restore();
        }
    }

    /** Material colour roles, taken from the system's own palette so the screen follows the wallpaper. */
    private static final class Palette {
        final boolean dark;
        final int primary, onPrimary, primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer,
                surface, surfaceContainer, surfaceHigh, onSurface, onSurfaceVariant,
                error, onError, errorContainer, onErrorContainer;

        Palette(Context c) {
            dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            primary = c.getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            onPrimary = c.getColor(dark ? android.R.color.system_accent1_800 : android.R.color.system_accent1_0);
            primaryContainer = c.getColor(dark ? android.R.color.system_accent1_700 : android.R.color.system_accent1_100);
            onPrimaryContainer = c.getColor(dark ? android.R.color.system_accent1_100 : android.R.color.system_accent1_900);
            secondaryContainer = c.getColor(dark ? android.R.color.system_accent2_700 : android.R.color.system_accent2_100);
            onSecondaryContainer = c.getColor(dark ? android.R.color.system_accent2_100 : android.R.color.system_accent2_900);
            surface = c.getColor(dark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_10);
            surfaceContainer = c.getColor(dark ? android.R.color.system_neutral1_800 : android.R.color.system_neutral1_50);
            surfaceHigh = c.getColor(dark ? android.R.color.system_neutral1_700 : android.R.color.system_neutral1_100);
            onSurface = c.getColor(dark ? android.R.color.system_neutral1_100 : android.R.color.system_neutral1_900);
            onSurfaceVariant = c.getColor(dark ? android.R.color.system_neutral2_200 : android.R.color.system_neutral2_700);
            error = dark ? 0xFFF2B8B5 : 0xFFB3261E;
            onError = dark ? 0xFF601410 : 0xFFFFFFFF;
            errorContainer = dark ? 0xFF8C1D18 : 0xFFF9DEDC;
            onErrorContainer = dark ? 0xFFF9DEDC : 0xFF410E0B;
        }
    }
}
