# Development signer

`camera-development.jks` is intentionally committed for the **development OTA channel only**.

- alias: `camera-dev`
- store/key password: `camera-dev-only`
- certificate SHA-256: `5bc768a38b0f8522d6dc6a6dc434658f3d74337cc72bb4abb837b9e8018be7ef`
- v1 + v2 APK signatures are enabled so API 23 devices can install/update the app.

Because this repository is public, this key is **not a security boundary**. Anyone can sign a development APK with it. Production/stable releases must use a separate protected signer that is never committed.
