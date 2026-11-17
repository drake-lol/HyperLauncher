package lol.drake.hyperlauncher;

import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionInfo;
import android.window.WindowAnimationState;

import java.lang.reflect.Method;
import java.util.List;

/**
 * A window transition that goes straight to its end state.
 *
 * Swapping between MiuiHome's Launcher and the real one must not be seen: both show the same picture.
 * The system's own handler still slides the outgoing home screen for a frame even when asked for no
 * animation, so these swaps bring their own transition, and this one does nothing but finish.
 */
final class InstantTransition extends IRemoteTransition.Stub {
    private static final int OPEN = 1, CLOSE = 2, TO_FRONT = 3, TO_BACK = 4;

    private static final int FLAG_IS_WALLPAPER = 1 << 1;

    private static Method getChanges, getMode, getLeash, getFlags, release;

    /** Told each time a swap has been put on screen. */
    static volatile Runnable onApplied;

    /**
     * Asked before a swap is finished: runs what it is given once the window coming to the front has its
     * picture on screen (at once, when it already has). Until then the window going away stays where it is,
     * beneath, so there is never a moment with neither.
     */
    static volatile java.util.function.Consumer<Runnable> whenShown;

    @Override
    public void startAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
                               IRemoteTransitionFinishedCallback finishCallback) {
        final java.util.function.Consumer<Runnable> hold = whenShown;
        try {
            if (getChanges == null) {
                getChanges = info.getClass().getMethod("getChanges");
                release = info.getClass().getMethod("releaseAllSurfaces");
            }
            List<?> changes = (List<?>) getChanges.invoke(info);
            int n = changes.size(), at = 0;
            for (Object change : changes) {
                at++;
                if (getMode == null) {
                    getMode = change.getClass().getMethod("getMode");
                    getLeash = change.getClass().getMethod("getLeash");
                }
                int mode = (Integer) getMode.invoke(change);
                SurfaceControl leash = (SurfaceControl) getLeash.invoke(change);
                if (leash == null || !leash.isValid()) continue;
                if (getFlags == null) getFlags = change.getClass().getMethod("getFlags");
                boolean wallpaper = ((Integer) getFlags.invoke(change) & FLAG_IS_WALLPAPER) != 0;
                // Both home screens sit on the wallpaper. It is listed as leaving when one of them does, but
                // the other needs it the same instant: hiding it here leaves a black screen behind the icons.
                if (wallpaper || mode == OPEN || mode == TO_FRONT) {
                    t.setVisibility(leash, true);
                    t.setAlpha(leash, 1f);
                    if (!wallpaper) t.setLayer(leash, 2 * n - at); // above the one that leaves
                } else if (mode == CLOSE || mode == TO_BACK) {
                    if (hold == null) t.setVisibility(leash, false);
                    else t.setLayer(leash, n - at); // stays beneath until the newcomer has drawn
                }
            }
        } catch (Throwable e) {
            Log.w(Module.TAG, "instant transition", e);
        }
        try {
            t.apply();
        } catch (Throwable e) {
            Log.w(Module.TAG, "instant transition apply", e);
        }
        Runnable finish = () -> {
            try {
                finishCallback.onTransitionFinished(null, null);
            } catch (Throwable e) {
                Log.w(Module.TAG, "instant transition finish", e);
            }
            Runnable then = onApplied;
            if (then != null) then.run();
            try {
                release.invoke(info);
            } catch (Throwable e) {
                // the surfaces go with the object
            }
        };
        if (hold == null) {
            finish.run();
        } else {
            try {
                hold.accept(finish);
            } catch (Throwable e) {
                finish.run();
            }
        }
    }

    @Override
    public void mergeAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t, IBinder mergeTarget,
                               IRemoteTransitionFinishedCallback finishCallback) {
        // already at its end state; whatever comes next plays by itself
    }

    @Override
    public void takeOverAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
                                  IRemoteTransitionFinishedCallback finishCallback, WindowAnimationState[] states) {
        startAnimation(token, info, t, finishCallback);
    }

    @Override
    public void onTransitionConsumed(IBinder token, boolean aborted) {
    }
}
