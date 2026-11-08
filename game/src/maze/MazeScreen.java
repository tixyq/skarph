package maze;

import java.util.Random;

import skarph.core.*;

/**
 * The whole game in one screen with three modes:
 * MENU - a fixed flight through a big maze behind a PLAY button and a sound icon,
 * PLAY - first person walk through a random small maze (two touch zones: left half = walk stick, right half = look drag;
 *        on the desktop a Pad instead: WASD or arrows up/down walk, mouse or arrows left/right look, the cursor is locked),
 * WIN  - the time, a NEXT button and a close cross.
 * Each maze gets a random look (Variant): day or night, brick or leaves, one of four seasons.
 * Night: a round flashlight spot inscribed in the screen, everything outside it is black.
 */
public final class MazeScreen extends ScreenAdapter implements Ui.Listener {
    public static final int MENU = 0, PLAY = 1, WIN = 2;

    private static final String SETTINGS_FILE = "maze.txt";
    private static final int PAGE_MENU = 1, PAGE_WIN = 2;
    private static final float VH = 320f;               // virtual screen height of the 2D layer
    private static final float S = 2f;                  // block size in world units
    private static final float EYE = 1.1f;
    private static final float RADIUS = 0.45f;          // player half size
    private static final float SPEED = 0.065f;          // units per tick (3.9 per second)
    private static final float FLY_SPEED = 0.07f;
    private static final float TURN_PER_SCREEN = 200f;  // degrees for a drag over one screen height
    private static final float MAX_PITCH = 80f;        // how far up or down you can look, degrees
    private static final float MOUSE_DEG_PER_UNIT = 0.15f; // desktop: degrees of turning per unit of mouse movement
    private static final float KEY_TURN = 2f;           // desktop: degrees per tick with an arrow key held (120 per second)
    private static final float STICK_RADIUS = 0.16f;    // walk stick: full speed at this deflection (share of screen height)
    private static final float STICK_DEAD = 0.20f;      // walk stick: dead zone (share of the radius)
    private static final float BRICK_UV = 0.6f;         // texture tiles per world unit (a brick is about 0.2 x 0.1)
    private static final float LEAF_UV = 0.65f;         // texture tiles per world unit (a leaf is about 0.06)
    private static final float DARK = 0.85f;            // opacity of the black around the flashlight
    private static final float ICON = 36f;
    private static final long FLY_SEED = 7L;            // the menu maze is always the same one
    private static final int FLY_CELLS = 21;            // the only big maze
    private static final int FLY_BLOCKS = 110;          // length of the flight in blocks
    private static final int FADE = 40;
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private Engine engine;
    private Renderer r;
    private Audio audio;
    private final Settings settings = new Settings(4);
    private final Random rnd = new Random(System.nanoTime());

    private int brickTex, leafTex, grassTex, maskTex, uiTex, fontTex;
    private int wallMesh = -1, floorMesh = -1, goalMesh = -1;
    private int sndClick = -1, sndWin = -1;
    private SpriteBatch batch;
    private BitmapFont font;
    private Ui ui;
    private TextureRegion white, mask, iconOn, iconOff, iconClose;
    private int btnPlay, btnNext, lblTime;
    private final Camera camera = new Camera();
    private final Mat4 proj2d = new Mat4();
    private final Mat4 model = new Mat4();
    private final InputEvent event = new InputEvent();
    private Pad pad;                                    // desktop controls, null on Android

    private boolean sizeSet;
    private int screenW = 800, screenH = 480;
    private float virtualW = VH * 800f / 480f;

    private boolean musicOn = true;
    private int mode;
    private Maze maze, menuMaze;
    private int variant = -1, menuVariant = -1, lastGameVariant = -1;

    // player
    private float px, pz, yaw;
    private int moveP = -1, lookP = -1;                 // the two pointers we listen to: right zone, left zone
    private float anchorX, anchorY, lookX, lookY;
    private float pitch;                                // looking up (+) or down (-), degrees
    private float stickX, stickY;                       // walk stick, -1..1 (y down on screen)
    private int gameTicks, gemTick;
    private float goalX, goalZ;

    // flight
    private int flyTick, flyBlocks, flyTotal;
    private float flyYaw;
    private boolean flyYawSet;
    private float qx, qz;

    // camera pose of the frame
    private float eyeX, eyeZ, eyeYaw;
    private boolean iconDown;

    /** Desktop controls: keyboard and mouse look instead of the two touch zones. Call before the screen starts; never on Android. */
    public void setPad(Pad pad) {
        this.pad = pad;
    }

    public void init(Engine engine, Renderer renderer) {
        this.engine = engine;
        this.r = renderer;
        this.audio = engine.getAudio();

        settings.load(engine.getStorage(), SETTINGS_FILE);
        musicOn = settings.getBoolean("musicOn", true);

        brickTex = r.loadTexture("brick.png", true, true);
        leafTex = r.loadTexture("leaves.png", true, true);
        grassTex = r.loadTexture("grass.png", true, true);
        maskTex = r.loadTexture("mask.png", true, false);
        uiTex = r.loadTexture("ui.png", true, false);
        fontTex = r.loadTexture("font.png", false, false);
        if (brickTex < 0 || leafTex < 0 || grassTex < 0 || maskTex < 0 || uiTex < 0 || fontTex < 0) {
            throw new IllegalStateException("texture loading failed (see logcat, tag skarph)");
        }
        font = new BitmapFont(TextureRegion.grid(fontTex, r.getTextureWidth(fontTex),
                r.getTextureHeight(fontTex), 8, 16, 16, 6), 32);
        int uw = r.getTextureWidth(uiTex), uh = r.getTextureHeight(uiTex);
        iconOn = new TextureRegion().set(uiTex, uw, uh, 0, 0, 64, 64);
        iconOff = new TextureRegion().set(uiTex, uw, uh, 64, 0, 64, 64);
        iconClose = new TextureRegion().set(uiTex, uw, uh, 0, 64, 64, 64);
        white = new TextureRegion().set(uiTex, uw, uh, 104, 104, 8, 8);
        mask = new TextureRegion().set(maskTex, r.getTextureWidth(maskTex), r.getTextureHeight(maskTex),
                0, 0, r.getTextureWidth(maskTex), r.getTextureHeight(maskTex));
        batch = new SpriteBatch(r, 128);

        goalMesh = r.createMesh(Shapes.cube(1f, 1f, 0.82f, 0.15f));
        sndClick = audio.loadSound("click.wav");
        sndWin = audio.loadSound("win.wav");

        ui = new Ui(16, 32, batch, font, white);
        ui.setListener(this);
        ui.setViewport(virtualW, VH, screenW, screenH);
        btnPlay = ui.addButton("PLAY", 0f, 100f, 200f, 52f, Ui.CENTER, Ui.MIDDLE, 2.5f);
        ui.setGroup(btnPlay, PAGE_MENU);
        int panel = ui.addPanel(0f, 0f, 270f, 176f, Ui.CENTER, Ui.MIDDLE);
        ui.setBlocking(panel, true);
        int lblT = ui.addLabel("TIME", 0f, -62f, Ui.CENTER, Ui.MIDDLE, 1.5f);
        lblTime = ui.addLabel("0:00", 0f, -16f, Ui.CENTER, Ui.MIDDLE, 4f);
        btnNext = ui.addButton("NEXT", 0f, 48f, 170f, 50f, Ui.CENTER, Ui.MIDDLE, 2.5f);
        ui.setGroup(panel, PAGE_WIN);
        ui.setGroup(lblT, PAGE_WIN);
        ui.setGroup(lblTime, PAGE_WIN);
        ui.setGroup(btnNext, PAGE_WIN);

        menuMaze = new Maze(FLY_CELLS, FLY_CELLS);
        menuMaze.generate(new Random(FLY_SEED));
        flyBlocks = Math.min(FLY_BLOCKS, (int) (menuMaze.pathLen * 0.85f));
        flyTotal = (int) ((flyBlocks - 1) * S / FLY_SPEED);

        audio.setMusicVolume(0.5f);
        if (musicOn) audio.playMusic("music.wav", true);
        enterMenu();
    }

    // ------------------------------------------------------------------ modes

    private void enterMenu() {
        menuVariant = Variant.pick(rnd, lastGameVariant, -1);
        variant = menuVariant;
        buildWorld(menuMaze, menuVariant);
        mode = MENU;
        ui.showGroup(PAGE_MENU);
        flyTick = 0;
        flyYawSet = false;
        pitch = 0f;
        resetTouch();
        if (pad != null) pad.setLookMode(false);
        updateFly();
    }

    private void startGame() {
        maze = new Maze(4 + rnd.nextInt(4), 3 + rnd.nextInt(4));
        maze.generate(rnd);
        variant = Variant.pick(rnd, lastGameVariant, menuVariant);
        lastGameVariant = variant;
        buildWorld(maze, variant);
        px = (maze.startBx + 0.5f) * S;
        pz = (maze.startBz + 0.5f) * S;
        yaw = maze.isWall(maze.startBx + 1, maze.startBz) ? 180f : 90f;
        goalX = (maze.goalBx + 0.5f) * S;
        goalZ = (maze.goalBz + 0.5f) * S;
        mode = PLAY;
        ui.showGroup(0);
        gameTicks = 0;
        pitch = 0f;
        resetTouch();
        if (pad != null) {
            pad.takeLookDx();                           // what the mouse did before the walk starts is not a turn
            pad.takeLookDy();
            pad.setLookMode(true);
        }
        eyeX = px;
        eyeZ = pz;
        eyeYaw = yaw;
    }

    private void win() {
        mode = WIN;
        resetTouch();
        if (pad != null) pad.setLookMode(false);
        int sec = gameTicks / 60;
        ui.text(lblTime).clear().append(sec / 60).append(':');
        if (sec % 60 < 10) ui.text(lblTime).append('0');
        ui.text(lblTime).append(sec % 60);
        ui.showGroup(PAGE_WIN);
        playSound(sndWin);
    }

    private void resetTouch() {
        moveP = -1;
        lookP = -1;
        stickX = 0f;
        stickY = 0f;
    }

    private void playSound(int snd) {
        if (musicOn && snd >= 0) audio.playSound(snd, 0.8f, 1f, false);
    }

    private void toggleSound() {
        musicOn = !musicOn;
        if (musicOn) {
            audio.playMusic("music.wav", true);
            playSound(sndClick);
        } else {
            audio.stopMusic();
        }
        saveSettings();
    }

    private void saveSettings() {
        settings.putBoolean("musicOn", musicOn);
        settings.save(engine.getStorage(), SETTINGS_FILE);
    }

    public void onClick(int id) {
        if (id == btnPlay || id == btnNext) {
            playSound(sndClick);
            startGame();
        }
    }

    public void onValueChanged(int id, float value) {
    }

    // ------------------------------------------------------------------ world meshes

    private void buildWorld(Maze m, int v) {
        if (wallMesh >= 0) r.deleteMesh(wallMesh);
        if (floorMesh >= 0) r.deleteMesh(floorMesh);
        wallMesh = -1;
        floorMesh = -1;
        float hgt = Variant.wallHeight(v);
        float uvScale = Variant.isLeaves(v) ? LEAF_UV : BRICK_UV;
        float[] c = Variant.wallColor(v);
        int faces = 0;
        for (int z = 0; z < m.h; z++) {
            for (int x = 0; x < m.w; x++) {
                if (!m.isWall(x, z)) continue;
                for (int d = 0; d < 4; d++) if (!m.isWall(x + DX[d], z + DZ[d])) faces++;
            }
        }
        if (faces * 4 > 65535) throw new IllegalStateException("maze too big for one mesh");
        float[] pos = new float[faces * 12], nor = new float[faces * 12], col = new float[faces * 16];
        float[] uv = new float[faces * 8];
        short[] idx = new short[faces * 6];
        int f = 0;
        for (int z = 0; z < m.h; z++) {
            for (int x = 0; x < m.w; x++) {
                if (!m.isWall(x, z)) continue;
                for (int d = 0; d < 4; d++) {
                    if (m.isWall(x + DX[d], z + DZ[d])) continue;
                    float nx = DX[d], nz = DZ[d];
                    float rx = nz, rz = -nx;                                  // right vector seen from outside
                    float cx = (x + 0.5f) * S + nx * S * 0.5f, cz = (z + 0.5f) * S + nz * S * 0.5f;
                    float ax = cx - rx * S * 0.5f, az = cz - rz * S * 0.5f;
                    float bx = cx + rx * S * 0.5f, bz = cz + rz * S * 0.5f;
                    int p = f * 12, q = f * 8, k = f * 16, vi = f * 4;
                    pos[p] = ax; pos[p + 1] = 0f; pos[p + 2] = az;
                    pos[p + 3] = bx; pos[p + 4] = 0f; pos[p + 5] = bz;
                    pos[p + 6] = bx; pos[p + 7] = hgt; pos[p + 8] = bz;
                    pos[p + 9] = ax; pos[p + 10] = hgt; pos[p + 11] = az;
                    for (int i = 0; i < 4; i++) {
                        nor[p + i * 3] = nx; nor[p + i * 3 + 1] = 0f; nor[p + i * 3 + 2] = nz;
                    }
                    // texture coordinates come from the world position, so neighbouring walls continue each other
                    boolean alongX = nz != 0f;
                    float ua = (alongX ? ax : az) * uvScale, ub = (alongX ? bx : bz) * uvScale;
                    float vb = 1f, vt = 1f - hgt * uvScale;                  // the bottom edge sits on a texture edge
                    uv[q] = ua; uv[q + 1] = vb;
                    uv[q + 2] = ub; uv[q + 3] = vb;
                    uv[q + 4] = ub; uv[q + 5] = vt;
                    uv[q + 6] = ua; uv[q + 7] = vt;
                    float j = 0.90f + 0.16f * rnd.nextFloat();
                    for (int i = 0; i < 4; i++) {
                        col[k + i * 4] = Math.min(1f, c[0] * j);
                        col[k + i * 4 + 1] = Math.min(1f, c[1] * j);
                        col[k + i * 4 + 2] = Math.min(1f, c[2] * j);
                        col[k + i * 4 + 3] = 1f;
                    }
                    int ii = f * 6;
                    idx[ii] = (short) vi; idx[ii + 1] = (short) (vi + 1); idx[ii + 2] = (short) (vi + 2);
                    idx[ii + 3] = (short) vi; idx[ii + 4] = (short) (vi + 2); idx[ii + 5] = (short) (vi + 3);
                    f++;
                }
            }
        }
        wallMesh = r.createMesh(new MeshData(pos, nor, col, uv, null, idx));
        r.setMeshTextures(wallMesh, Variant.isLeaves(v) ? leafTex : brickTex, -1);

        float M = 40f, x0 = -M, x1 = m.w * S + M, z0 = -M, z1 = m.h * S + M;
        float[] fc = Variant.floorColor(v);
        float[] fpos = {x0, 0f, z1, x1, 0f, z1, x1, 0f, z0, x0, 0f, z0};
        float[] fnor = {0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f};
        float[] fcol = new float[16];
        for (int i = 0; i < 4; i++) {
            fcol[i * 4] = fc[0]; fcol[i * 4 + 1] = fc[1]; fcol[i * 4 + 2] = fc[2]; fcol[i * 4 + 3] = 1f;
        }
        float t = 0.4f;
        float[] fuv = {x0 * t, z1 * t, x1 * t, z1 * t, x1 * t, z0 * t, x0 * t, z0 * t};
        short[] fidx = {0, 1, 2, 0, 2, 3};
        floorMesh = r.createMesh(new MeshData(fpos, fnor, fcol, fuv, null, fidx));
        r.setMeshTextures(floorMesh, grassTex, -1);
    }

    // ------------------------------------------------------------------ update

    public void update() {
        gemTick++;
        while (engine.input.poll(event)) {
            if (mode == PLAY) {
                if (pad == null) steer();               // with the pad the cursor is locked: clicks mean nothing
            } else {
                if (ui.handle(event)) continue;
                iconEvent();
            }
        }
        if (mode == MENU) {
            updateFly();
        } else if (mode == PLAY) {
            gameTicks++;
            if (pad != null) padControls();
            if (stickX != 0f || stickY != 0f) move();
            eyeX = px;
            eyeZ = pz;
            eyeYaw = yaw;
            float dx = px - goalX, dz = pz - goalZ;
            if (dx * dx + dz * dz < 0.8f * 0.8f) win();
        }
    }

    /**
     * Two zones, each followed by its own finger (only two pointers are ever listened to):
     * left half = walk stick (the way the finger is pushed from where it landed is the way you walk,
     * relative to the view), right half = look drag (the view follows the finger: sideways turns, up/down tilts).
     * A finger keeps its job even when it slides into the other half.
     */
    private void steer() {
        int p = event.pointer;
        if (event.type == InputEvent.DOWN) {
            if (event.x >= screenW * 0.5f) {
                if (lookP < 0 && p != moveP) {
                    lookP = p;
                    lookX = event.x;
                    lookY = event.y;
                }
            } else if (moveP < 0 && p != lookP) {
                moveP = p;
                anchorX = event.x;
                anchorY = event.y;
                stickX = 0f;
                stickY = 0f;
            }
        } else if (event.type == InputEvent.MOVE) {
            if (p == lookP) {
                yaw += (event.x - lookX) / screenH * TURN_PER_SCREEN;
                lookX = event.x;
                pitch -= (event.y - lookY) / screenH * TURN_PER_SCREEN;     // finger up = look up
                lookY = event.y;
                if (pitch > MAX_PITCH) pitch = MAX_PITCH;
                else if (pitch < -MAX_PITCH) pitch = -MAX_PITCH;
                if (yaw >= 360f) yaw -= 360f;
                else if (yaw < 0f) yaw += 360f;
            } else if (p == moveP) {
                float radius = screenH * STICK_RADIUS;
                float dx = event.x - anchorX, dy = event.y - anchorY;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > radius) {                      // the stick follows a finger that goes too far
                    float k = (len - radius) / len;
                    anchorX += dx * k;
                    anchorY += dy * k;
                    dx = event.x - anchorX;
                    dy = event.y - anchorY;
                    len = radius;
                }
                float dead = radius * STICK_DEAD;
                if (len <= dead) {
                    stickX = 0f;
                    stickY = 0f;
                } else {
                    float m = (len - dead) / (radius - dead);
                    float n = (float) Math.sqrt(dx * dx + dy * dy);
                    stickX = dx / n * m;
                    stickY = dy / n * m;
                }
            }
        } else if (event.type == InputEvent.UP) {
            if (p == lookP) {
                lookP = -1;
            } else if (p == moveP) {
                moveP = -1;
                stickX = 0f;
                stickY = 0f;
            }
        }
    }

    /**
     * Desktop: the mouse turns and tilts the view (up = look up), the arrows left and right turn it at a steady speed;
     * W and S (or the arrows up and down) walk forward and back, A and D step sideways, all relative to the view.
     * It fills the same walk stick as the touch zone does, so walking is one code path; a diagonal is no faster than a straight line.
     */
    private void padControls() {
        yaw += pad.takeLookDx() * MOUSE_DEG_PER_UNIT + pad.getTurn() * KEY_TURN;
        pitch -= pad.takeLookDy() * MOUSE_DEG_PER_UNIT;
        if (pitch > MAX_PITCH) pitch = MAX_PITCH;
        else if (pitch < -MAX_PITCH) pitch = -MAX_PITCH;
        while (yaw >= 360f) yaw -= 360f;
        while (yaw < 0f) yaw += 360f;
        float wx = pad.getWalkX(), wy = pad.getWalkY();
        float len2 = wx * wx + wy * wy;
        if (len2 > 1f) {
            float k = 1f / (float) Math.sqrt(len2);
            wx *= k;
            wy *= k;
        }
        stickX = wx;
        stickY = -wy;                                   // the stick has y down on the screen, forward is up
    }

    private void iconEvent() {
        float vx = event.x * virtualW / screenW, vy = event.y * VH / screenH;
        boolean in = vx >= virtualW - ICON - 16f && vy <= ICON + 16f;
        if (event.type == InputEvent.DOWN) {
            iconDown = in;
        } else if (event.type == InputEvent.UP) {
            if (iconDown && in) {
                if (mode == MENU) toggleSound();
                else if (mode == WIN) {
                    playSound(sndClick);
                    enterMenu();
                }
            }
            iconDown = false;
        }
    }

    private void move() {
        float sn = FastMath.sin(yaw), cs = FastMath.cos(yaw);
        float fwd = -stickY, side = stickX;                 // forward is up on the screen
        float dx = (sn * fwd + cs * side) * SPEED, dz = (-cs * fwd + sn * side) * SPEED;
        if (!blocked(px + dx, pz)) px += dx;
        if (!blocked(px, pz + dz)) pz += dz;
    }

    private boolean blocked(float x, float z) {
        return solid(x - RADIUS, z - RADIUS) || solid(x + RADIUS, z - RADIUS)
                || solid(x - RADIUS, z + RADIUS) || solid(x + RADIUS, z + RADIUS);
    }

    private boolean solid(float x, float z) {
        return maze.isWall((int) Math.floor(x / S), (int) Math.floor(z / S));
    }

    /** The point 'dist' units along the flight path, written to qx, qz. */
    private void posAt(float dist) {
        float f = dist / S;
        int i = (int) f;
        if (i >= flyBlocks - 1) {
            i = flyBlocks - 2;
            f = flyBlocks - 1;
        }
        float t = f - i;
        int a = menuMaze.path[i], b = menuMaze.path[i + 1];
        int w = menuMaze.w;
        qx = ((a % w) + 0.5f) * S * (1f - t) + ((b % w) + 0.5f) * S * t;
        qz = ((a / w) + 0.5f) * S * (1f - t) + ((b / w) + 0.5f) * S * t;
    }

    private void updateFly() {
        flyTick++;
        if (flyTick >= flyTotal) {
            flyTick = 0;
            flyYawSet = false;
        }
        float d = flyTick * FLY_SPEED;
        posAt(d);
        float x = qx, z = qz;
        posAt(d + 3f);
        float dx = qx - x, dz = qz - z;
        if (dx * dx + dz * dz > 0.0001f) {
            float target = (float) Math.toDegrees(Math.atan2(dx, -dz));
            if (!flyYawSet) {
                flyYaw = target;
                flyYawSet = true;
            } else {
                float diff = target - flyYaw;
                while (diff > 180f) diff -= 360f;
                while (diff < -180f) diff += 360f;
                flyYaw += diff * 0.07f;
            }
        }
        eyeX = x;
        eyeZ = z;
        eyeYaw = flyYaw;
    }

    // ------------------------------------------------------------------ render

    public void render(Renderer rr) {
        int sw = rr.getWidth(), sh = rr.getHeight();
        if (!sizeSet || sw != screenW || sh != screenH) {
            sizeSet = true;
            screenW = sw;
            screenH = sh;
            virtualW = VH * sw / sh;
            proj2d.setOrtho(0f, virtualW, VH, 0f, -1f, 1f);
            ui.setViewport(virtualW, VH, sw, sh);
            camera.setPerspective(70f, (float) sw / sh, 0.2f, 100f);
        }
        int v = variant;
        boolean night = Variant.isNight(v);
        float[] cc = Variant.clearColor(v);
        rr.clear(cc[0], cc[1], cc[2]);
        float fx = FastMath.sin(eyeYaw), fz = -FastMath.cos(eyeYaw);
        float cp = FastMath.cos(pitch), sp = FastMath.sin(pitch);
        camera.lookAt(eyeX, EYE, eyeZ, eyeX + fx * cp, EYE + sp, eyeZ + fz * cp, 0f, 1f, 0f);
        rr.setCamera(camera.projection, camera.view);
        rr.setFog(true, Variant.fogStart(v), Variant.fogEnd(v), cc[0], cc[1], cc[2]);
        rr.setLighting(true);
        float[] am = Variant.ambient(v), l = Variant.light(v);
        rr.setAmbientLight(am[0], am[1], am[2]);
        rr.setDirectionalLight(l[0], l[1], l[2], l[3], l[4], l[5]);
        model.setIdentity();
        rr.drawMesh(floorMesh, model);
        rr.drawMesh(wallMesh, model);
        if (mode != MENU) {
            model.setIdentity();
            model.translate(goalX, 0.95f + 0.12f * FastMath.sin(gemTick * 4f), goalZ);
            model.rotateY(gemTick * 2f);
            model.rotateX(35f);
            model.scale(0.5f, 0.5f, 0.5f);
            rr.drawMesh(goalMesh, model);
        }
        rr.setFog(false, 0f, 0f, 0f, 0f, 0f);

        batch.begin(proj2d);
        if (night) drawFlashlight();
        if (mode == PLAY && pad == null) {              // the dot is for touch; on the desktop the mouse is the aim
            batch.setColor(0f, 0f, 0f, 0.7f);
            batch.draw(white, virtualW * 0.5f - 3f, VH * 0.5f - 3f, 6f, 6f);
            batch.setColor(1f, 1f, 1f, 1f);
            batch.draw(white, virtualW * 0.5f - 2f, VH * 0.5f - 2f, 4f, 4f);
        } else if (mode == MENU) {
            float a = 0f;
            if (flyTick < FADE) a = 1f - flyTick / (float) FADE;
            else if (flyTick > flyTotal - FADE) a = (flyTick - (flyTotal - FADE)) / (float) FADE;
            if (a > 0f) {
                batch.setColor(0f, 0f, 0f, a);
                batch.draw(white, 0f, 0f, virtualW, VH);
            }
        }
        if (mode == MENU) drawIcon(musicOn ? iconOn : iconOff);
        else if (mode == WIN) drawIcon(iconClose);
        ui.draw();
        batch.end();
    }

    private void drawIcon(TextureRegion reg) {
        float x = virtualW - ICON - 10f, y = 10f;
        batch.setColor(0f, 0f, 0f, 0.45f);
        batch.draw(reg, x + 1.5f, y + 1.5f, ICON, ICON);
        batch.setColor(1f, 1f, 1f, 0.95f);
        batch.draw(reg, x, y, ICON, ICON);
    }

    /** A circle that touches the short sides of the screen; everything else is dark (not fully black). */
    private void drawFlashlight() {
        float d = Math.min(virtualW, VH);
        float x0 = (virtualW - d) * 0.5f, y0 = (VH - d) * 0.5f;
        batch.setColor(1f, 1f, 1f, DARK);
        batch.draw(mask, x0, y0, d, d);
        batch.setColor(0f, 0f, 0f, DARK);
        if (x0 > 0f) {                                   // the quads share their edges exactly: no seam, no double dark line
            batch.draw(white, 0f, 0f, x0, VH);
            batch.draw(white, x0 + d, 0f, virtualW - (x0 + d), VH);
        }
        if (y0 > 0f) {
            batch.draw(white, 0f, 0f, virtualW, y0);
            batch.draw(white, 0f, y0 + d, virtualW, VH - (y0 + d));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public void pause() {
        saveSettings();
    }

    public void dispose() {
        if (wallMesh >= 0) r.deleteMesh(wallMesh);
        if (floorMesh >= 0) r.deleteMesh(floorMesh);
        if (goalMesh >= 0) r.deleteMesh(goalMesh);
        wallMesh = floorMesh = goalMesh = -1;
    }

    // ------------------------------------------------------------------ for tests

    public int getMode() { return mode; }
    public Maze getMaze() { return maze; }
    public Maze getMenuMaze() { return menuMaze; }
    public int getVariant() { return variant; }
    public int getMenuVariant() { return menuVariant; }
    public float getPlayerX() { return px; }
    public float getPlayerZ() { return pz; }
    public float getYaw() { return yaw; }
    public int getGameTicks() { return gameTicks; }
    public float getEyeX() { return eyeX; }
    public float getEyeZ() { return eyeZ; }
    public float getEyeYaw() { return eyeYaw; }
    public int getFlyTick() { return flyTick; }
    public int getFlyTotal() { return flyTotal; }
    public boolean isSoundOn() { return musicOn; }
    public float getPitch() { return pitch; }
    public float getStickX() { return stickX; }
    public float getStickY() { return stickY; }
}
