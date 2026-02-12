## Keyboard 71

Android custom keyboard app (IME) with a native OpenGL core.

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
5. `SoftKeyboard.kt`: IME lifecycle, text operations, selection/composition.

## Contribution notes

1. Make changes optional when possible.
2. Improve code quality over time.
3. Keep compatibility with the native API boundary (`@Api`).
