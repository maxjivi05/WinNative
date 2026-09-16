package com.winlator.cmod.runtime.display.composition;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.Keep;
import com.winlator.cmod.runtime.display.xserver.Drawable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DirectCompositionLayer {
    private static final String TAG = "DirectCompositionLayer";
    private static final long COMPLETION_WATCHDOG_MS = 500L;

    public static final int PRESENT_FAILED = 0;
    public static final int PRESENT_COMPOSED = 1;
    public static final int PRESENT_OVERLAY = 2;

    static {
        System.loadLibrary("winlator");
    }

    private static volatile Boolean supported;

    private static final class Hold {
        final Drawable drawable;
        int count;

        Hold(Drawable drawable) {
            this.drawable = drawable;
        }
    }

    private final Runnable onCompleted;
    private final Handler watchdog = new Handler(Looper.getMainLooper());
    private final Object holdLock = new Object();
    private final HashMap<Long, Hold> holds = new HashMap<>();
    private final AtomicLong inFlightToken = new AtomicLong(0L);
    private long nativeHandle;
    private long nextToken = 1L;
    private long currentAhb;
    private boolean released;

    private DirectCompositionLayer(Runnable onCompleted) {
        this.onCompleted = onCompleted;
    }

    public static boolean isSupported() {
        Boolean cached = supported;
        if (cached != null) return cached;
        boolean result = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                result = nativeIsSupported();
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "native probe unavailable", e);
            }
        }
        supported = result;
        return result;
    }

    public static DirectCompositionLayer create(Surface surface, Runnable onCompleted) {
        if (surface == null || !surface.isValid() || !isSupported()) return null;
        DirectCompositionLayer layer = new DirectCompositionLayer(onCompleted);
        layer.nativeHandle = layer.nativeCreate(surface);
        return layer.nativeHandle != 0 ? layer : null;
    }

    public boolean isIdle() {
        return inFlightToken.get() == 0L;
    }

    public long getCurrentBuffer() {
        return currentAhb;
    }

    public int present(long ahbPtr, Drawable source,
                       int cropX, int cropY, int cropW, int cropH,
                       int dstX, int dstY, int dstW, int dstH) {
        if (nativeHandle == 0 || !isIdle()) return PRESENT_FAILED;
        final long token = nextToken++;
        final long previous = currentAhb;
        inFlightToken.set(token);
        int result = nativePresent(nativeHandle, token, ahbPtr,
                cropX, cropY, cropW, cropH, dstX, dstY, dstW, dstH);
        if (result == PRESENT_FAILED) {
            inFlightToken.set(0L);
            return result;
        }
        currentAhb = ahbPtr;
        source.acquireDisplayHold();
        synchronized (holdLock) {
            Hold hold = holds.get(ahbPtr);
            if (hold == null) {
                hold = new Hold(source);
                holds.put(ahbPtr, hold);
            }
            hold.count++;
        }
        watchdog.postDelayed(() -> {
            if (inFlightToken.compareAndSet(token, 0L)) {
                Log.w(TAG, "transaction " + token + " never completed, forcing release");
                releaseHold(previous);
                onCompleted.run();
            }
        }, COMPLETION_WATCHDOG_MS);
        return result;
    }

    public void release() {
        if (nativeHandle == 0) return;
        long handle = nativeHandle;
        nativeHandle = 0;
        currentAhb = 0L;
        inFlightToken.set(0L);
        nativeDestroy(handle);
        watchdog.postDelayed(this::releaseAllHolds, COMPLETION_WATCHDOG_MS);
    }

    @Keep
    private void onTransactionCompleted(long token, long releasedAhb) {
        releaseHold(releasedAhb);
        if (inFlightToken.compareAndSet(token, 0L)) onCompleted.run();
    }

    @Keep
    private void onDestroyed() {
        releaseAllHolds();
    }

    private void releaseHold(long ahbPtr) {
        if (ahbPtr == 0L) return;
        Drawable drawable;
        synchronized (holdLock) {
            Hold hold = holds.get(ahbPtr);
            if (hold == null) return;
            hold.count--;
            if (hold.count <= 0) holds.remove(ahbPtr);
            drawable = hold.drawable;
        }
        drawable.releaseDisplayHold();
    }

    private void releaseAllHolds() {
        ArrayList<Hold> pending;
        synchronized (holdLock) {
            if (released) return;
            released = true;
            pending = new ArrayList<>(holds.values());
            holds.clear();
        }
        for (Hold hold : pending) {
            for (int i = 0; i < hold.count; i++) hold.drawable.releaseDisplayHold();
        }
    }

    private static native boolean nativeIsSupported();
    private native long nativeCreate(Surface surface);
    private static native int nativePresent(long handle, long token, long ahbPtr,
                                            int cropX, int cropY, int cropW, int cropH,
                                            int dstX, int dstY, int dstW, int dstH);
    private static native void nativeDestroy(long handle);
}
