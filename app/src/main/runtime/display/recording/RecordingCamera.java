package com.winlator.cmod.runtime.display.recording;

import android.content.Context;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class RecordingCamera {
  private final Context context;
  private final HandlerThread thread = new HandlerThread("RecordingCamera");
  private Handler handler;
  private CameraDevice camera;
  private CameraCaptureSession session;
  private ImageReader reader;
  private MediaRecorder recorder;
  private Surface target;
  private boolean started;
  private volatile boolean openPending;
  private long lastImageNs;
  private int[] pixels;
  private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
  private volatile boolean closing;
  private volatile String failure;
  private final Object bitmapLock = new Object();
  private Bitmap bitmap;
  private int sensorRotation;
  private File file;
  private final CountDownLatch ready = new CountDownLatch(1);

  public RecordingCamera(Context context) {
    this.context = context.getApplicationContext();
  }

  public String getFailure() {
    return failure;
  }

  public void start(boolean separate, File output, int displayRotation) throws Exception {
    thread.start();
    handler = new Handler(thread.getLooper());
    CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    String id = null;
    CameraCharacteristics properties = null;
    for (String candidate : manager.getCameraIdList()) {
      CameraCharacteristics c = manager.getCameraCharacteristics(candidate);
      Integer facing = c.get(CameraCharacteristics.LENS_FACING);
      if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
        id = candidate;
        properties = c;
        break;
      }
    }
    if (id == null) throw new IllegalStateException("No front camera available");
    Integer orientation = properties.get(CameraCharacteristics.SENSOR_ORIENTATION);
    sensorRotation = ((orientation == null ? 0 : orientation) + displayRotation) % 360;
    StreamConfigurationMap map =
        properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    if (map == null) throw new IllegalStateException("Camera formats unavailable");
    Size[] sizes =
        separate
            ? map.getOutputSizes(MediaRecorder.class)
            : map.getOutputSizes(ImageFormat.YUV_420_888);
    if (sizes == null || sizes.length == 0)
      throw new IllegalStateException("Camera output unavailable");
    int maxArea = separate ? 640 * 480 : 320 * 240;
    Size size =
        Arrays.stream(sizes)
            .filter(s -> s.getWidth() * s.getHeight() <= maxArea)
            .max(Comparator.comparingInt(s -> s.getWidth() * s.getHeight()))
            .orElseGet(
                () ->
                    Arrays.stream(sizes)
                        .min(Comparator.comparingInt(s -> s.getWidth() * s.getHeight()))
                        .get());
    if (size.getWidth() * size.getHeight() > 1280 * 720)
      throw new IllegalStateException("No lightweight camera output available");
    Range<Integer>[] ranges =
        properties.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
    Range<Integer> rate =
        ranges == null
            ? null
            : Arrays.stream(ranges)
                .filter(r -> r.getUpper() <= 30)
                .min(
                    Comparator.comparingInt(
                        r -> Math.abs(r.getUpper() - (separate ? 30 : 15)) * 100 + r.getLower()))
                .orElse(null);
    if (separate) {
      file = output;
      recorder = new MediaRecorder();
      recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
      recorder.setVideoSize(size.getWidth(), size.getHeight());
      recorder.setVideoFrameRate(rate == null ? 30 : rate.getUpper());
      recorder.setVideoEncodingBitRate(1500000);
      recorder.setOrientationHint(sensorRotation);
      recorder.setOutputFile(file.getPath());
      recorder.prepare();
      recorder.setOnErrorListener((mr, what, extra) -> failure = "Camera encoder failed");
      target = recorder.getSurface();
    } else {
      reader =
          ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
      reader.setOnImageAvailableListener(this::readImage, handler);
      target = reader.getSurface();
    }
    openPending = true;
    try {
      manager.openCamera(
          id,
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
              camera = device;
              openPending = false;
              if (closing) {
                device.close();
                ready.countDown();
                return;
              }
              try {
                device.createCaptureSession(
                    Collections.singletonList(target),
                    new CameraCaptureSession.StateCallback() {
                      @Override
                      public void onConfigured(CameraCaptureSession configured) {
                        session = configured;
                        if (closing) {
                          configured.close();
                          ready.countDown();
                          return;
                        }
                        try {
                          CaptureRequest.Builder request =
                              device.createCaptureRequest(
                                  separate
                                      ? CameraDevice.TEMPLATE_RECORD
                                      : CameraDevice.TEMPLATE_PREVIEW);
                          request.addTarget(target);
                          if (rate != null)
                            request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, rate);
                          configured.setRepeatingRequest(request.build(), null, handler);
                          if (recorder != null) {
                            recorder.start();
                            started = true;
                          }
                        } catch (Exception e) {
                          failure = "Camera capture failed: " + e.getMessage();
                        }
                        ready.countDown();
                      }

                      @Override
                      public void onConfigureFailed(CameraCaptureSession configured) {
                        failure = "Camera configuration failed";
                        ready.countDown();
                      }
                    },
                    handler);
              } catch (Exception e) {
                failure = e.getMessage();
                ready.countDown();
              }
            }

            @Override
            public void onDisconnected(CameraDevice device) {
              failure = "Camera disconnected";
              device.close();
              openPending = false;
              ready.countDown();
              if (closing) thread.quitSafely();
            }

            @Override
            public void onError(CameraDevice device, int error) {
              failure = "Camera unavailable (" + error + ")";
              device.close();
              openPending = false;
              ready.countDown();
              if (closing) thread.quitSafely();
            }

            @Override
            public void onClosed(CameraDevice device) {
              if (closing) thread.quitSafely();
            }
          },
          handler);
    } catch (Exception e) {
      openPending = false;
      throw e;
    }
    if (!ready.await(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Camera startup timed out");
    if (failure != null) throw new IllegalStateException(failure);
  }

  private void readImage(ImageReader source) {
    try (Image image = source.acquireLatestImage()) {
      if (image == null || closing) return;
      long now = System.nanoTime();
      if (now - lastImageNs < 66666666L) return;
      lastImageNs = now;
      int w = image.getWidth(), h = image.getHeight();
      if (pixels == null) pixels = new int[w * h];
      Image.Plane[] planes = image.getPlanes();
      ByteBuffer y = planes[0].getBuffer(), u = planes[1].getBuffer(), v = planes[2].getBuffer();
      for (int row = 0; row < h; row++) {
        for (int col = 0; col < w; col++) {
          int yy =
              (y.get(
                          y.position()
                              + row * planes[0].getRowStride()
                              + col * planes[0].getPixelStride())
                      & 255)
                  - 16;
          int uu =
              (u.get(
                          u.position()
                              + row / 2 * planes[1].getRowStride()
                              + col / 2 * planes[1].getPixelStride())
                      & 255)
                  - 128;
          int vv =
              (v.get(
                          v.position()
                              + row / 2 * planes[2].getRowStride()
                              + col / 2 * planes[2].getPixelStride())
                      & 255)
                  - 128;
          int r = Math.max(0, Math.min(255, (298 * yy + 409 * vv + 128) >> 8));
          int g = Math.max(0, Math.min(255, (298 * yy - 100 * uu - 208 * vv + 128) >> 8));
          int b = Math.max(0, Math.min(255, (298 * yy + 516 * uu + 128) >> 8));
          pixels[row * w + col] = 0xff000000 | r << 16 | g << 8 | b;
        }
      }
      synchronized (bitmapLock) {
        if (bitmap == null) bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
      }
    } catch (Exception e) {
      if (!closing) failure = "Camera frame failed: " + e.getMessage();
    }
  }

  public void draw(Canvas canvas, int width, int height, int corner, boolean circle) {
    synchronized (bitmapLock) {
      if (bitmap == null) return;
      float side = Math.min(width, height) * 0.25f;
      float margin = Math.min(width, height) * 0.025f;
      float x = corner == 0 || corner == 2 ? margin : width - side - margin;
      float y = corner < 2 ? margin : height - side - margin;
      int save = canvas.save();
      RectF box = new RectF(x, y, x + side, y + side);
      if (circle) {
        Path path = new Path();
        path.addOval(box, Path.Direction.CW);
        canvas.clipPath(path);
      } else canvas.clipRect(box);
      canvas.translate(x + side / 2, y + side / 2);
      canvas.scale(-1, 1);
      canvas.rotate(sensorRotation);
      float scale = side / Math.min(bitmap.getWidth(), bitmap.getHeight());
      canvas.scale(scale, scale);
      canvas.drawBitmap(bitmap, -bitmap.getWidth() / 2f, -bitmap.getHeight() / 2f, paint);
      canvas.restoreToCount(save);
    }
  }

  public void stop() {
    closing = true;
    if (handler == null) return;
    CountDownLatch done = new CountDownLatch(1);
    Runnable cleanup =
        () -> {
          try {
            try {
              if (session != null) session.close();
            } catch (Exception e) {
              failure = "Camera session close failed";
            }
            try {
              if (camera != null) camera.close();
            } catch (Exception e) {
              failure = "Camera close failed";
            }
            boolean valid = started;
            if (recorder != null) {
              try {
                if (started) recorder.stop();
              } catch (Exception e) {
                valid = false;
                failure = "Could not save camera video";
              }
              try {
                recorder.release();
              } catch (Exception e) {
                failure = "Camera encoder release failed";
              }
              recorder = null;
            }
            if (reader != null) {
              reader.close();
              reader = null;
            } else if (target != null) target.release();
            if (file != null) {
              if (valid)
                MediaScannerConnection.scanFile(
                    context, new String[] {file.getPath()}, new String[] {"video/mp4"}, null);
              else file.delete();
            }
            synchronized (bitmapLock) {
              if (bitmap != null) bitmap.recycle();
              bitmap = null;
            }
          } finally {
            done.countDown();
          }
        };
    if (!handler.post(cleanup)) cleanup.run();
    boolean interrupted = false;
    while (true) {
      try {
        done.await();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (!openPending && camera == null) thread.quitSafely();
    if (interrupted) Thread.currentThread().interrupt();
  }
}
