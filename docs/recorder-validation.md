# Recorder changes and validation

## Capture behavior

The in-game Record dialog retains its FPS, resolution, bitrate-quality and Record UI controls. It adds microphone mixing and front-camera modes: off, overlay, or a separate MP4. Overlay placement supports all four corners and square/circle clipping. Camera video is mirrored in the overlay; a separate camera MP4 uses sensor/display orientation metadata. Microphone and game audio are in the main recording. Permissions are requested only for selected inputs. New recording labels currently use English fallback; the new resource file limits its missing-translation suppression to those labels.

The encoder mirrors the game's Vulkan compositor, including when its SurfaceView is on an external display. FPS choices use that display's refresh rate. Hardware AVC encoders are checked for aligned size/rate/bitrate support; unsupported requests fall back to lower settings, and the start notification reports the actual settings. Software game-video encoding is excluded. Quality controls bitrate, not game render resolution. New settings default to 30 fps, Balance and at most 1080p; existing selections are retained. Resolution preferences now store the short-side size rather than reusing a tier index across different displays.

A display detach, incompatible size/rotation change, capture failure, or leaving the foreground ends the clip. The user can start a new clip after the new display settles. This avoids mixing orientations or stretching frames in one MP4. PiP retains recording while the activity remains visible. Recorded overlays use the phone controls and camera coordinates mapped into the recorded frame.

Game frames remain demand-driven. Capture uses a zero-timeout encoder-image acquire and drops busy captures instead of adding a 16 ms wait to game rendering. Overlay snapshots are capped at 1280 pixels on the long side and uploaded away from the UI thread, with only one upload outstanding. Camera overlays prefer 320×240 and process at most 15 frames/sec; separate camera output prefers at most 640×480. Actual camera sizes/rates depend on the advertised outputs. These bounds reduce overhead; they are not hardware performance measurements.

## Bugs addressed

- Removed the synchronized stop/join path that prevented the drain thread from acquiring the same lock. Only the encoder worker touches active codecs and the muxer; the control worker detaches Vulkan before signaling EOS and waiting for finalization.
- Configured AAC before recording instead of permanently excluding game audio after a one-second grace period. Silent intervals retain their duration, and late audio can enter the existing track.
- Bounded PCM and startup packet buffering. Each source has its own timeline; dropped data does not shorten the audio clock. PCM conversion handles 8-bit, 16-bit, float, endianness, channel reduction and rate changes.
- Added output-only AAudio callback taps for DirectAudio and PulseAudio, which bypass the ALSA Java tap. Callback delivery is nonblocking, allocates no buffers, and cannot fill an unbounded queue. The local receiver accepts only the app UID. Recording-disabled retries are rate-limited. Microphone input uses a separate nonblocking AudioRecord reader.
- Retained muxer/codec ownership through EOS. Empty or unfinalizable outputs are deleted; partial but finalized clips can survive an input failure, with an error notification. The saved notification includes the actual path, including app-storage fallback.
- Guarded native recording operations against renderer destruction, checked Vulkan blit/overlay capabilities, and made overlay failures visible. Unsupported selected features fail explicitly instead of silently disappearing.
- Serialized camera callbacks and cleanup, including pending-open cancellation. Camera absence, permission denial, disconnection and encoder errors are reported.

## Automated validation

- Passed `:app:assembleStandardDebug`: Java/Kotlin compilation, ARM64 native compilation and debug APK packaging.
- Passed 9 tests with `:app:testStandardDebugUnitTest --tests 'com.winlator.cmod.runtime.display.recording.*'`: audio timing/conversion/mixing and rendered overlay corner/circle tests.
- Project-wide Android lint was also run. It reports 167 errors and 2655 warnings, with no errors on changed lines. It is blocked by existing errors outside this change (including `InputControlsView` resource typing, API-level calls and existing translations); it is not a clean lint baseline.
- Passed the native callback test with AddressSanitizer and UndefinedBehaviorSanitizer:

```sh
cc -std=gnu11 -Wall -Wextra -Werror -fsanitize=address,undefined -g -pthread \
  app/src/test/cpp/recording_audio_tap_test.c \
  app/src/main/cpp/wnaudiohook/recording_stream.c \
  -o /tmp/recording_audio_tap_test
/tmp/recording_audio_tap_test
```

The native test covers independent callbacks from a reused builder, chunking, full receiver queues, stream cleanup, and excluding input streams.

## Device validation still required

Builds and host tests cannot establish thermal behavior, driver-specific Vulkan/codec interoperability, camera orientation, microphone routing, or long-session A/V synchronization on physical hardware. The following checks must be performed on representative devices before claiming end-to-end hardware validation:

- Record gameplay using ALSA, PulseAudio and DirectAudio, including silence followed by sound, multiple sound streams, headset changes and microphone on/off.
- Test phone-only, external-display-only, mirrored output, display connect/disconnect, resolution/refresh changes and rotation. Confirm the completed clip is playable and a subsequent recording uses the new display correctly.
- Test each quality/resolution/FPS tier and hardware fallback; compare game frame times, memory and thermals with recording off/on for at least 15 minutes.
- Check camera overlay at every corner in both shapes, separately saved camera playback, permission denial, another app holding the camera/microphone, rapid start/stop, background/PiP and exiting the game during startup/finalization.
- Inspect MP4 video/audio tracks, timestamps and final durations; listen for mic/game balance, clipping, echo and drift. Exercise low-storage/write failures and repeat recording after failures.

## API references

- [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec): surface input, output-format changes, EOS and buffer ownership.
- [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer): adding tracks before start and finalizing MP4 output.
- [MediaCodecInfo](https://developer.android.com/reference/android/media/MediaCodecInfo): hardware classification and codec capabilities.
- [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord): nonblocking reads and runtime recording permissions.
- [CameraDevice](https://developer.android.com/reference/android/hardware/camera2/CameraDevice): asynchronous session and device lifecycle.
- [Khronos image acquisition](https://docs.vulkan.org/refpages/latest/refpages/source/vkAcquireNextImageKHR.html): zero-timeout acquire returns without waiting for an available image.
- [Khronos semaphore reuse](https://docs.vulkan.org/guide/latest/swapchain_semaphore_reuse.html): presentation semaphores remain tied to acquired image indices.
