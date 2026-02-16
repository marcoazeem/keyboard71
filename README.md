## Keyboard 71

Android custom keyboard app (IME) with a native OpenGL core.

## Current status

1. Original NIN engine behavior depends on legacy `libgl2jni.so`.
2. 64-bit migration is active with an experimental arm64 native renderer.
3. Full fallback mode exists and is currently disabled (`BuildConfig.STUB_NATIVE_ENGINE=false`).
4. Hybrid assist keys are enabled in native mode (`BuildConfig.NATIVE_ASSIST_KEYS=true`) so typing remains possible during migration.
5. Native NIN features are being rebuilt incrementally.

## Local setup

1. Install Android Studio and SDK platform 33.
2. Ensure the SDK exists at `~/Library/Android/sdk` or set `sdk.dir` in `local.properties`.
3. Build with:

```bash
./gradlew :app:assembleDebug
```

## Run and enable

1. Install the debug APK from `app/build/outputs/apk/debug/`.
2. Open the app (`NINActivity`).
3. Tap:
   - `Click here to enable the keyboard`
   - `Click here to switch to the keyboard`

## Architecture

1. `libgl2jni.so`: native keyboard/rendering core (do not modify).
2. `NINLib.java`: app-to-native JNI API.
3. `@Api` methods: native-callback surface. Do not change signatures.
4. `NINView.kt`: rendering + touch relay.
5. `SoftKeyboard.kt`: IME lifecycle, text operations, selection/composition, fallback keyboard UI.

## Contribution notes

1. Make changes optional when possible.
2. Improve code quality over time.
3. Keep compatibility with the native API boundary (`@Api`).
