# LiquidVivo 1.7.3

Release date: 2026-09-18

## First formal release

- Consolidates the LiquidVivo application identity under `com.LiquidVivo`.
- Keeps the Miuix manager UI and libxposed API 102 module entrypoint in one APK.
- Removes the legacy `com.FucVivo` source tree from the active build graph.
- Adds release-oriented repository hygiene so build caches, APKs, logs, and local signing inputs stay out of version control.

## Build

From `LiquidVivo/`:

```powershell
./gradlew.bat :app:assembleDebug --no-daemon
```

For a signed release build, provide `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` through the local Gradle properties or environment used by the signing plugin.
