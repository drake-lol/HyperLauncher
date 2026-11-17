package lol.drake.hyperlauncher;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * How the setup screen and the hooked processes talk. The setup screen sends an ordered broadcast to one
 * package; if the module is running in there, it answers with what it knows, and carries out the one or two
 * things the setup screen may ask of it. Nobody but the module's own app is answered.
 *
 * This class is loaded in three places: the module's app, MiuiHome and the third-party launcher. It must not
 * touch anything of the Xposed API.
 */
final class Status {
    static final String VERSION = "1.1";
    static final String MODULE = "lol.drake.hyperlauncher";
    static final String ACTION = MODULE + ".STATUS";

    static final String CMD = "cmd";
    static final String CMD_RESTART = "restart";
    static final String CMD_GESTURES = "gestures";

    /** What a hooked process adds to its answer, and does when asked. */
    interface Source {
        void describe(Bundle into);

        /** @return true when the command was understood and carried out */
        boolean run(String command);
    }

    private static boolean serving;

    /** Starts answering as soon as this process has an Application to register with. */
    static synchronized void serveWhenReady(String role, Source source) {
        if (serving) return;
        serving = true;
        Handler main = new Handler(Looper.getMainLooper());
        int[] tries = {0};
        Runnable[] attempt = new Runnable[1];
        attempt[0] = () -> {
            Application app = currentApplication();
            if (app == null) {
                if (tries[0]++ < 100) main.postDelayed(attempt[0], 300);
                return;
            }
            try {
                app.registerReceiver(new Answer(role, source), new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                Log.w("HyperLauncher", "the setup screen cannot be answered from here", t);
            }
        };
        main.post(attempt[0]);
    }

    private static Application currentApplication() {
        try {
            return (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Answer extends BroadcastReceiver {
        private final String role;
        private final Source source;

        Answer(String role, Source source) {
            this.role = role;
            this.source = source;
        }

        @Override
        public void onReceive(Context c, Intent i) {
            // the sender has to have shown who it is, and be the module's own app
            if (!isOrderedBroadcast() || !MODULE.equals(getSentFromPackage())) return;
            Bundle b = new Bundle();
            b.putString("version", VERSION);
            b.putString("role", role);
            b.putInt("pid", android.os.Process.myPid());
            try {
                source.describe(b);
            } catch (Throwable t) {
                b.putString("error", String.valueOf(t));
            }
            String cmd = i.getStringExtra(CMD);
            if (CMD_RESTART.equals(cmd)) {
                b.putBoolean("done", true);
                // answer first, then go: the system starts a launcher again by itself
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(0);
                }, 200);
            } else if (cmd != null) {
                boolean done = false;
                try {
                    done = source.run(cmd);
                } catch (Throwable t) {
                    b.putString("error", String.valueOf(t));
                }
                b.putBoolean("done", done);
            }
            setResultCode(1);
            setResultExtras(b);
        }
    }

    private Status() {
    }
}
