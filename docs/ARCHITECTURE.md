# Camera architecture

## Non-negotiable rules

1. API 23 is the exact application floor. Java/Kotlin Camera2 is the universal device/session control plane.
2. No device model, manufacturer, SoC, sensor vendor, camera ID, focal length, or lens role may be hardcoded.
3. Startup must prioritize first visible preview. Deep AUX discovery, RAW capability scans, native initialization, OTA networking, and diagnostics happen only after first frame unless the user explicitly requests them.
4. One long-lived camera owner serializes CameraDevice, CameraCaptureSession, ImageReader, request builders, and route generations.
5. Every lens is represented by a canonical optical identity plus one or more routes/profiles. Camera IDs are opaque transport keys, never semantic lens names.
6. All post-processing is detached from the UI and camera-owner threads after bounded RAW copies are made.
7. The only still-photo feature in the initial product is intelligent multi-frame RAW capture producing one merged DNG.

## Universality boundary

The app discovers every route exposed to an ordinary third-party application through public Android camera APIs: public Camera2 IDs on API 23+, logical/physical camera relationships on API 28+, and CameraX interoperability where it can represent the selected public route. Vendor-whitelisted or system-only cameras cannot be made universally accessible without privileged/root/vendor-specific code, so the app does not pretend otherwise or ship brittle vendor hacks.

## Startup path

Valid-cache startup:

`Activity -> stable preview surface -> permission + surface gates -> validate tiny route cache -> open route -> preview session -> first result/frame -> release deep discovery + OTA + diagnostics`

First install uses a bounded metadata-only public-ID seed. Physical relationships and complete stream/RAW enumeration are postponed until a visible preview exists.

## Preview modes

- **API preview**: Camera2 PRIVATE preview is primary. CameraX is a fallback for a normal public route when Camera2 preview cannot be established.
- **RAW preview**: an experimental capability-gated RAW_SENSOR repeating stream rendered by the native renderer. It is shown only when the selected route advertises a usable RAW stream and the session configuration succeeds.

A camera HAL may require a session rebuild for lens changes. On API 23 many devices cannot keep two cameras open concurrently, so the app guarantees non-blocking UI and minimizes the hardware gap rather than claiming physically impossible zero-gap switching.

## Multi-frame DNG pipeline

Capture keeps the visible repeating stream active while RAW targets are captured. Image/result pairing is by `SENSOR_TIMESTAMP`. RAW planes are copied promptly into bounded direct buffers, Android `Image` objects are closed, and all expensive work moves to a dedicated native-processing executor.

The native merge path is CFA-preserving: even-pixel alignment, exposure/ISO normalization, black/white-level handling, sharpness/motion rejection, saturation/noise weighting, and robust outlier rejection. The merged packed RAW mosaic is written through Android `DngCreator.writeByteBuffer`, using a real capture result for DNG metadata. No JPEG/HEIF intermediate is allowed.

The first implementation is deliberately bounded and testable; future merge upgrades can replace the native algorithm behind the same contract without changing camera ownership or DNG output semantics.
