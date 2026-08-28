# Delivery roadmap

## Foundation gate
- Build/install on API 23.
- Stable Camera preview surface and serialized Camera2 ownership.
- Fast cached public-lens seed plus post-first-frame full discovery.
- Canonical lens grouping with no vendor/device/ID hardcoding.
- Correct preview/output rotation and front-camera mirroring policy.
- Dynamic Sensor / 1:1 / 4:3 / 16:9 / Full preview geometry with rounded clipping.
- Development OTA with permanent development signer and GitHub release publisher.

## Computational RAW gate
- RAW_SENSOR session target with exact timestamp pairing.
- Per-lens min/max burst settings.
- Scene-dependent frame planner.
- Exposure bracketing when MANUAL_SENSOR is available; safe repeated-AE fallback otherwise.
- Sharpness/motion rejection and native CFA merge.
- One merged DNG only.
- Processing never blocks first-frame, preview UI, or camera ownership.

## Hardware acceptance gate
A feature is not called universal merely because CI is green. Validate representative Snapdragon, Exynos, MediaTek, and Tensor devices across API 23+, including single-camera, logical multi-camera, physical-camera, RAW and non-RAW configurations. Record discovery, switching, orientation, lifecycle, thermal, memory, capture, DNG validity, and OTA evidence.
