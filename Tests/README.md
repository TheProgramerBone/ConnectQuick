# Cross-platform integration tests

Real processes, real sockets: the **actual Backend** (`node src/server.ts`), the **actual Windows Host runtime** (`HostHarness`: the same
`HostRuntime` the tray app runs, with in-memory OS adapters so it can run anywhere) and the **actual Android controller core** (`ControllerHarness`: the same
Kotlin code the app uses, on a plain JVM) — each its own process, driven over JSON lines by `node:test`. What these prove: pairing by QR, the end-to-end handshake
and data layer, every feature (input, clipboard, media, files, power, screen signalling), permissions and the PC's switches, hostile inputs, and resilience (PC crash,
backend restart, network change, silent partition, revoked device, duplicate identity). What they do **not** prove: anything that needs a real Windows desktop or a real phone
(Win32 effects, GDI capture, VP8, libwebrtc, the UIs).

## Run

```sh
# 1. build the harnesses once (needs .NET 8 SDK, JDK 17; Kotlin via Gradle)
dotnet build ../WindowsHost/src/ConnectQuick.WindowsHost.HostHarness
cd ../Android && ./gradlew -p controller-core testClasses printHarnessClasspath -q      # prints the controller classpath
# 2. run
cd ../Tests && npm install
CQ_CONTROLLER_CP="<classpath printed above>" npm test        # CQ_HOST_HARNESS=<path to HostHarness dll> overrides the default location
```

In the development sandbox (no Gradle plugin access) the controller classes were built with `kotlinc` directly and `CQ_CONTROLLER_CP` pointed at them; the tests are identical.
Run suites one at a time (`--test-concurrency=1`, already the default in `npm test`): the scenarios start several processes each and are timing-sensitive on a loaded machine.
