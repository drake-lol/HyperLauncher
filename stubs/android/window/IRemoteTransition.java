package android.window;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.view.SurfaceControl;

/** Compile-time stand-in for the framework's hidden interface; never packaged. */
public interface IRemoteTransition extends IInterface {
    void startAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
                        IRemoteTransitionFinishedCallback finishCallback);

    void mergeAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t, IBinder mergeTarget,
                        IRemoteTransitionFinishedCallback finishCallback);

    void takeOverAnimation(IBinder token, TransitionInfo info, SurfaceControl.Transaction t,
                           IRemoteTransitionFinishedCallback finishCallback, WindowAnimationState[] states);

    void onTransitionConsumed(IBinder token, boolean aborted);

    abstract class Stub extends Binder implements IRemoteTransition {
        public Stub() {
            throw new RuntimeException("stub");
        }

        @Override
        public IBinder asBinder() {
            throw new RuntimeException("stub");
        }
    }
}
