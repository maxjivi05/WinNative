package com.winlator.cmod.runtime.wine;

import android.content.Context;
import android.util.Log;
import com.winlator.cmod.runtime.container.Container;
import com.winlator.cmod.runtime.display.environment.ImageFs;
import com.winlator.cmod.shared.io.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class EaClientInstaller {
  private EaClientInstaller() {}

  private static final String TAG = "EaClientInstaller";
  private static final String INSTALLER_RELATIVE =
      "__Installer/Origin/redist/internal/EAappInstaller.exe";
  private static final String EA_ROOT_RELATIVE =
      ".wine/drive_c/Program Files/Electronic Arts/EA Desktop";
  private static final String MSI_NAME = "wn-ea-install.msi";
  private static final String SCRIPT_NAME = "wn-ea-install.cmd";
  private static final long MIN_MSI_BYTES = 50L * 1024L * 1024L;

  public static boolean isClientInstalled(Container container) {
    return new File(container.getRootDir(), EA_ROOT_RELATIVE).isDirectory();
  }

  public static boolean stage(Context context, Container container, File gameDir) {
    if (context == null || container == null || gameDir == null) return false;
    if (isClientInstalled(container)) return false;
    File installer = new File(gameDir, INSTALLER_RELATIVE);
    if (!installer.isFile()) return false;

    File driveC = new File(container.getRootDir(), ".wine/drive_c");
    File msi = new File(driveC, MSI_NAME);
    File script = new File(driveC, SCRIPT_NAME);
    if (msi.isFile() && script.isFile() && msi.length() > MIN_MSI_BYTES) return true;

    File work = new File(driveC, "wn-ea-extract");
    try {
      FileUtils.delete(work);
      if (!work.mkdirs()) return false;
      File cab = new File(work, "payload.cab");
      if (!carvePayloadCab(installer, cab)) {
        Log.w(TAG, "no payload CAB found in " + installer.getAbsolutePath());
        return false;
      }
      if (!runCabextract(context, cab, work)) return false;
      File extracted = findLargestMsi(work, cab);
      if (extracted == null) {
        Log.w(TAG, "cabextract produced no MSI-signature payload");
        return false;
      }
      if (msi.isFile() && !FileUtils.delete(msi)) return false;
      if (!extracted.renameTo(msi)) return false;
      writeInstallScript(script);
      Log.i(TAG, "staged EA client installer (" + msi.length() + " bytes) from " + installer.getName());
      return true;
    } catch (Exception e) {
      Log.w(TAG, "staging the EA client installer failed", e);
      return false;
    } finally {
      FileUtils.delete(work);
    }
  }

  private static boolean carvePayloadCab(File installer, File out) throws IOException {
    long length = installer.length();
    try (RandomAccessFile raf = new RandomAccessFile(installer, "r")) {
      byte[] window = new byte[1 << 20];
      long base = 0;
      while (base < length) {
        raf.seek(base);
        int n = raf.read(window);
        if (n <= 0) break;
        for (int i = 0; i + 8 <= n; i++) {
          if (window[i] != 'M' || window[i + 1] != 'S' || window[i + 2] != 'C' || window[i + 3] != 'F') {
            continue;
          }
          long offset = base + i;
          raf.seek(offset + 8);
          long size = readUint32(raf);
          if (size < MIN_MSI_BYTES || offset + size > length) continue;
          return copyRange(raf, offset, size, out);
        }
        base += n - 16;
      }
    }
    return false;
  }

  private static long readUint32(RandomAccessFile raf) throws IOException {
    byte[] b = new byte[4];
    raf.readFully(b);
    return (b[0] & 0xFFL) | ((b[1] & 0xFFL) << 8) | ((b[2] & 0xFFL) << 16) | ((b[3] & 0xFFL) << 24);
  }

  private static boolean copyRange(RandomAccessFile raf, long offset, long size, File out)
      throws IOException {
    raf.seek(offset);
    try (FileOutputStream fos = new FileOutputStream(out)) {
      byte[] buf = new byte[1 << 20];
      long remaining = size;
      while (remaining > 0) {
        int want = (int) Math.min(buf.length, remaining);
        int n = raf.read(buf, 0, want);
        if (n <= 0) break;
        fos.write(buf, 0, n);
        remaining -= n;
      }
      return remaining == 0;
    }
  }

  private static boolean runCabextract(Context context, File cab, File destDir) {
    File rootDir = ImageFs.find(context).getRootDir();
    File cabextract = new File(rootDir, "usr/bin/cabextract");
    if (!cabextract.isFile()) {
      Log.w(TAG, "cabextract is missing from the system image");
      return false;
    }
    FileUtils.chmod(cabextract, 0755);
    List<String> args = new ArrayList<>();
    args.add(cabextract.getAbsolutePath());
    args.add("-q");
    args.add("-d");
    args.add(destDir.getAbsolutePath());
    args.add(cab.getAbsolutePath());
    try {
      ProcessBuilder pb = new ProcessBuilder(args);
      pb.environment().put(
          "LD_LIBRARY_PATH", new File(rootDir, "usr/lib").getAbsolutePath() + ":/system/lib64");
      File devNull = new File("/dev/null");
      pb.redirectOutput(ProcessBuilder.Redirect.to(devNull));
      pb.redirectError(ProcessBuilder.Redirect.to(devNull));
      Process proc = pb.start();
      if (!proc.waitFor(10, TimeUnit.MINUTES)) {
        proc.destroyForcibly();
        return false;
      }
      return proc.exitValue() == 0;
    } catch (Exception e) {
      Log.w(TAG, "cabextract failed", e);
      return false;
    }
  }

  private static File findLargestMsi(File dir, File exclude) {
    File[] entries = dir.listFiles();
    if (entries == null) return null;
    File best = null;
    for (File entry : entries) {
      if (entry.isDirectory()) {
        File nested = findLargestMsi(entry, exclude);
        if (nested != null && (best == null || nested.length() > best.length())) best = nested;
        continue;
      }
      if (entry.equals(exclude) || entry.length() < MIN_MSI_BYTES) continue;
      if (!hasOleSignature(entry)) continue;
      if (best == null || entry.length() > best.length()) best = entry;
    }
    return best;
  }

  private static boolean hasOleSignature(File file) {
    try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
      byte[] sig = new byte[4];
      raf.readFully(sig);
      return (sig[0] & 0xFF) == 0xD0 && (sig[1] & 0xFF) == 0xCF
          && (sig[2] & 0xFF) == 0x11 && (sig[3] & 0xFF) == 0xE0;
    } catch (Exception e) {
      return false;
    }
  }

  private static void writeInstallScript(File script) {
    String body =
        "@echo off\r\n"
            + "if exist \"C:\\Program Files\\Electronic Arts\\EA Desktop\\EA Desktop\\EADesktop.exe\" exit /b 0\r\n"
            + "\"C:\\windows\\system32\\msiexec.exe\" /a \"C:\\" + MSI_NAME
            + "\" TARGETDIR=\"C:\\wn-ea-admin\" /qn\r\n"
            + "if not exist \"C:\\wn-ea-admin\\Electronic Arts\" exit /b 1\r\n"
            + "xcopy /E /I /Y \"C:\\wn-ea-admin\\Electronic Arts\" \"C:\\Program Files\\Electronic Arts\" >nul\r\n"
            + "reg add \"HKLM\\SOFTWARE\\Electronic Arts\\EA Desktop\" /v InstallSuccessful /t REG_SZ /d true /f >nul 2>&1\r\n"
            + "reg add \"HKLM\\SOFTWARE\\WOW6432Node\\Electronic Arts\\EA Desktop\" /v InstallSuccessful /t REG_SZ /d true /f >nul 2>&1\r\n"
            + "exit /b 0\r\n";
    FileUtils.writeString(script, body);
  }
}
