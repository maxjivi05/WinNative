package com.winlator.cmod.runtime.input.controls;

import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class FakeInputWriter {
  public static final short ABS_BRAKE = 10;
  public static final short ABS_GAS = 9;
  public static final short ABS_HAT0X = 16;
  public static final short ABS_HAT0Y = 17;
  public static final short ABS_RX = 3;
  public static final short ABS_RY = 4;
  public static final short ABS_X = 0;
  public static final short ABS_Y = 1;
  private static final int BUFFER_SIZE = 768;
  private static final int EVENT_SIZE = 24;
  public static final short EV_ABS = 3;
  public static final short EV_KEY = 1;
  public static final short EV_MSC = 4;
  public static final short EV_SYN = 0;
  private static final int MAX_EVENTS_PER_UPDATE = 32;
  public static final short MSC_SCAN = 4;
  public static final short SYN_REPORT = 0;
  private static final String TAG = "FakeInputWriter";
  // Cap pure-axis writes to ~200 Hz to match Wine's fake-evdev poll cadence
  // and avoid the touch-driven event flood that progressively backs up the
  // fake-evdev queue and starves Wine's input thread.
  private static final long MIN_AXIS_WRITE_INTERVAL_NS = 5_000_000L;
  private FileChannel channel;
  private final File eventFile;
  private int prevHatX;
  private int prevHatY;
  private int prevThumbLX;
  private int prevThumbLY;
  private int prevThumbRX;
  private int prevThumbRY;
  private int prevTriggerL;
  private int prevTriggerR;
  private RandomAccessFile raf;
  public static final short BTN_A = 304;
  public static final short BTN_B = 305;
  public static final short BTN_X = 307;
  public static final short BTN_Y = 308;
  public static final short BTN_TL = 310;
  public static final short BTN_TR = 311;
  public static final short BTN_SELECT = 314;
  public static final short BTN_START = 315;
  public static final short BTN_THUMBL = 317;
  public static final short BTN_THUMBR = 318;
  private static final short[] BUTTON_MAP = {
    BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR, BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR
  };
  private boolean isOpen = false;
  private volatile boolean destroyed = false;
  private final boolean[] prevButtonStates = new boolean[12];
  private boolean hasChanges = false;
  private long lastWriteNs = 0L;
  private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

  public FakeInputWriter(String fakeInputPath, int slot) {
    this.eventFile = new File(fakeInputPath, NotificationCompat.CATEGORY_EVENT + slot);
    this.buffer.order(ByteOrder.LITTLE_ENDIAN);
  }

  public synchronized boolean open() {
    if (this.destroyed) {
      return false;
    }
    if (this.isOpen) {
      return true;
    }
    try {
      this.eventFile.getParentFile().mkdirs();
      if (!this.eventFile.exists()) {
        this.eventFile.createNewFile();
      }
      this.raf = new RandomAccessFile(this.eventFile, "rw");
      this.raf.seek(this.raf.length());
      this.channel = this.raf.getChannel();
      this.isOpen = true;
      Log.i(TAG, "Opened fake input: " + this.eventFile.getAbsolutePath());
      return true;
    } catch (IOException e) {
      Log.e(TAG, "Failed to open: " + e.getMessage());
      return false;
    }
  }

  public synchronized void close() {
    if (this.channel != null) {
      try {
        this.channel.close();
      } catch (IOException e) {
      }
      this.channel = null;
    }
    if (this.raf != null) {
      try {
        this.raf.close();
      } catch (IOException e2) {
      }
      this.raf = null;
    }
    this.isOpen = false;
  }

  public synchronized void reset() {
    if (this.isOpen || open()) {
      this.buffer.clear();
      this.hasChanges = false;
      for (int i = 0; i < BUTTON_MAP.length; i++) {
        if (this.prevButtonStates[i]) {
          this.prevButtonStates[i] = false;
          writeEvent((short) 4, (short) 4, BUTTON_MAP[i]);
          writeEvent((short) 1, BUTTON_MAP[i], 0);
        }
      }
      if (this.prevThumbLX != 0) {
        this.prevThumbLX = 0;
        writeEvent((short) 3, (short) 0, 0);
      }
      if (this.prevThumbLY != 0) {
        this.prevThumbLY = 0;
        writeEvent((short) 3, (short) 1, 0);
      }
      if (this.prevThumbRX != 0) {
        this.prevThumbRX = 0;
        writeEvent((short) 3, (short) 3, 0);
      }
      if (this.prevThumbRY != 0) {
        this.prevThumbRY = 0;
        writeEvent((short) 3, (short) 4, 0);
      }
      if (this.prevTriggerL != 0) {
        this.prevTriggerL = 0;
        writeEvent((short) 3, (short) 10, 0);
      }
      if (this.prevTriggerR != 0) {
        this.prevTriggerR = 0;
        writeEvent((short) 3, (short) 9, 0);
      }
      if (this.prevHatX != 0) {
        this.prevHatX = 0;
        writeEvent((short) 3, (short) 16, 0);
      }
      if (this.prevHatY != 0) {
        this.prevHatY = 0;
        writeEvent((short) 3, (short) 17, 0);
      }
      if (this.hasChanges) {
        writeEvent((short) 0, (short) 0, 0);
        this.buffer.flip();
        try {
          this.channel.write(this.buffer);
        } catch (IOException e) {
          Log.e(TAG, "Reset write error: " + e.getMessage());
        }
        Log.i(TAG, "Reset fake input to neutral state: " + this.eventFile.getAbsolutePath());
        return;
      }
      Log.i(TAG, "Reset fake input to neutral state: " + this.eventFile.getAbsolutePath());
    }
  }

  public synchronized void softRelease() {
    reset();
    close();
    Log.i(TAG, "Soft released fake input: " + this.eventFile.getAbsolutePath());
  }

  public synchronized void destroy() {
    this.destroyed = true;
    reset();
    close();
    if (this.eventFile != null && this.eventFile.exists()) {
      boolean deleted = this.eventFile.delete();
      Log.i(TAG, "Deleted fake input: " + this.eventFile.getAbsolutePath() + " (" + deleted + ")");
    }
  }

  private void writeEvent(short type, short code, int value) {
    long timeMs = System.currentTimeMillis();
    this.buffer.putLong(timeMs / 1000);
    this.buffer.putLong((timeMs % 1000) * 1000);
    this.buffer.putShort(type);
    this.buffer.putShort(code);
    this.buffer.putInt(value);
    this.hasChanges = true;
  }

  private void writeButton(int i, boolean z) {
    if (i < 0 || i >= BUTTON_MAP.length || this.prevButtonStates[i] == z) {
      return;
    }
    this.prevButtonStates[i] = z;
    writeEvent((short) 4, (short) 4, BUTTON_MAP[i]);
    writeEvent((short) 1, BUTTON_MAP[i], z ? 1 : 0);
  }

  private void writeAxis(short code, int value, int[] prevRef, int index) {
    if (prevRef[index] == value) {
      return;
    }
    prevRef[index] = value;
    writeEvent((short) 3, code, value);
  }

  public void writeGamepadState(GamepadState state) throws IOException {
    if (!this.isOpen && !open()) {
      return;
    }

    int lx = (int) (state.thumbLX * 32767.0f);
    int ly = (int) (state.thumbLY * 32767.0f);
    int rx = (int) (state.thumbRX * 32767.0f);
    int ry = (int) (state.thumbRY * 32767.0f);
    int tl = (int) (state.triggerL * 255.0f);
    int tr = (int) (state.triggerR * 255.0f);
    int hatX = state.dpad[3] ? -1 : (state.dpad[1] ? 1 : 0);
    int hatY = state.dpad[0] ? -1 : (state.dpad[2] ? 1 : 0);

    boolean buttonChanged = false;
    for (int i = 0; i < 10; i++) {
      if (this.prevButtonStates[i] != state.isPressed((byte) i)) {
        buttonChanged = true;
        break;
      }
    }
    boolean axisChanged =
        lx != this.prevThumbLX
            || ly != this.prevThumbLY
            || rx != this.prevThumbRX
            || ry != this.prevThumbRY
            || tl != this.prevTriggerL
            || tr != this.prevTriggerR
            || hatX != this.prevHatX
            || hatY != this.prevHatY;

    if (!buttonChanged && !axisChanged) {
      return;
    }

    // Throttle pure-axis updates so we don't outrun Wine's poll cadence and
    // back up the fake-evdev queue with redundant micro-movements from the
    // touchscreen. Button transitions always go through immediately so input
    // latency on press/release stays unaffected.
    //
    // Exception: if the new state is "fully neutral" (all axes/triggers at
    // zero) and the previous state had any non-zero axis, this is the user
    // releasing a virtual joystick. Touchscreens emit only a single terminal
    // (0, 0) update on release, so dropping it leaves the stick stuck at its
    // last position from the game's perspective. Always let releases through.
    boolean returningToNeutral =
        lx == 0 && ly == 0 && rx == 0 && ry == 0 && tl == 0 && tr == 0
            && hatX == 0 && hatY == 0
            && (this.prevThumbLX != 0 || this.prevThumbLY != 0
                || this.prevThumbRX != 0 || this.prevThumbRY != 0
                || this.prevTriggerL != 0 || this.prevTriggerR != 0
                || this.prevHatX != 0 || this.prevHatY != 0);
    if (!buttonChanged && !returningToNeutral) {
      long now = System.nanoTime();
      if (now - this.lastWriteNs < MIN_AXIS_WRITE_INTERVAL_NS) {
        return;
      }
    }

    this.buffer.clear();
    this.hasChanges = false;
    for (int i = 0; i < 10; i++) {
      writeButton(i, state.isPressed((byte) i));
    }

    if (lx != this.prevThumbLX) {
      this.prevThumbLX = lx;
      writeEvent((short) 3, (short) 0, lx);
    }
    if (ly != this.prevThumbLY) {
      this.prevThumbLY = ly;
      writeEvent((short) 3, (short) 1, ly);
    }
    if (rx != this.prevThumbRX) {
      this.prevThumbRX = rx;
      writeEvent((short) 3, (short) 3, rx);
    }
    if (ry != this.prevThumbRY) {
      this.prevThumbRY = ry;
      writeEvent((short) 3, (short) 4, ry);
    }
    if (tl != this.prevTriggerL) {
      this.prevTriggerL = tl;
      writeEvent((short) 3, (short) 10, tl);
    }
    if (tr != this.prevTriggerR) {
      this.prevTriggerR = tr;
      writeEvent((short) 3, (short) 9, tr);
    }
    if (hatX != this.prevHatX) {
      this.prevHatX = hatX;
      writeEvent((short) 3, (short) 16, hatX);
    }
    if (hatY != this.prevHatY) {
      this.prevHatY = hatY;
      writeEvent((short) 3, (short) 17, hatY);
    }

    if (this.hasChanges) {
      writeEvent((short) 0, (short) 0, 0);
      this.buffer.flip();
      this.channel.write(this.buffer);
      this.lastWriteNs = System.nanoTime();
    }
  }
}
