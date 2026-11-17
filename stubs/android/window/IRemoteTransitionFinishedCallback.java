package android.window;

import android.os.IInterface;
import android.view.SurfaceControl;

public interface IRemoteTransitionFinishedCallback extends IInterface {
    void onTransitionFinished(WindowContainerTransaction wct, SurfaceControl.Transaction sct);
}
