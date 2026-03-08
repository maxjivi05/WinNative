import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class PeChecker {
    public static boolean is64BitWindowsExecutable(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            // Read DOS Header (first 64 bytes)
            byte[] dosHeader = new byte[64];
            if (fis.read(dosHeader) != 64) return false;
            // Check 'MZ' signature
            if (dosHeader[0] != 'M' || dosHeader[1] != 'Z') return false;

            // Offset to PE Header is at 0x3C
            int peOffset = (dosHeader[60] & 0xFF) | ((dosHeader[61] & 0xFF) << 8) |
                           ((dosHeader[62] & 0xFF) << 16) | ((dosHeader[63] & 0xFF) << 24);

            // Skip to PE Header
            fis.getChannel().position(peOffset);
            byte[] peHeader = new byte[24];
            if (fis.read(peHeader) != 24) return false;

            // Check 'PE\0\0' signature
            if (peHeader[0] != 'P' || peHeader[1] != 'E' || peHeader[2] != 0 || peHeader[3] != 0) return false;

            // Read Machine field (offset 4)
            int machine = (peHeader[4] & 0xFF) | ((peHeader[5] & 0xFF) << 8);

            // 0x8664 is AMD64, 0xAA64 is ARM64, 0x014C is i386
            return machine == 0x8664 || machine == 0xAA64;
        } catch (IOException e) {
            return false;
        }
    }
    public static void main(String[] args) {
        System.out.println("Compiles and runs!");
    }
}
