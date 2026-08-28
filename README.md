# Camera

Universal Android camera application foundation for real hardware work.

## Identity

- App name: **Camera**
- Application ID: `com.sahidcode404.camera`
- Minimum Android version: **API 23 (Android 6.0)**
- Camera control plane: **Camera2**
- Still pipeline: **multi-frame RAW_SENSOR -> one merged DNG**
- Development distribution: **GitHub Actions -> rolling `dev-latest` OTA**

## Current foundation

- No manufacturer, model, SoC, sensor-vendor, focal-role, or camera-ID hardcoding.
- Public Camera2 route discovery on API 23+ and logical/physical route discovery on API 28+.
- Canonical lens grouping keeps transport IDs opaque and groups physical profiles beneath optical lenses.
- Topology caching for fast subsequent startup.
- Dedicated camera-owner thread and dedicated native-processing executor; expensive RAW merge and DNG writing never run on the UI thread.
- API preview and capability-gated RAW_SENSOR preview modes.
- Sensor / 1:1 / 4:3 / 16:9 / Full preview geometry with rounded clipping.
- Dynamic per-lens RAW burst bounds and scene-dependent frame planning.
- Native CFA-preserving merge baseline with even-pixel alignment, sharpness/motion rejection, exposure normalization, saturation/noise weighting, robust outlier rejection, and a single packed RAW result.
- DNG output through `DngCreator.writeByteBuffer`; no JPEG/HEIF intermediate.
- Permanent **development-only** signer committed intentionally so API 23+ development builds can update in place.
- Every green `main` CI build publishes/replaces `Camera-dev.apk` and `dev-manifest.json` on the rolling `dev-latest` GitHub Release.
- The app checks OTA only after the first visible preview frame, verifies hash/package/version/signer continuity, downloads in the background, then invokes Android's package installer.

## Android platform boundaries

The app can universally discover/use routes that Android exposes to an ordinary third-party application. Vendor-whitelisted or system-only cameras cannot be made universally accessible without privileged/root/vendor-specific code, so this project does not ship brittle vendor hacks.

Likewise, a normal Android app cannot silently replace itself. Development OTA can automatically check, download, verify, and prepare the APK, but Android still owns the final install-confirmation UI (and the one-time “install unknown apps” permission on Android 8+).

On many API-23-era devices two camera devices cannot be open concurrently. Lens switching is therefore designed to be asynchronous and UI-nonblocking with the smallest HAL/session transition possible; true zero-frame-gap physical switching cannot be promised on hardware that does not support concurrent camera operation.

See `docs/ARCHITECTURE.md` and `docs/ROADMAP.md` for the frozen design rules and hardware acceptance gates.
