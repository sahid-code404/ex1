# Development signer

`camera-development.jks` is intentionally committed for the **development OTA channel only**.

- alias: `camera-dev`
- store/key password: `camera-dev-only`
- certificate SHA-256: `9dde8fe35506ba993a5b8ffba8f01ff46d35c86419ba8fd5029d187b3f6fbd8c`
- v1 + v2 APK signatures are enabled so API 23 devices can install/update the app.

Because this repository is public, this key is **not a security boundary**. Anyone can sign a development APK with it. Production/stable releases must use a separate protected signer that is never committed.
