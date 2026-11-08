# Demos

Example programs, one per engine version. Each folder has `src/`, `res/` (the app icon), `assets/` (if the demo needs files) and
`AndroidManifest.xml`. This is the only place that tracks which version added what.

| Demo | Topic | New in the engine |
|---|---|---|
| [demo-v0.1](#demo-v01) | Foundation | Fixed 60 Hz tick loop, math, memory pools, input queue and the OpenGL ES 1.x renderer. |
| [demo-v0.2](#demo-v02) | 2D and textures | Textures, sprite sheets, `SpriteBatch`, frame animation, bitmap font. |
| [demo-v0.3](#demo-v03) | Scene, camera, light | `Scene`/`Node` hierarchy, frustum culling, one directional light, VBO. |
| [demo-v0.4](#demo-v04) | Collisions and physics | Rays, picking, `SpatialGrid` broad phase, `PhysicsWorld`. |
| [demo-v0.5](#demo-v05) | Assets and formats | MDL1 models, OBJ / glTF converters, `ResourceManager`, level files, baked lightmaps. |
| [demo-v0.6](#demo-v06) | Audio, UI, multi-touch, saving | Sound and music, the `Ui` layer, multi-touch, `Settings` + `Storage`. |
| [demo-v0.7](#demo-v07) | OpenGL ES 2.0 backend | A shader backend with automatic choice and fallback to ES 1.x. |
| [demo-v0.8](#demo-v08) | Effects and stress test | Profiler, fog, sky, 3D billboards, particles, camera track, timeline, benchmark, quality levels. |
| [demo-v1.0](#demo-v10) | Night port | A second stress test on a new map, with menu, sound and music. |

## Running a demo

1. Create a project with `minSdkVersion` 3 (landscape for 0.8 and 1.0, see the demo manifest).
2. Add the sources of `skarph-core/src`, `skarph-android/src` and `demos/demo-vX.Y/src`.
3. Copy **all** files of `demos/demo-vX.Y/assets` into the app `assets` folder.
4. Copy `demos/demo-vX.Y/res` into the app `res` folder (the Skarph icon, 48 / 72 / 96 px).
5. Use `demos/demo-vX.Y/AndroidManifest.xml`.

**Desktop** (demo-v1.0): `./build-desktop.sh` in the project folder builds one portable jar, `java -jar build/desktop/skarph-demo-v1.0.jar`
runs it on Windows, Linux and macOS (`demos/demo-v1.0/desktop/DesktopDemo.java`, the same screen as on Android; manual section 14).

Without a device, the plain-Java parts of the demos run headless in `build.sh` (`TownDemoTest`, `PortDemoTest`,
`DemoSmokeTest`).

## demo-v0.1

**Foundation.** Fixed 60 Hz tick loop, math, memory pools, input queue and the OpenGL ES 1.x renderer.

Tests:

- Tick loop: the cubes move per tick, so the speed is the same at any FPS.
- `Mat4` transforms, `Shapes.cube`, `Renderer.drawMesh`.
- Touch input through `engine.input`; `Engine.stats` (FPS / TPS in logcat, tag `skarph`).

On screen: One big cube spinning at 60 degrees per second with 8 small cubes orbiting it. Drag to rotate the big cube. logcat shows `fps=.. tps=60`.

## demo-v0.2

**2D and textures.** Textures, sprite sheets, `SpriteBatch`, frame animation, bitmap font.

Tests:

- `Renderer.loadTexture`, `TextureRegion.grid`, `SpriteBatch` (2 draw calls per frame).
- `FrameAnimation` by tick number, blob shadows, an object pool of fading stars.
- `BitmapFont` with `TextBuffer` (no string concatenation).

On screen: Tiled floor, 6 walking creatures with shadows, 8 spinning coins, FPS/TPS counters. Touch or drag spawns stars.

## demo-v0.3

**Scene, camera, light.** `Scene`/`Node` hierarchy, frustum culling, one directional light, VBO.

Tests:

- Node hierarchy (a solar system: pivot, holder, planet, moon).
- `Camera` frustum culling on ~250 objects (button CULL).
- Per-vertex directional light (button LIGHT), VBO or client arrays (`usesBufferObjects`).

On screen: Orbiting camera over a field of boxes and a solar system. HUD: FPS, TPS, total / drawn / culled nodes, VBO yes/no. Drag to turn the camera.

## demo-v0.4

**Collisions and physics.** Rays, picking, `SpatialGrid` broad phase, `PhysicsWorld`.

Tests:

- `Camera.screenToRay` picking: tap a body to kick it, tap the ground to drop a ball.
- `SpatialGrid` vs all pairs (button GRID, counter `tests`).
- Up to 200 bodies (button MORE); physics time per tick in the HUD.

On screen: 60 balls and boxes fall into an arena with 4 obstacles. RESET restores the start state.

## demo-v0.5

**Assets and formats.** MDL1 models, OBJ / glTF converters, `ResourceManager`, level files, baked lightmaps.

Tests:

- `LevelLoader` places models from `level.lvl`; collision boxes come from the same file.
- `ResourceManager` caching and reference counts (button RELOAD: refs double for a moment, models stay the same).
- Baked lightmap on unit 1 (button LMAP) vs dynamic light on props (button LIGHT).

On screen: A brick level with baked soft shadows, bronze statues, bobbing gems and 24 bouncing balls. Tap the floor to drop a ball.

## demo-v0.6

**Audio, UI, multi-touch, saving.** Sound and music, the `Ui` layer, multi-touch, `Settings` + `Storage`.

Tests:

- `Ui`: buttons, toggles, sliders, labels, pages (menu and settings).
- `Audio`: effects (`SoundPool`) and a streamed music loop.
- `TouchState`: one glowing cursor per finger, two-finger pinch.
- Settings and statistics survive a restart.

On screen: Menu with PLAY / SETTINGS. In PLAY put several fingers down; a tap drops a coin with a sound, a pinch resizes it. Change the volumes, close the app, start again: values are kept.

## demo-v0.7

**OpenGL ES 2.0 backend.** A shader backend with automatic choice and fallback to ES 1.x.

Tests:

- The level of v0.5 on either backend through the same `Renderer` interface.
- `EngineView.BACKEND_AUTO / GLES1 / GLES2`, `BackendListener` fallback if the shaders fail.
- Per-pixel light (ES 2.0) vs per-vertex light (ES 1.x) on statues and balls.

On screen: Same scene as v0.5. The fourth button restarts the demo with the other backend (`GLES 2 N/A` on devices without ES 2.0). The choice is saved.

## demo-v0.8

**Effects and stress test.** Profiler, fog, sky, 3D billboards, particles, camera track, timeline, benchmark, quality levels.

Tests:

- A 32 s cutscene: one camera pose per tick from `cutscene.cam`, scenes from `cutscene.tl`.
- Effects (smoke, flames, sparks, fireworks, blob shadows) and a physics show of 80 bodies.
- BENCH: one fixed run, average / minimum FPS and a score, best score saved per quality level.
- Quality levels LOW / MED / HIGH with an AUTO governor.

On screen: A walled town on a plateau. Buttons: FOG, SKY, LMAP, backend, CUT, RESTART, FX, PHYS, QUAL, AUTO, BENCH (STOP while it runs).

## demo-v1.0

**Night port.** A second stress test on a new map, with menu, sound and music.

Tests:

- Night winter port: baked moon and lamp light, glowing windows, fjord, `water.mdl`.
- Menu (`Ui` pages) over a running cutscene, 34 s looping cutscene, 5 scenes.
- Effects: snow, halos, lighthouse beam, welding sparks, fireworks. Sound: `Audio` cues on the timeline (`PortSound`).
- Physics show, quality levels, AUTO, benchmark with a best score per level.

On screen: Menu: START, FREE ORBIT, MUSIC, SFX, backend. In the HUD: MENU, CUT, RESTART, FOG, SKY, LMAP, FX, SFX, Q, AUTO, PHYS, BENCH.
