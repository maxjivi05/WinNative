# Container shutdown hang

On the connected device, Wine had stopped but the display activity remained on “Closing container.” A Java debugger stack showed `XServerExitCleanup` waiting in `XConnectorEpoll.stop`, the connector waiting in `killConnection`, and client threads blocked in `ClientSocket.recvAncillaryMsg`.

The native `waitForSocketRead` invoked `handleExistingConnection`, then the Java loop invoked it a second time. A single packet could therefore leave the client blocked on another read. The apparent five-second timeout also called unbounded `join()`. Native epoll instances additionally shared one global event array.

The fix makes readiness polling report readiness without dispatching the request. Each epoll call has its own event array. Shutdown signals the client and calls `shutdown(SHUT_RDWR)` to wake blocked socket IO before joining its thread. Only the cleanup owner releases buffers and descriptors, after the worker has finished. Shutdown preserves interrupt status, publishes the running flag across threads, registers clients before starting their workers, and protects shutdown descriptors against concurrent closure.

Validation:

- All 189 JVM tests pass; the PUBG APK and instrumentation APK build.
- Five on-device native connector tests pass: idle clients, a single request without a second packet, delayed handlers retaining their buffers until completion, twelve peer-close/shutdown races, and four independent connectors mixing single-threaded and multithreaded dispatch.
- A real Battle.net session returned from Exit to UnifiedActivity in 4.22 seconds, leaving no wineserver process. No force-stop was used for that validation.
- The APK was signed with the existing WinNative key and installed without clearing application data.

This fixes container teardown. The official Battle.net client still displayed incomplete/blank content during the short launch test; full game installation and authenticated launching remain separate outstanding work.
