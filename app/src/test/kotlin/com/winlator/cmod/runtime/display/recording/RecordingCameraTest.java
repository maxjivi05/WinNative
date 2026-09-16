package com.winlator.cmod.runtime.display.recording;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RecordingCameraTest {
  private RecordingCamera camera() throws Exception {
    RecordingCamera camera = new RecordingCamera(RuntimeEnvironment.getApplication());
    Bitmap frame = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
    frame.eraseColor(Color.RED);
    Field bitmap = RecordingCamera.class.getDeclaredField("bitmap");
    bitmap.setAccessible(true);
    bitmap.set(camera, frame);
    return camera;
  }

  @Test
  public void squareAppearsInEachRequestedCorner() throws Exception {
    RecordingCamera camera = camera();
    for (int corner = 0; corner < 4; corner++) {
      Bitmap output = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
      camera.draw(new Canvas(output), 1280, 720, corner, false);
      int x = corner % 2 == 0 ? 20 : 1100;
      int y = corner < 2 ? 20 : 540;
      assertEquals(Color.RED, output.getPixel(x, y));
      assertEquals(Color.TRANSPARENT, output.getPixel(640, 360));
      output.recycle();
    }
  }

  @Test
  public void circularOverlayClipsTheSquareCorners() throws Exception {
    Bitmap output = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
    camera().draw(new Canvas(output), 1280, 720, 0, true);
    assertEquals(Color.TRANSPARENT, output.getPixel(20, 20));
    assertEquals(Color.RED, output.getPixel(108, 108));
  }
}
