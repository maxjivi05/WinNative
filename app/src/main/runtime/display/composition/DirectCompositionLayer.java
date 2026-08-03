package com.winlator.cmod.runtime.display.composition;

import android.util.Log;
import android.view.Surface;

/**
 * A single {@code ASurfaceControl} child layer bound to the XServerSurfaceView's
 * Surface. Every public method is synchronized so the UI thread's
 * {@link #release()} can't race the render thread's {@link #pushBuffer}.
 */
public final class DirectCompositionLayer {

    private static final String TAG = "DirectCompositionLayer";

    /** Native ASurfaceControl* pointer. 0 = not attached / released. */
    private long nativeSc = 0;
    private boolean attached = false;

    /** Creates the layer, hidden until the first successful {@link #pushBuffer}. */
    public synchronized boolean attach(Surface surface) {
        if (attached) {
            Log.w(TAG, "attach: already attached, ignoring");
            return true;
        }
        if (surface == null || !surface.isValid()) {
            Log.w(TAG, "attach: surface is null or invalid");
            return false;
        }
        nativeSc = nativeCreateFromWindow(surface, "winnative-direct-composition");
        if (nativeSc == 0) {
            Log.e(TAG, "attach: nativeCreateFromWindow returned 0");
            return false;
        }
        attached = true;
        Log.i(TAG, "Direct Composition layer attached: sc=" + nativeSc);
        return true;
    }

    /**
     * Shows an AHardwareBuffer on the layer, atomically with the buffer set.
     *
     * @param acquireFenceFd producer fence, or -1. Ownership passes to the
     *                       framework on success and is closed here otherwise.
     * @param opaque         declares alpha=1.0 throughout, letting HWC skip
     *                       blending and avoid the Snapdragon SDR-on-HDR
     *                       brightness boost.
     * @return false on any failure; the caller self-detaches after
     *         DC_FAIL_LIMIT consecutive failures.
     */
    public synchronized boolean pushBuffer(long ahbPtr, int dstX, int dstY,
                                            int dstW, int dstH,
                                            int acquireFenceFd, boolean opaque, boolean pace) {
        if (!attached || nativeSc == 0) {
            if (acquireFenceFd >= 0) {
                try { android.os.ParcelFileDescriptor.adoptFd(acquireFenceFd).close(); }
                catch (java.io.IOException ignored) {}
            }
            return false;
        }
        return nativePushBuffer(nativeSc, ahbPtr, dstX, dstY, dstW, dstH,
                                acquireFenceFd, opaque, pace);
    }

    /** Hides the layer. Idempotent. */
    public synchronized void hide() {
        if (!attached || nativeSc == 0) return;
        nativeHide(nativeSc);
    }

    /**
     * Reparents to null, drains in-flight transactions, then releases the
     * native layer. The instance is unusable afterwards.
     */
    public synchronized void release() {
        if (!attached) return;
        nativeDetachAndRelease(nativeSc);
        nativeSc = 0;
        attached = false;
        Log.i(TAG, "Direct Composition layer released");
    }

    public synchronized boolean isAttached() {
        return attached;
    }

    // --- Native methods ---

    private native long nativeCreateFromWindow(Surface surface, String debugName);

    private native void nativeDetachAndRelease(long scPtr);

    private native void nativeHide(long scPtr);

    private native boolean nativePushBuffer(long scPtr, long ahbPtr,
                                             int dstX, int dstY,
                                             int dstW, int dstH,
                                             int acquireFenceFd, boolean opaque, boolean pace);

    // Blocks until SF finishes the previous frame (hardware signal, no CPU polling).
    public native boolean nativeWaitForPreviousFrame(long timeoutMs);
}
