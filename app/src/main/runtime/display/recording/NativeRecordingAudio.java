package com.winlator.cmod.runtime.display.recording;

import java.nio.ByteBuffer;

final class NativeRecordingAudio {
  static {
    System.loadLibrary("winlator");
  }

  static native int open();

  static native int read(int fd, ByteBuffer buffer);

  static native void close(int fd);
}
