package com.winlator.cmod.runtime.display.xserver.extensions;

import static com.winlator.cmod.runtime.display.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.runtime.display.connector.XInputStream;
import com.winlator.cmod.runtime.display.connector.XOutputStream;
import com.winlator.cmod.runtime.display.connector.XStreamLock;
import com.winlator.cmod.runtime.display.xserver.ScreenInfo;
import com.winlator.cmod.runtime.display.xserver.XClient;
import com.winlator.cmod.runtime.display.xserver.errors.BadImplementation;
import com.winlator.cmod.runtime.display.xserver.errors.XRequestError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Static, single-output RandR 1.3 emulation following the same model as
 * `XWayland --force-xrandr-emulation`: one CRTC, one Output, one Mode at
 * the active screen size, no real mode-set capability. This is the
 * minimum the Linux Steam ARM64 client (and most Wine titles) require to
 * complete startup; without it Steam dies right after "Verification
 * complete" on `XSyncInitialize`/`RRGetScreenResources`.
 *
 * Why RandR 1.3 and not 1.5 — 1.4 added providers (RRGetProviders /
 * RRGetProviderInfo / RRSetProviderOutputSource / RRSetProviderOffloadSink)
 * and 1.5 added monitors (RRGetMonitors / RRSetMonitor / RRDeleteMonitor).
 * We implement none of those, so advertising a higher version would
 * invite Wine's xrandr14 settings handler in winex11.drv to call into
 * unimplemented opcodes. Reporting 1.3 keeps Wine on the legacy 1.0
 * settings path which only needs opcodes 0/2/4/5/6 — and we cover 2
 * (RRSetScreenConfig, no-op success) and 5 (RRGetScreenInfo, single
 * size + single rate) explicitly so Wine fullscreen mode-set requests
 * don't error out on existing Wine games.
 *
 * Wire formats sourced from xorgproto's `randrproto.h`. All multi-byte
 * fields are little-endian per the X11 protocol once the connection has
 * negotiated little-endian byte order (the default on aarch64).
 */
public class RandRExtension implements Extension {

  /**
   * Major opcode in the negative range used by {@code XServer.extensions}
   * to slot extension dispatchers; {@code -90} is well clear of the
   * other extensions ({@code -100}..{@code -104}). When written to the
   * wire as an unsigned byte it becomes {@code 166} — clients use the
   * value the server returns from {@code QueryExtension}, so the exact
   * number here doesn't have to match upstream Xorg's RandR opcode.
   */
  public static final byte MAJOR_OPCODE = -90;

  // RandR 1.3
  private static final int SERVER_MAJOR = 1;
  private static final int SERVER_MINOR = 3;

  // Static resource IDs picked from a high range to avoid colliding with
  // any client-allocated XIDs the X server hands out at runtime.
  private static final int OUTPUT_ID = 0x70000001;
  private static final int CRTC_ID = 0x70000002;
  private static final int MODE_ID = 0x70000003;

  private static final String OUTPUT_NAME = "WinNative-0";
  private static final String MODE_NAME = "WinNative-mode";

  // Rotation flags from randrproto.h (Rotate_0 = 1).
  private static final short ROTATE_0 = 1;

  // RR_Connected = 0 / RR_Disconnected = 1 / RR_UnknownConnection = 2.
  private static final byte RR_CONNECTED = 0;
  // SubPixelOrder: SubPixelUnknown = 0.
  private static final byte SUBPIXEL_UNKNOWN = 0;

  // SetScreenConfig status: 0 = Success.
  private static final byte CONFIG_STATUS_SUCCESS = 0;

  /** A constant timestamp; we never advertise screen changes anyway. */
  private static final int TIMESTAMP = 0;

  private abstract static class ClientOpcodes {
    private static final byte QUERY_VERSION = 0;
    private static final byte SET_SCREEN_CONFIG = 2;
    private static final byte SELECT_INPUT = 4;
    private static final byte GET_SCREEN_INFO = 5;
    private static final byte GET_SCREEN_SIZE_RANGE = 6;
    private static final byte GET_SCREEN_RESOURCES = 8;
    private static final byte GET_OUTPUT_INFO = 9;
    /**
     * Steam Linux ARM64 (and toolkit code in CEF/SDL) walks the
     * per-output property atom list at startup. We don't expose any
     * properties, so the right answer is an empty atom list.
     */
    private static final byte LIST_OUTPUT_PROPERTIES = 10;
    private static final byte GET_CRTC_INFO = 20;
    private static final byte GET_SCREEN_RESOURCES_CURRENT = 25;
    /**
     * Per-CRTC pixel transform query. Steam asks even when nothing's
     * rotated/scaled. The right answer for "no transform" is the 3x3
     * identity matrix (1.0 = 0x10000 in 16.16 fixed) for both pending
     * and current transforms with `hasTransforms = 0` and empty filters.
     */
    private static final byte GET_CRTC_TRANSFORM = 27;
    private static final byte GET_OUTPUT_PRIMARY = 31;
  }

  @Override public String getName() { return "RANDR"; }
  @Override public byte getMajorOpcode() { return MAJOR_OPCODE; }
  @Override public byte getFirstErrorId() { return 0; }
  @Override public byte getFirstEventId() { return 0; }

  @Override
  public void handleRequest(XClient client, XInputStream in, XOutputStream out)
      throws IOException, XRequestError {
    int opcode = client.getRequestData();
    switch (opcode) {
      case ClientOpcodes.QUERY_VERSION:
        queryVersion(client, in, out);
        break;
      case ClientOpcodes.SET_SCREEN_CONFIG:
        setScreenConfig(client, in, out);
        break;
      case ClientOpcodes.SELECT_INPUT:
        selectInput(client, in, out);
        break;
      case ClientOpcodes.GET_SCREEN_INFO:
        getScreenInfo(client, in, out);
        break;
      case ClientOpcodes.GET_SCREEN_SIZE_RANGE:
        getScreenSizeRange(client, in, out);
        break;
      case ClientOpcodes.GET_SCREEN_RESOURCES:
      case ClientOpcodes.GET_SCREEN_RESOURCES_CURRENT:
        getScreenResources(client, in, out);
        break;
      case ClientOpcodes.GET_OUTPUT_INFO:
        getOutputInfo(client, in, out);
        break;
      case ClientOpcodes.LIST_OUTPUT_PROPERTIES:
        listOutputProperties(client, in, out);
        break;
      case ClientOpcodes.GET_CRTC_INFO:
        getCrtcInfo(client, in, out);
        break;
      case ClientOpcodes.GET_CRTC_TRANSFORM:
        getCrtcTransform(client, in, out);
        break;
      case ClientOpcodes.GET_OUTPUT_PRIMARY:
        getOutputPrimary(client, in, out);
        break;
      default:
        throw new BadImplementation();
    }
  }

  /**
   * Per the RandR spec, the server returns the highest version it
   * supports but never above what the client requested. Older clients
   * that ask for 1.1 must see 1.1 back, otherwise they may try to call
   * features we don't implement.
   */
  private void queryVersion(XClient client, XInputStream in, XOutputStream out)
      throws IOException {
    int clientMajor = in.readInt();
    int clientMinor = in.readInt();

    int respMajor = SERVER_MAJOR;
    int respMinor = SERVER_MINOR;
    if (clientMajor < SERVER_MAJOR ||
        (clientMajor == SERVER_MAJOR && clientMinor < SERVER_MINOR)) {
      respMajor = clientMajor;
      respMinor = clientMinor;
    }

    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte((byte) 0);
      out.writeShort(client.getSequenceNumber());
      out.writeInt(0);
      out.writeInt(respMajor);
      out.writeInt(respMinor);
      out.writePad(16);
    }
  }

  /**
   * Wine's legacy XRandR 1.0 settings handler calls this on a fullscreen
   * mode change. Our screen size never changes (Android decides that),
   * so we always reply success without doing anything. The newTimestamp
   * and root fields are zero/window-passed-through; Wine doesn't depend
   * on them when we say success.
   */
  private void setScreenConfig(XClient client, XInputStream in, XOutputStream out)
      throws IOException {
    // Body: drawable + timestamp + configTimestamp + sizeID + rotation +
    // rate + pad = 20 bytes after header.
    int drawable = in.readInt();
    in.skip(16);

    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte(CONFIG_STATUS_SUCCESS);
      out.writeShort(client.getSequenceNumber());
      out.writeInt(0);
      out.writeInt(TIMESTAMP);
      out.writeInt(TIMESTAMP);
      out.writeInt(drawable);
      out.writePad(12);
    }
  }

  /**
   * Subscribe-to-events. We never fire RandR events (resolution doesn't
   * change at runtime here), so this is a fire-and-forget request with
   * no reply — matching the protocol definition where SelectInput is
   * void.
   */
  private void selectInput(XClient client, XInputStream in, XOutputStream out)
      throws IOException {
    in.skip(8); // window(4) + enable(2) + pad(2)
  }

  /**
   * Wine's xrandr10 fallback path uses this to enumerate sizes and the
   * current configuration. We expose one size at the live screen
   * dimensions and a single 60 Hz rate.
   */
  private void getScreenInfo(XClient client, XInputStream in, XOutputStream out)
      throws IOException {
    in.skip(4); // window
    ScreenInfo s = client.xServer.screenInfo;

    // Variable trailer: 1 size struct (8 bytes) + nrateEnts(2 = 1 nRates + 1 rate) * 2 = 4
    // Total reply = 32 (fixed) + 8 + 4 = 44 → length = (44-32)/4 = 3.
    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte((byte) ROTATE_0);              // rotations supported
      out.writeShort(client.getSequenceNumber());
      out.writeInt(3);                              // reply length
      out.writeInt(0);                              // root window (left zero — RandR 1.0 callers don't track this)
      out.writeInt(TIMESTAMP);                      // timestamp
      out.writeInt(TIMESTAMP);                      // configTimestamp
      out.writeShort((short) 1);                    // nSizes
      out.writeShort((short) 0);                    // current sizeID (index into the sizes array)
      out.writeShort(ROTATE_0);                     // current rotation
      out.writeShort((short) 60);                   // current rate
      out.writeShort((short) 2);                    // nrateEnts (1 count entry + 1 rate value)
      out.writeShort((short) 0);                    // pad

      // The single size entry (xScreenSizes, 8 bytes).
      out.writeShort(s.width);
      out.writeShort(s.height);
      out.writeShort(s.getWidthInMillimeters());
      out.writeShort(s.getHeightInMillimeters());

      // Rate descriptor for that size: 1 rate then the rate values.
      out.writeShort((short) 1);                    // 1 rate available
      out.writeShort((short) 60);                   // 60 Hz
    }
  }

  /** Reply with a generous, never-changing size range. */
  private void getScreenSizeRange(XClient client, XInputStream in, XOutputStream out)
      throws IOException {
    in.skip(4); // window
    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte((byte) 0);
      out.writeShort(client.getSequenceNumber());
      out.writeInt(0);
      out.writeShort((short) 1);                    // minWidth
      out.writeShort((short) 1);                    // minHeight
      out.writeShort((short) 16384);                // maxWidth
      out.writeShort((short) 16384);                // maxHeight
      out.writePad(16);
    }
  }

  /**
   * Single CRTC + Output + Mode. Reply layout:
   *   32 bytes fixed header (timestamps, counts, padding)
   *   4 bytes per CRTC ID (×1)
   *   4 bytes per Output ID (×1)
   *   32 bytes per Mode info (×1)
   *   nbytesNames bytes of mode names, then padding to 4-byte boundary
   *
   * For us: 32 + 4 + 4 + 32 + 14_name_bytes_padded_to_16 = 88 bytes.
   * length = (88 - 32) / 4 = 14.
   */
  private void getScreenResources(XClient client, XInputStream in, XOutputStream out)
      throws IOException {
    in.skip(4); // window
    ScreenInfo s = client.xServer.screenInfo;

    byte[] modeNameBytes = MODE_NAME.getBytes(StandardCharsets.US_ASCII);
    int nbytesNames = modeNameBytes.length;
    int namePad = (4 - (nbytesNames & 3)) & 3;

    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte((byte) 0);
      out.writeShort(client.getSequenceNumber());
      out.writeInt(1 + 1 + 8 + (nbytesNames + namePad) / 4); // see header comment
      out.writeInt(TIMESTAMP);
      out.writeInt(TIMESTAMP);
      out.writeShort((short) 1);                    // nCrtcs
      out.writeShort((short) 1);                    // nOutputs
      out.writeShort((short) 1);                    // nModes
      out.writeShort((short) nbytesNames);          // total name bytes (raw, unpadded)
      out.writePad(8);

      out.writeInt(CRTC_ID);
      out.writeInt(OUTPUT_ID);

      // xRRModeInfo (32 bytes). We declare a 60 Hz dot clock by computing
      // pixels-per-frame at full screen size; toolkits that pretty-print
      // rates derive Hz = dotClock / (hTotal * vTotal).
      int dotClock = (int) s.width * s.height * 60;
      out.writeInt(MODE_ID);                        // id
      out.writeShort(s.width);                      // width
      out.writeShort(s.height);                     // height
      out.writeInt(dotClock);                       // dotClock
      out.writeShort((short) 0);                    // hSyncStart
      out.writeShort((short) 0);                    // hSyncEnd
      out.writeShort(s.width);                      // hTotal
      out.writeShort((short) 0);                    // hSkew
      out.writeShort((short) 0);                    // vSyncStart
      out.writeShort((short) 0);                    // vSyncEnd
      out.writeShort(s.height);                     // vTotal
      out.writeShort((short) nbytesNames);          // nameLength
      out.writeInt(0);                              // modeFlags (none)

      out.write(modeNameBytes);
      if (namePad > 0) out.writePad(namePad);
    }
  }

  /**
   * Reply size = 36 fixed + 4 (1 CRTC) + 4 (1 mode) + 0 clones + 12
   * (output name 11 bytes + 1 byte pad) = 56 bytes. length = (56-32)/4 = 6.
   */
  private void getOutputInfo(XClient client, XInputStream in, XOutputStream out)
      throws IOException, XRequestError {
    int requested = in.readInt();
    in.skip(4); // configTimestamp
    if (requested != OUTPUT_ID) throw new BadImplementation();

    ScreenInfo s = client.xServer.screenInfo;
    byte[] outName = OUTPUT_NAME.getBytes(StandardCharsets.US_ASCII);
    int namePad = (4 - (outName.length & 3)) & 3;

    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte((byte) 0);                      // status (Success)
      out.writeShort(client.getSequenceNumber());
      // Fixed reply is 36 bytes (4 bytes past 32-byte minimum). Variable
      // payload is 1 CRTC + 1 Mode + 0 clones + name + name pad.
      int variablePastFixed = 4 /* CRTC */ + 4 /* Mode */ + 0 /* clones */
          + outName.length + namePad;
      // length = (36 - 32 + variablePastFixed) / 4 = 1 + variablePastFixed/4.
      out.writeInt(1 + variablePastFixed / 4);
      out.writeInt(TIMESTAMP);
      out.writeInt(CRTC_ID);
      out.writeInt(s.getWidthInMillimeters());
      out.writeInt(s.getHeightInMillimeters());
      out.writeByte(RR_CONNECTED);
      out.writeByte(SUBPIXEL_UNKNOWN);
      out.writeShort((short) 1);                    // nCrtcs
      out.writeShort((short) 1);                    // nModes
      out.writeShort((short) 0);                    // nPreferred (0 = no preferred index hint)
      out.writeShort((short) 0);                    // nClones
      out.writeShort((short) outName.length);       // nameLength

      // Variable trailer:
      out.writeInt(CRTC_ID);                        // possible CRTC list
      out.writeInt(MODE_ID);                        // mode list
      out.write(outName);
      if (namePad > 0) out.writePad(namePad);
    }
  }

  /**
   * Reply size = 32 fixed + 4 (1 output) + 4 (1 possible output) = 40 bytes.
   * length = (40 - 32) / 4 = 2.
   */
  private void getCrtcInfo(XClient client, XInputStream in, XOutputStream out)
      throws IOException, XRequestError {
    int requested = in.readInt();
    in.skip(4); // configTimestamp
    if (requested != CRTC_ID) throw new BadImplementation();

    ScreenInfo s = client.xServer.screenInfo;
    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte((byte) 0);
      out.writeShort(client.getSequenceNumber());
      out.writeInt(2);
      out.writeInt(TIMESTAMP);
      out.writeShort((short) 0);                    // x
      out.writeShort((short) 0);                    // y
      out.writeShort(s.width);                      // width
      out.writeShort(s.height);                     // height
      out.writeInt(MODE_ID);                        // current mode
      out.writeShort(ROTATE_0);                     // current rotation
      out.writeShort(ROTATE_0);                     // supported rotations (just 0)
      out.writeShort((short) 1);                    // nOutputs
      out.writeShort((short) 1);                    // nPossibleOutputs
      out.writeInt(OUTPUT_ID);                      // outputs[0]
      out.writeInt(OUTPUT_ID);                      // possibleOutputs[0]
    }
  }

  /** RandR 1.3+. We just hardcode our single output as primary. */
  private void getOutputPrimary(XClient client, XInputStream in, XOutputStream out)
      throws IOException {
    in.skip(4); // window
    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte((byte) 0);
      out.writeShort(client.getSequenceNumber());
      out.writeInt(0);
      out.writeInt(OUTPUT_ID);
      out.writePad(20);
    }
  }

  /**
   * Empty property-list reply. Reply layout per randrproto.h:
   *   type(1) pad(1) seq(2) length(4) nAtoms(2) pad(22) = 32 bytes fixed
   *   then nAtoms * CARD32 atom entries (we have 0).
   */
  private void listOutputProperties(XClient client, XInputStream in, XOutputStream out)
      throws IOException, XRequestError {
    int requested = in.readInt();
    if (requested != OUTPUT_ID) throw new BadImplementation();
    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte((byte) 0);
      out.writeShort(client.getSequenceNumber());
      out.writeInt(0);
      out.writeShort((short) 0);   // nAtoms
      out.writePad(22);
    }
  }

  /**
   * Per-CRTC transform query. We never apply scaling or rotation, so we
   * return the identity matrix as both `pendingTransform` and
   * `currentTransform`, with `hasTransforms = 0` (none applied) and no
   * filter names/params.
   *
   * Reply size = 96 bytes fixed (no variable trailer for empty filters).
   * length = (96 - 32) / 4 = 16.
   *
   * Wire layout per `xRRGetCrtcTransformReply`:
   *   type(1) pad(1) seq(2) length(4)
   *   pendingTransform[9 INT32] = 36
   *   hasTransforms(1) pad(1) pad(2)
   *   currentTransform[9 INT32] = 36
   *   pad(4)
   *   pendingNbytesFilter(2) pendingNparamsFilter(2)
   *   currentNbytesFilter(2) currentNparamsFilter(2)
   *
   * Identity matrix in 16.16 fixed-point: diagonal = 0x10000 (= 1.0),
   * everything else = 0.
   */
  private void getCrtcTransform(XClient client, XInputStream in, XOutputStream out)
      throws IOException, XRequestError {
    int requested = in.readInt();
    if (requested != CRTC_ID) throw new BadImplementation();
    try (XStreamLock lock = out.lock()) {
      out.writeByte(RESPONSE_CODE_SUCCESS);
      out.writeByte((byte) 0);
      out.writeShort(client.getSequenceNumber());
      out.writeInt(16);
      writeIdentityTransform(out);    // pendingTransform
      out.writeByte((byte) 0);        // hasTransforms = false
      out.writeByte((byte) 0);
      out.writeShort((short) 0);
      writeIdentityTransform(out);    // currentTransform
      out.writeInt(0);                 // pad4
      out.writeShort((short) 0);       // pendingNbytesFilter
      out.writeShort((short) 0);       // pendingNparamsFilter
      out.writeShort((short) 0);       // currentNbytesFilter
      out.writeShort((short) 0);       // currentNparamsFilter
    }
  }

  /** 9 INT32 in 16.16 fixed: row-major identity matrix. 36 bytes. */
  private static void writeIdentityTransform(XOutputStream out) throws IOException {
    final int ONE = 0x10000; // 1.0 in 16.16 fixed-point
    out.writeInt(ONE); out.writeInt(0);   out.writeInt(0);
    out.writeInt(0);   out.writeInt(ONE); out.writeInt(0);
    out.writeInt(0);   out.writeInt(0);   out.writeInt(ONE);
  }
}
