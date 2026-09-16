package com.winlator.cmod.runtime.display.recording;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class RecordingAudioMixerTest {
  private ByteBuffer pcm(int frames, int channels, int value, ByteOrder order) {
    ByteBuffer buffer = ByteBuffer.allocate(frames * channels * 2).order(order);
    for (int i = 0; i < frames * channels; i++) buffer.putShort((short) value);
    buffer.flip();
    return buffer;
  }

  private ByteBuffer read(RecordingAudioMixer mixer, int frames) {
    ByteBuffer out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
    mixer.read(out, frames);
    out.flip();
    return out;
  }

  @Test
  public void delayedAudioRetainsItsTimelineAfterSilence() {
    RecordingAudioMixer mixer = new RecordingAudioMixer();
    ByteBuffer silent = read(mixer, 48000 * 2);
    while (silent.hasRemaining()) assertEquals(0, silent.getShort());
    mixer.add("game", pcm(480, 2, 1000, ByteOrder.LITTLE_ENDIAN), 48000, 2, 2, 96480);
    ByteBuffer result = read(mixer, 480);
    while (result.hasRemaining()) assertEquals(1000, result.getShort());
    assertEquals(96480, mixer.position());
  }

  @Test
  public void mixesIndependentSourcesAndClipsWithoutWrapping() {
    RecordingAudioMixer mixer = new RecordingAudioMixer();
    mixer.add("game", pcm(480, 2, 24000, ByteOrder.LITTLE_ENDIAN), 48000, 2, 2, 480);
    mixer.add("mic", pcm(480, 1, 16000, ByteOrder.LITTLE_ENDIAN), 48000, 1, 2, 480);
    ByteBuffer result = read(mixer, 480);
    while (result.hasRemaining()) assertEquals(32767, result.getShort());
  }

  @Test
  public void handlesResamplingEndianAndKeepsSourcePosition() {
    RecordingAudioMixer mixer = new RecordingAudioMixer();
    ByteBuffer source = pcm(441, 1, -1234, ByteOrder.BIG_ENDIAN);
    mixer.add("game", source, 44100, 1, 2, 480);
    assertEquals(0, source.position());
    ByteBuffer result = read(mixer, 480);
    while (result.hasRemaining()) assertEquals(-1234, result.getShort());
  }

  @Test
  public void overrunCannotOverwriteEarlierFrames() {
    RecordingAudioMixer mixer = new RecordingAudioMixer();
    mixer.add("near", pcm(480, 1, 1234, ByteOrder.LITTLE_ENDIAN), 48000, 1, 2, 480);
    mixer.add("far", pcm(480, 1, 30000, ByteOrder.LITTLE_ENDIAN), 48000, 1, 2, 48000);
    ByteBuffer result = read(mixer, 480);
    while (result.hasRemaining()) assertEquals(1234, result.getShort());
  }

  @Test
  public void sequentialBlocksDoNotOverlapWhenCallbacksAreBatched() {
    RecordingAudioMixer mixer = new RecordingAudioMixer();
    mixer.add("game", pcm(480, 1, 1000, ByteOrder.LITTLE_ENDIAN), 48000, 1, 2, 480);
    mixer.add("game", pcm(480, 1, 2000, ByteOrder.LITTLE_ENDIAN), 48000, 1, 2, 480);
    ByteBuffer result = read(mixer, 960);
    for (int i = 0; i < 960; i++) assertEquals(1000, result.getShort());
    while (result.hasRemaining()) assertEquals(2000, result.getShort());
  }

  @Test
  public void floatAndUnsignedEightBitAreConverted() {
    RecordingAudioMixer mixer = new RecordingAudioMixer();
    ByteBuffer floats =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).putFloat(0.5f);
    floats.flip();
    mixer.add("float", floats, 48000, 2, 4, 1);
    ByteBuffer result = read(mixer, 1);
    assertEquals(0, result.getShort());
    assertEquals(16384, result.getShort());
    mixer.add("eight", ByteBuffer.wrap(new byte[] {0, (byte) 255}), 48000, 2, 3, 2);
    result = read(mixer, 1);
    assertEquals(-32768, result.getShort());
    assertEquals(32512, result.getShort());
  }

  @Test
  public void droppedAudioDoesNotCompressTheSampleClock() {
    RecordingAudioMixer mixer = new RecordingAudioMixer();
    read(mixer, 9600);
    mixer.add("game", pcm(480, 1, 2000, ByteOrder.LITTLE_ENDIAN), 48000, 1, 2, 12000);
    ByteBuffer silence = read(mixer, 1920);
    while (silence.hasRemaining()) assertEquals(0, silence.getShort());
    ByteBuffer result = read(mixer, 480);
    while (result.hasRemaining()) assertEquals(2000, result.getShort());
  }
}
