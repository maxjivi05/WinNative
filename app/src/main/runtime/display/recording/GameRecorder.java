package com.winlator.cmod.runtime.display.recording;

import android.content.Context;
import android.media.*;
import android.os.Build;
import android.os.Environment;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import timber.log.Timber;

public final class GameRecorder {
  private static final String TAG = "GameRecorder";
  private static volatile GameRecorder activeRecorder;

  public static GameRecorder active() {
    return activeRecorder;
  }

  private final Context context;
  private final ArrayBlockingQueue<Pcm> pcmQueue = new ArrayBlockingQueue<>(32);
  private final RecordingAudioMixer mixer = new RecordingAudioMixer();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private volatile boolean recording;
  private volatile String failure;
  private volatile File savedFile;
  private MediaCodec video, audio;
  private Surface surface;
  private MediaMuxer muxer;
  private AudioRecord microphone;
  private Thread worker;
  private long baseNs;
  private int nativeAudio = -1;
  private int width, height, fps;
  private int videoTrack = -1, audioTrack = -1;
  private boolean muxerStarted, videoWritten;
  private File output;
  private final List<Sample> pending = new ArrayList<>();
  private int pendingBytes;

  private static final class Pcm {
    final Object source;
    final ByteBuffer data;
    final int rate, channels, encoding;
    final long time;

    Pcm(Object source, ByteBuffer data, int rate, int channels, int encoding, long time) {
      this.source = source;
      this.data = data;
      this.rate = rate;
      this.channels = channels;
      this.encoding = encoding;
      this.time = time;
    }
  }

  private static final class Sample {
    final boolean video;
    final ByteBuffer data;
    final MediaCodec.BufferInfo info;

    Sample(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
      this.video = video;
      this.data = data;
      this.info = info;
    }
  }

  public GameRecorder(Context context) {
    this.context = context.getApplicationContext();
  }

  public boolean isRecording() {
    return recording;
  }

  public String getFailure() {
    return failure;
  }

  public File getSavedFile() {
    return savedFile;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public int getFps() {
    return fps;
  }

  public File cameraFile() {
    return new File(output.getParentFile(), output.getName().replace(".mp4", "_camera.mp4"));
  }

  public Surface start(
      int requestedWidth,
      int requestedHeight,
      int requestedFps,
      int orientation,
      int bitrate,
      boolean mic) {
    if (worker != null || surface != null || output != null || stopping.get())
      throw new IllegalStateException("Recorder is single-use");
    try {
      if (requestedWidth < 2 || requestedHeight < 2)
        throw new IllegalArgumentException("Invalid capture size");
      configureVideo(requestedWidth, requestedHeight, requestedFps, bitrate);
      MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2);
      format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      format.setInteger(MediaFormat.KEY_BIT_RATE, 160000);
      audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
      audio.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      audio.start();
      File dir = new File(Environment.getExternalStorageDirectory(), "WinNative/Recordings");
      if ((!dir.isDirectory() && !dir.mkdirs()) || !dir.canWrite()) {
        dir =
            new File(
                context.getExternalFilesDir(null) != null
                    ? context.getExternalFilesDir(null)
                    : context.getFilesDir(),
                "Recordings");
        if (!dir.isDirectory() && !dir.mkdirs())
          throw new IllegalStateException("Recording storage unavailable");
      }
      output = File.createTempFile("WinNative_" + System.currentTimeMillis() + "_", ".mp4", dir);
      muxer = new MediaMuxer(output.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      muxer.setOrientationHint(orientation);
      if (mic) {
        int size =
            AudioRecord.getMinBufferSize(
                48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) throw new IllegalStateException("Microphone format unavailable");
        microphone =
            new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                48000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(size * 2, 9600));
      }
      return surface;
    } catch (Exception e) {
      failure = e.getMessage();
      Timber.tag(TAG).e(e, "Recording setup failed");
      release();
      return null;
    }
  }

  public void begin() {
    if (worker != null || surface == null || stopping.get())
      throw new IllegalStateException("Recorder cannot start");
    if (microphone != null) {
      microphone.startRecording();
      if (microphone.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
        throw new IllegalStateException("Microphone unavailable");
    }
    nativeAudio = NativeRecordingAudio.open();
    if (nativeAudio < 0) throw new IllegalStateException("Game audio capture unavailable");
    baseNs = System.nanoTime();
    recording = true;
    activeRecorder = this;
    worker = new Thread(this::run, "GameRecorder");
    worker.start();
  }

  private void configureVideo(int w, int h, int requestedFps, int bitrate) throws Exception {
    Exception last = null;
    for (int shortSide : new int[] {Math.min(w, h), 1080, 720, 480}) {
      if (shortSide > Math.min(w, h)) continue;
      for (int rate : new int[] {Math.max(1, Math.min(165, requestedFps)), 30}) {
        if (rate > requestedFps) continue;
        for (MediaCodecInfo info :
            new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
          if (!info.isEncoder()) continue;
          String name = info.getName();
          if (Build.VERSION.SDK_INT >= 29
              ? !info.isHardwareAccelerated()
              : name.startsWith("OMX.google.") || name.startsWith("c2.android.")) continue;
          try {
            MediaCodecInfo.CodecCapabilities caps =
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
            int ew = (int) ((long) w * shortSide / Math.min(w, h));
            int eh = (int) ((long) h * shortSide / Math.min(w, h));
            ew -= ew % vc.getWidthAlignment();
            eh -= eh % vc.getHeightAlignment();
            if (!vc.areSizeAndRateSupported(ew, eh, rate)) continue;
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ew, eh);
            fmt.setInteger(
                MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            long scaled =
                (long) bitrate * ew * eh / ((long) w * h) * rate / Math.max(1, requestedFps);
            fmt.setInteger(
                MediaFormat.KEY_BIT_RATE,
                vc.getBitrateRange()
                    .clamp((int) Math.max(1000000, Math.min(Integer.MAX_VALUE, scaled))));
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, rate);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            if (caps.getEncoderCapabilities()
                .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
              fmt.setInteger(
                  MediaFormat.KEY_BITRATE_MODE,
                  MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            video = MediaCodec.createByCodecName(name);
            video.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = video.createInputSurface();
            video.start();
            width = ew;
            height = eh;
            fps = rate;
            return;
          } catch (Exception e) {
            last = e;
            if (surface != null) {
              surface.release();
              surface = null;
            }
            if (video != null) {
              try {
                video.release();
              } catch (Exception ignored) {
              }
              video = null;
            }
          }
        }
      }
    }
    throw new IllegalStateException("No supported hardware recording encoder", last);
  }

  public void onPcm(Object source, ByteBuffer data, int rate, int channels, int encoding) {
    if (!recording
        || stopping.get()
        || pcmQueue.remainingCapacity() == 0
        || data.remaining() > 65536) return;
    ByteBuffer copy = ByteBuffer.allocate(data.remaining()).order(data.order());
    copy.put(data.duplicate()).flip();
    pcmQueue.offer(new Pcm(source, copy, rate, channels, encoding, System.nanoTime()));
  }

  public void stop() {
    stopping.set(true);
    if (activeRecorder == this) activeRecorder = null;
    Thread thread = worker;
    if (thread == null) {
      release();
      return;
    }
    if (thread != null && thread != Thread.currentThread()) {
      boolean interrupted = false;
      while (thread.isAlive()) {
        try {
          thread.join();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void run() {
    ByteBuffer nativeBuffer = ByteBuffer.allocateDirect(4128).order(ByteOrder.LITTLE_ENDIAN);
    ByteBuffer micBuffer = ByteBuffer.allocateDirect(9600).order(ByteOrder.nativeOrder());
    MediaCodec.BufferInfo vi = new MediaCodec.BufferInfo(), ai = new MediaCodec.BufferInfo();
    boolean videoDone = false, audioDone = false, eos = false, audioEos = false;
    long deadline = Long.MAX_VALUE;
    long stopFrame = Long.MAX_VALUE;
    try {
      while (!videoDone || !audioDone) {
        long now = System.nanoTime();
        if (stopping.get() && !eos) {
          eos = true;
          deadline = now + 3000000000L;
          stopFrame = Math.max(mixer.position(), (now - baseNs) * 48000 / 1000000000L);
          video.signalEndOfInputStream();
        }
        for (int n = 0; n < 32; n++) {
          Pcm pcm = pcmQueue.poll();
          if (pcm == null) break;
          mixer.add(
              pcm.source,
              pcm.data,
              pcm.rate,
              pcm.channels,
              pcm.encoding,
              (pcm.time - baseNs) * 48000 / 1000000000L);
        }
        for (int n = 0; n < 64; n++) {
          nativeBuffer.clear();
          int bytes = NativeRecordingAudio.read(nativeAudio, nativeBuffer);
          if (bytes <= 0) break;
          if (bytes < 32) continue;
          long source = nativeBuffer.getLong(), time = nativeBuffer.getLong();
          int rate = nativeBuffer.getInt(), channels = nativeBuffer.getInt();
          int encoding = nativeBuffer.getInt(), size = nativeBuffer.getInt();
          if (size < 0 || size != bytes - 32) continue;
          nativeBuffer.limit(bytes);
          mixer.add(
              source,
              nativeBuffer,
              rate,
              channels,
              encoding,
              (time - baseNs) * 48000 / 1000000000L);
        }
        if (microphone != null) {
          micBuffer.clear();
          int read =
              microphone.read(micBuffer, micBuffer.capacity(), AudioRecord.READ_NON_BLOCKING);
          if (read < 0) throw new IllegalStateException("Microphone read failed: " + read);
          micBuffer.position(0);
          micBuffer.limit(read);
          mixer.add(
              microphone,
              micBuffer,
              48000,
              1,
              AudioFormat.ENCODING_PCM_16BIT,
              (now - baseNs) * 48000 / 1000000000L);
        }
        long available =
            eos ? stopFrame : Math.max(0, (now - baseNs - 100000000L) * 48000 / 1000000000L);
        for (int n = 0; n < 8 && !audioEos; n++) {
          long remaining = available - mixer.position();
          if (remaining < RecordingAudioMixer.BLOCK && !eos) break;
          int index = audio.dequeueInputBuffer(0);
          if (index < 0) break;
          ByteBuffer in = audio.getInputBuffer(index);
          if (in == null) throw new IllegalStateException("Missing audio input buffer");
          in.clear();
          int frames =
              (int)
                  Math.min(
                      Math.max(0, remaining),
                      Math.min(RecordingAudioMixer.BLOCK, in.remaining() / 4));
          long pts = mixer.position() * 1000000L / 48000;
          mixer.read(in, frames);
          audioEos = eos && mixer.position() >= stopFrame;
          audio.queueInputBuffer(
              index, 0, frames * 4, pts, audioEos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
        }
        if (!videoDone) videoDone = drain(video, vi, true);
        if (!audioDone) audioDone = drain(audio, ai, false);
        if (now > deadline) throw new IllegalStateException("Encoder did not finish");
        if (!muxerStarted && now - baseNs > 5000000000L)
          throw new IllegalStateException("No recording frames received");
        Thread.sleep(3);
      }
    } catch (Exception e) {
      failure = e.getMessage();
      Timber.tag(TAG).e(e, "Recording failed");
    } finally {
      recording = false;
      if (activeRecorder == this) activeRecorder = null;
      while (!stopping.get()) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }
      }
      release();
    }
  }

  private boolean drain(MediaCodec codec, MediaCodec.BufferInfo info, boolean isVideo) {
    for (int n = 0; n < 16; n++) {
      int index = codec.dequeueOutputBuffer(info, 0);
      if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
      if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        if (isVideo) videoTrack = muxer.addTrack(codec.getOutputFormat());
        else audioTrack = muxer.addTrack(codec.getOutputFormat());
        if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
          muxer.start();
          muxerStarted = true;
          for (Sample sample : pending) write(sample.video, sample.data, sample.info);
          pending.clear();
          pendingBytes = 0;
        }
      } else if (index >= 0) {
        try {
          ByteBuffer data = codec.getOutputBuffer(index);
          if (data != null
              && info.size > 0
              && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            if (isVideo)
              info.presentationTimeUs = Math.max(0, info.presentationTimeUs - baseNs / 1000);
            data.position(info.offset);
            data.limit(info.offset + info.size);
            if (muxerStarted) write(isVideo, data, info);
            else {
              if (pendingBytes + info.size > 8 * 1024 * 1024)
                throw new IllegalStateException("Encoder startup buffer full");
              ByteBuffer copy = ByteBuffer.allocate(info.size);
              copy.put(data).flip();
              MediaCodec.BufferInfo saved = new MediaCodec.BufferInfo();
              saved.set(0, info.size, info.presentationTimeUs, info.flags);
              pending.add(new Sample(isVideo, copy, saved));
              pendingBytes += info.size;
            }
          }
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
        } finally {
          codec.releaseOutputBuffer(index, false);
        }
      }
    }
    return false;
  }

  private void write(boolean isVideo, ByteBuffer data, MediaCodec.BufferInfo info) {
    muxer.writeSampleData(isVideo ? videoTrack : audioTrack, data, info);
    if (isVideo) videoWritten = true;
  }

  private void release() {
    if (nativeAudio >= 0) {
      NativeRecordingAudio.close(nativeAudio);
      nativeAudio = -1;
    }
    if (microphone != null) {
      try {
        microphone.stop();
      } catch (Exception ignored) {
      }
      microphone.release();
      microphone = null;
    }
    if (muxer != null) {
      boolean valid = muxerStarted && videoWritten;
      try {
        if (muxerStarted) muxer.stop();
      } catch (Exception e) {
        valid = false;
        failure = "Could not finalize recording";
      }
      try {
        muxer.release();
      } catch (Exception ignored) {
      }
      muxer = null;
      if (valid) {
        savedFile = output;
        MediaScannerConnection.scanFile(
            context, new String[] {output.getPath()}, new String[] {"video/mp4"}, null);
      }
    }
    for (MediaCodec codec : new MediaCodec[] {video, audio}) {
      if (codec != null) {
        try {
          codec.stop();
        } catch (Exception ignored) {
        }
        try {
          codec.release();
        } catch (Exception ignored) {
        }
      }
    }
    video = null;
    audio = null;
    if (surface != null) {
      surface.release();
      surface = null;
    }
    if (savedFile == null && output != null) output.delete();
    pending.clear();
    pcmQueue.clear();
  }
}
