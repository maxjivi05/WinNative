package com.winlator.cmod.runtime.display.recording;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

final class RecordingAudioMixer {
  static final int RATE = 48000;
  static final int BLOCK = 480;
  private static final int CAPACITY = RATE / 2;
  private final float[] samples = new float[CAPACITY * 2];
  private final Map<Object, Long> cursors =
      new LinkedHashMap<Object, Long>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Long> entry) {
          return size() > 64;
        }
      };
  private long position;

  long position() {
    return position;
  }

  void add(Object source, ByteBuffer pcm, int rate, int channels, int encoding, long arrivalFrame) {
    int bytes = encoding == 4 ? 4 : encoding == 2 ? 2 : encoding == 3 ? 1 : 0;
    if (bytes == 0 || channels < 1 || channels > 8 || rate < 8000 || rate > 192000) return;
    ByteBuffer input = pcm.duplicate().order(pcm.order());
    int frames = input.remaining() / (bytes * channels);
    int count = (int) ((long) frames * RATE / rate);
    if (count == 0) return;
    long start = Math.max(position, arrivalFrame - count);
    Long cursor = cursors.get(source);
    if (cursor != null && Math.abs(cursor - start) < RATE / 10) start = Math.max(start, cursor);
    cursors.put(source, start + count);
    int limit = (int) Math.min(count, position + CAPACITY - start);
    int offset = input.position();
    for (int i = 0; i < limit; i++) {
      double at = (double) i * rate / RATE;
      int a = (int) at;
      int b = Math.min(a + 1, frames - 1);
      float fraction = (float) (at - a);
      for (int side = 0; side < 2; side++) {
        float value = 0;
        int contributors = 0;
        for (int ch = channels == 1 ? 0 : side; ch < channels; ch += 2) {
          float first = sample(input, offset + (a * channels + ch) * bytes, encoding);
          float next = sample(input, offset + (b * channels + ch) * bytes, encoding);
          value += first + (next - first) * fraction;
          contributors++;
        }
        int index = (int) ((start + i) % CAPACITY) * 2 + side;
        samples[index] += value / Math.max(1, contributors);
      }
    }
  }

  private static float sample(ByteBuffer b, int at, int encoding) {
    if (encoding == 2) return b.getShort(at) / 32768f;
    if (encoding == 3) return ((b.get(at) & 255) - 128) / 128f;
    float value = b.getFloat(at);
    return Float.isFinite(value) ? Math.max(-1f, Math.min(1f, value)) : 0f;
  }

  void read(ByteBuffer output, int frames) {
    output.order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < frames; i++) {
      int index = (int) (position++ % CAPACITY) * 2;
      for (int side = 0; side < 2; side++) {
        float value = samples[index + side];
        output.putShort((short) Math.max(-32768, Math.min(32767, Math.round(value * 32768f))));
        samples[index + side] = 0;
      }
    }
  }
}
