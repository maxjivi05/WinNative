package com.winlator.cmod.runtime.display.composition;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Java entry point for the native Direct Composition module.
 *
 * <p>{@link #isAvailable()} requires API 29+ and that the libandroid.so symbols
 * resolve. Beyond that, device eligibility is left to the per-container opt-in
 * toggle rather than a static brand blocklist.
 */
public final class SurfaceCompositor {

    static {
        // Same pattern used by SysVSharedMemory, GPUImage, ClientSocket, etc.
        System.loadLibrary("winlator");
    }

    private static final String TAG = "SurfaceCompositor";

    /** Cached probe result. null until first call; thereafter final-state. */
    private static volatile Boolean cachedAvailability;

    private SurfaceCompositor() {
        // Static-only utility.
    }

    /** @return true when the API 29+ SurfaceControl symbols are usable here. */
    public static boolean isAvailable() {
        Boolean cached = cachedAvailability;
        if (cached != null) {
            return cached;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            cachedAvailability = Boolean.FALSE;
            return false;
        }

        boolean result;
        try {
            result = nativeIsAvailable();
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            Log.w(TAG, "nativeIsAvailable threw, treating as unavailable", e);
            result = false;
        }
        cachedAvailability = result;
        if (result) {
            Log.i(TAG, "Direct Composition is available on this device");
        }
        return result;
    }

    private static native boolean nativeIsAvailable();

    // Shared wine_*.txt logs only capture Wine/FEX stderr, not logcat, so DC
    // events go to their own direct-composition.log in the logs directory —
    // LogManager includes it automatically when the user shares logs.

    private static volatile File diagFile = null;
    private static volatile FileWriter diagWriter = null;
    private static final Object diagLock = new Object();
    private static final SimpleDateFormat diagDateFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** Called once at session start, before any DC code runs. */
    public static void initDiagnosticFile(File logsDir) {
        synchronized (diagLock) {
            try {
                if (diagWriter != null) {
                    diagWriter.flush();
                    diagWriter.close();
                }
                if (logsDir != null && !logsDir.exists()) logsDir.mkdirs();
                diagFile = new File(logsDir, "direct-composition.log");
                diagWriter = new FileWriter(diagFile, /*append=*/false);
                logEvent("=== Direct Composition diagnostic log started ===");
                logEvent("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                        + " (API " + Build.VERSION.SDK_INT + ")");
                logEvent("isAvailable() = " + isAvailable());
            } catch (IOException e) {
                Log.w(TAG, "Failed to init diagnostic file", e);
                diagWriter = null;
            }
        }
    }

    /** Appends a timestamped line to the diagnostic file and logcat. Thread-safe. */
    public static void logEvent(String message) {
        String timestamped = "[" + diagDateFormat.format(new Date()) + "] " + message;
        Log.i(TAG, message);
        synchronized (diagLock) {
            if (diagWriter != null) {
                // Flush every line: this log exists to diagnose SurfaceFlinger
                // crashes and soft reboots, and anything still buffered when the
                // device goes down is exactly the part worth having.
                try {
                    diagWriter.write(timestamped + "\n");
                    diagWriter.flush();
                } catch (IOException ignored) {}
            }
        }
    }

    /** Called from XServerDisplayActivity.onDestroy. */
    public static void closeDiagnosticFile() {
        synchronized (diagLock) {
            try {
                if (diagWriter != null) {
                    logEvent("=== Direct Composition diagnostic log closed ===");
                    diagWriter.flush();
                    diagWriter.close();
                }
            } catch (IOException ignored) {
            } finally {
                diagWriter = null;
                diagFile = null;
            }
        }
    }
}
