<p align="center"><img src="icon.png" width="128" alt="Skarph"></p>

# Skarph 1.1

A small 3D engine with 2D support, written in Java. Fixed 60 Hz logic, no allocation in the game loop, OpenGL ES 1.x
and 2.0 backends with automatic choice. The core has no platform dependencies; it runs on Android and on Windows,
Linux and macOS through two equal platform layers.

```
skarph-core/      the engine, pure Java: loop, math, scene, physics, UI, effects
skarph-android/   platform layer for Android: view, renderers, audio, storage, assets, input;
                  build-android.sh builds a signed APK of a program
skarph-desktop/   platform layer for Windows, Linux and macOS: window, OpenGL, audio, input (LWJGL, downloaded on first start);
                  build-desktop.sh builds a portable jar of a program
demos/            example programs (index: demos/README.md)
docs/             MANUAL.md (guide) and API.md (every public class and member)
tools/            Python tools: model converters, lightmap baker, camera tracks, level preview, icons
tests/            headless tests; run-tests.sh compiles the engine and runs all of them
icon.png          the Skarph icon, 512x512, transparent; used as the icon of every platform
```

Every script (`.sh` and `.py`) prints what it does and how to call it when it is started without arguments or with
`help`, `-help`, `--help` or `-h`; nothing else happens then. Start with that.

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), to learn about how the engine works.

## Start

Read [docs/MANUAL.md](docs/MANUAL.md), section 2, for a working app in one screen of code.

## Build and test

Both platforms are built from the same sources; nothing in the core depends on either of them.

```
tests/run-tests.sh build                                             tests: JDK only
tests/run-tests.sh build --android-jar /path/android.jar             more tests (Android layer, demos, GL)
skarph-android/build-android.sh demos/demo-v1.0 --sdk ~/Android/Sdk  a signed APK: build/apk/demo-v1.0.apk
skarph-desktop/build-desktop.sh                                      without arguments: the help of the jar build
```

`tests/run-tests.sh` compiles the engine and the platform layers and runs the headless tests; the options that add checks
are in its help. `build-android.sh` needs the Android SDK build-tools (aapt2, d8, zipalign, apksigner) and an `android.jar`.
`build-desktop.sh` builds one jar that runs with `java -jar` on Windows, Linux and macOS (Java 8 or newer).
See [docs/MANUAL.md](docs/MANUAL.md), sections 2, 12 and 14.

## License

MIT License, see [LICENSE](LICENSE).

Copyright (c) 2026 tixyq
