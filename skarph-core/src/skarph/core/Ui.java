// Copyright (C) 2026 tixyq. GPL-3.0-or-later with attribution term, see LICENSE.
package skarph.core;

/**
 * Small retained-mode UI: panels, labels, buttons, toggles and sliders drawn with the SpriteBatch
 * and BitmapFont, with touch handling (multi-touch aware), anchors for different screen
 * shapes and "pages" (groups of widgets shown one at a time).
 *
 * Coordinates are in virtual units (the same units you give your SpriteBatch projection, for example
 * a fixed height of 320). Widgets are created once in init() (fixed capacity, ids are ints);
 * text lives in preallocated TextBuffers, so changing text, drawing and touching allocate nothing.
 *
 * Per frame:  batch.begin(projection); ui.draw(); batch.end();
 * Per event:  while (input.poll(ev)) { if (ui.handle(ev)) continue; ... your own handling ... }
 *
 * Anchors: a widget's x/y are offsets from the chosen screen position: LEFT/TOP = from the
 * left/top edge, CENTER/MIDDLE = from the center (the widget is centered there), RIGHT/BOTTOM =
 * from the right/bottom edge (use negative offsets, the widget stays inside).
 */
public final class Ui {
    public interface Listener {
        /** A button was released inside its bounds, or a toggle changed (see isChecked). */
        void onClick(int id);

        /** A slider was moved by the user. */
        void onValueChanged(int id, float value);
    }

    public static final int LABEL = 0;
    public static final int BUTTON = 1;
    public static final int TOGGLE = 2;
    public static final int SLIDER = 3;
    public static final int PANEL = 4;

    public static final int LEFT = 0;
    public static final int CENTER = 1;
    public static final int RIGHT = 2;
    public static final int TOP = 0;
    public static final int MIDDLE = 1;
    public static final int BOTTOM = 2;

    /** Group of widgets that are shown on every page. */
    public static final int ALWAYS = -1;

    private static final float BORDER = 1.5f;
    private static final float KNOB_WIDTH = 10f;

    // colors {r, g, b, a}: change them to restyle the UI
    public final float[] colPanel = {0f, 0f, 0f, 0.55f};
    public final float[] colBorder = {1f, 1f, 1f, 0.45f};
    public final float[] colButton = {0.20f, 0.32f, 0.60f, 1f};
    public final float[] colButtonDown = {0.38f, 0.55f, 0.92f, 1f};
    public final float[] colDisabled = {0.25f, 0.25f, 0.28f, 0.7f};
    public final float[] colOn = {0.18f, 0.55f, 0.28f, 0.95f};
    public final float[] colOff = {0.55f, 0.20f, 0.22f, 0.95f};
    public final float[] colText = {1f, 1f, 1f, 1f};
    public final float[] colTextDisabled = {0.6f, 0.6f, 0.6f, 1f};
    public final float[] colTrack = {0f, 0f, 0f, 0.6f};
    public final float[] colKnob = {0.9f, 0.9f, 1f, 1f};

    private final int max;
    private int count;
    private final SpriteBatch batch;
    private final BitmapFont font;
    private final TextureRegion white;

    private final int[] type;
    private final int[] ax;
    private final int[] ay;
    private final float[] x;
    private final float[] y;
    private final float[] w;
    private final float[] h;
    private final float[] rx;
    private final float[] ry;
    private final float[] rw;
    private final float[] rh;
    private final float[] scale;
    private final int[] group;
    private final boolean[] visible;
    private final boolean[] enabled;
    private final boolean[] blocking;
    private final boolean[] checked;
    private final boolean[] inside;
    private final float[] value;
    private final int[] pressedBy;
    private final TextBuffer[] text;

    private final int[] pointerWidget = new int[TouchState.MAX_POINTERS];
    private float virtualW = 480f;
    private float virtualH = 320f;
    private int screenW = 480;
    private int screenH = 320;
    private int activeGroup;
    private Listener listener;

    /**
     * @param maxWidgets capacity
     * @param textCapacity characters per widget text
     * @param white a fully white region of a texture (solid rectangles are drawn by tinting it)
     */
    public Ui(int maxWidgets, int textCapacity, SpriteBatch batch, BitmapFont font, TextureRegion white) {
        max = maxWidgets;
        this.batch = batch;
        this.font = font;
        this.white = white;
        type = new int[max];
        ax = new int[max];
        ay = new int[max];
        x = new float[max];
        y = new float[max];
        w = new float[max];
        h = new float[max];
        rx = new float[max];
        ry = new float[max];
        rw = new float[max];
        rh = new float[max];
        scale = new float[max];
        group = new int[max];
        visible = new boolean[max];
        enabled = new boolean[max];
        blocking = new boolean[max];
        checked = new boolean[max];
        inside = new boolean[max];
        value = new float[max];
        pressedBy = new int[max];
        text = new TextBuffer[max];
        for (int i = 0; i < max; i++) {
            text[i] = new TextBuffer(textCapacity);
        }
        for (int i = 0; i < pointerWidget.length; i++) {
            pointerWidget[i] = -1;
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Call at start-up and whenever the screen size changes.
     * @param virtualW,virtualH the size of your UI coordinate system
     * @param screenW,screenH the real size in pixels (touch events come in pixels)
     */
    public void setViewport(float virtualW, float virtualH, int screenW, int screenH) {
        this.virtualW = virtualW;
        this.virtualH = virtualH;
        this.screenW = screenW > 0 ? screenW : 1;
        this.screenH = screenH > 0 ? screenH : 1;
        for (int i = 0; i < count; i++) {
            layout(i);
        }
    }

    // ---- creating widgets ------------------------------------------------------

    private int add(int t, float px, float py, float pw, float ph, int anchorX, int anchorY,
                    String label, float textScale) {
        if (count == max) {
            return -1;
        }
        int i = count++;
        type[i] = t;
        x[i] = px;
        y[i] = py;
        w[i] = pw;
        h[i] = ph;
        ax[i] = anchorX;
        ay[i] = anchorY;
        scale[i] = textScale;
        group[i] = ALWAYS;
        visible[i] = true;
        enabled[i] = true;
        blocking[i] = false;
        checked[i] = false;
        inside[i] = false;
        value[i] = 0f;
        pressedBy[i] = -1;
        text[i].clear();
        if (label != null) {
            text[i].append(label);
        }
        layout(i);
        return i;
    }

    /** @return widget id, or -1 if the capacity is exhausted (same for all add methods). */
    public int addPanel(float px, float py, float pw, float ph, int anchorX, int anchorY) {
        return add(PANEL, px, py, pw, ph, anchorX, anchorY, null, 1f);
    }

    public int addLabel(String label, float px, float py, int anchorX, int anchorY, float textScale) {
        return add(LABEL, px, py, 0f, 0f, anchorX, anchorY, label, textScale);
    }

    public int addButton(String label, float px, float py, float pw, float ph,
                         int anchorX, int anchorY, float textScale) {
        return add(BUTTON, px, py, pw, ph, anchorX, anchorY, label, textScale);
    }

    public int addToggle(String label, float px, float py, float pw, float ph,
                         int anchorX, int anchorY, float textScale, boolean isChecked) {
        int i = add(TOGGLE, px, py, pw, ph, anchorX, anchorY, label, textScale);
        if (i >= 0) {
            checked[i] = isChecked;
        }
        return i;
    }

    public int addSlider(float px, float py, float pw, float ph, int anchorX, int anchorY, float initial) {
        int i = add(SLIDER, px, py, pw, ph, anchorX, anchorY, null, 1f);
        if (i >= 0) {
            value[i] = clamp01(initial);
        }
        return i;
    }

    public void clear() {
        count = 0;
        for (int i = 0; i < pointerWidget.length; i++) {
            pointerWidget[i] = -1;
        }
    }

    public int getCount() {
        return count;
    }

    // ---- widget properties -------------------------------------------------------

    /** The widget's text. Modify it with append()/clear(): no allocation. */
    public TextBuffer text(int id) {
        return text[id];
    }

    public void setText(int id, String s) {
        text[id].clear().append(s);
    }

    public void setVisible(int id, boolean v) {
        visible[id] = v;
    }

    public void setEnabled(int id, boolean e) {
        enabled[id] = e;
    }

    public boolean isEnabled(int id) {
        return enabled[id];
    }

    /** A blocking panel swallows touches that land on it (for dialogs and backgrounds). */
    public void setBlocking(int id, boolean b) {
        blocking[id] = b;
    }

    public void setChecked(int id, boolean c) {
        checked[id] = c;
    }

    public boolean isChecked(int id) {
        return checked[id];
    }

    /** Sets a slider position (0..1) without calling the listener. */
    public void setValue(int id, float v) {
        value[id] = clamp01(v);
    }

    public float getValue(int id) {
        return value[id];
    }

    public boolean isPressed(int id) {
        return pressedBy[id] >= 0 && inside[id];
    }

    /** Assigns a widget to a page. Widgets of group ALWAYS are shown on every page. */
    public void setGroup(int id, int g) {
        group[id] = g;
    }

    /** Shows only the widgets of this page (plus the ALWAYS ones). Releases all pressed widgets. */
    public void showGroup(int g) {
        activeGroup = g;
        releaseAll();
    }

    public int getActiveGroup() {
        return activeGroup;
    }

    public boolean isShown(int id) {
        return visible[id] && (group[id] < 0 || group[id] == activeGroup);
    }

    /** Resolved rectangle of a widget in virtual units (labels: valid after the first draw). */
    public float getRectX(int id) {
        return rx[id];
    }

    public float getRectY(int id) {
        return ry[id];
    }

    public float getRectW(int id) {
        return rw[id];
    }

    public float getRectH(int id) {
        return rh[id];
    }

    private void releaseAll() {
        for (int i = 0; i < count; i++) {
            pressedBy[i] = -1;
            inside[i] = false;
        }
        for (int i = 0; i < pointerWidget.length; i++) {
            pointerWidget[i] = -1;
        }
    }

    // ---- layout -----------------------------------------------------------------

    private float resolveX(int anchor, float px, float pw) {
        if (anchor == LEFT) {
            return px;
        }
        return anchor == CENTER ? virtualW * 0.5f + px - pw * 0.5f : virtualW + px - pw;
    }

    private float resolveY(int anchor, float py, float ph) {
        if (anchor == TOP) {
            return py;
        }
        return anchor == MIDDLE ? virtualH * 0.5f + py - ph * 0.5f : virtualH + py - ph;
    }

    private void layout(int i) {
        if (type[i] == LABEL) {
            layoutLabel(i);
            return;
        }
        rw[i] = w[i];
        rh[i] = h[i];
        rx[i] = resolveX(ax[i], x[i], w[i]);
        ry[i] = resolveY(ay[i], y[i], h[i]);
    }

    private void layoutLabel(int i) {
        TextBuffer t = text[i];
        int lines = 1;
        int longest = 0;
        int run = 0;
        for (int k = 0; k < t.length(); k++) {
            if (t.charAt(k) == '\n') {
                lines++;
                run = 0;
            } else {
                run++;
                if (run > longest) {
                    longest = run;
                }
            }
        }
        font.setScale(scale[i]);
        rw[i] = font.getTextWidth(longest);
        rh[i] = font.getLineHeight() * lines;
        rx[i] = resolveX(ax[i], x[i], rw[i]);
        ry[i] = resolveY(ay[i], y[i], rh[i]);
    }

    // ---- touch handling -------------------------------------------------------------

    private boolean contains(int i, float vx, float vy) {
        return vx >= rx[i] && vx <= rx[i] + rw[i] && vy >= ry[i] && vy <= ry[i] + rh[i];
    }

    private int hitTest(float vx, float vy) {
        for (int i = count - 1; i >= 0; i--) {
            if (!isShown(i)) {
                continue;
            }
            int t = type[i];
            boolean touchable = t == BUTTON || t == TOGGLE || t == SLIDER || (t == PANEL && blocking[i]);
            if (touchable && contains(i, vx, vy)) {
                return i;
            }
        }
        return -1;
    }

    private void setSliderFromX(int i, float vx) {
        float v = clamp01((vx - rx[i]) / rw[i]);
        if (v != value[i]) {
            value[i] = v;
            if (listener != null) {
                listener.onValueChanged(i, v);
            }
        }
    }

    /**
     * Feed every input event. @return true if the UI used it (the touch started on a widget or
     * belongs to a gesture that did): do not handle such events in your game.
     */
    public boolean handle(InputEvent e) {
        int p = e.pointer;
        if (p < 0 || p >= pointerWidget.length) {
            return false;
        }
        float vx = e.x * virtualW / screenW;
        float vy = e.y * virtualH / screenH;
        int i;
        switch (e.type) {
            case InputEvent.DOWN:
                i = hitTest(vx, vy);
                pointerWidget[p] = i;
                if (i < 0) {
                    return false;
                }
                if (type[i] != PANEL && enabled[i]) {
                    pressedBy[i] = p;
                    inside[i] = true;
                    if (type[i] == SLIDER) {
                        setSliderFromX(i, vx);
                    }
                }
                return true;
            case InputEvent.MOVE:
                i = pointerWidget[p];
                if (i < 0) {
                    return false;
                }
                if (pressedBy[i] == p) {
                    inside[i] = contains(i, vx, vy);
                    if (type[i] == SLIDER) {
                        setSliderFromX(i, vx);
                    }
                }
                return true;
            case InputEvent.UP:
                i = pointerWidget[p];
                if (i < 0) {
                    return false;
                }
                pointerWidget[p] = -1;
                if (pressedBy[i] == p) {
                    boolean fire = inside[i] && (type[i] == BUTTON || type[i] == TOGGLE);
                    pressedBy[i] = -1;
                    inside[i] = false;
                    if (fire) {
                        if (type[i] == TOGGLE) {
                            checked[i] = !checked[i];
                        }
                        if (listener != null) {
                            listener.onClick(i);
                        }
                    }
                }
                return true;
            default:
                return false;
        }
    }

    // ---- drawing ------------------------------------------------------------------

    private void rect(float rx0, float ry0, float rw0, float rh0, float[] c) {
        batch.setColor(c[0], c[1], c[2], c[3]);
        batch.draw(white, rx0, ry0, rw0, rh0);
    }

    private void frame(float fx, float fy, float fw, float fh) {
        rect(fx, fy, fw, BORDER, colBorder);
        rect(fx, fy + fh - BORDER, fw, BORDER, colBorder);
        rect(fx, fy + BORDER, BORDER, fh - 2f * BORDER, colBorder);
        rect(fx + fw - BORDER, fy + BORDER, BORDER, fh - 2f * BORDER, colBorder);
    }

    private void drawText(int i, float tx, float ty, float[] color) {
        batch.setColor(0f, 0f, 0f, 0.75f * color[3]);
        font.draw(batch, text[i], tx + 1f, ty + 1f);
        batch.setColor(color[0], color[1], color[2], color[3]);
        font.draw(batch, text[i], tx, ty);
    }

    /** Draws all shown widgets. Call between batch.begin() and batch.end(). */
    public void draw() {
        for (int i = 0; i < count; i++) {
            if (!isShown(i)) {
                continue;
            }
            switch (type[i]) {
                case PANEL:
                    rect(rx[i], ry[i], rw[i], rh[i], colPanel);
                    frame(rx[i], ry[i], rw[i], rh[i]);
                    break;
                case LABEL:
                    layoutLabel(i);
                    drawText(i, rx[i], ry[i], colText);
                    break;
                case BUTTON:
                case TOGGLE:
                    drawButton(i);
                    break;
                case SLIDER:
                    drawSlider(i);
                    break;
                default:
                    break;
            }
        }
        font.setScale(1f);
        batch.setColor(1f, 1f, 1f, 1f);
    }

    private void drawButton(int i) {
        float[] fill;
        if (!enabled[i]) {
            fill = colDisabled;
        } else if (isPressed(i)) {
            fill = colButtonDown;
        } else if (type[i] == TOGGLE) {
            fill = checked[i] ? colOn : colOff;
        } else {
            fill = colButton;
        }
        rect(rx[i], ry[i], rw[i], rh[i], fill);
        frame(rx[i], ry[i], rw[i], rh[i]);
        font.setScale(scale[i]);
        TextBuffer t = text[i];
        float tw = font.getTextWidth(t.length());
        float th = font.getLineHeight();
        float off = isPressed(i) ? 1f : 0f;
        drawText(i, rx[i] + (rw[i] - tw) * 0.5f + off, ry[i] + (rh[i] - th) * 0.5f + off,
                enabled[i] ? colText : colTextDisabled);
    }

    private void drawSlider(int i) {
        float trackH = 6f;
        float ty = ry[i] + (rh[i] - trackH) * 0.5f;
        rect(rx[i], ty, rw[i], trackH, colTrack);
        rect(rx[i], ty, rw[i] * value[i], trackH, enabled[i] ? colButtonDown : colDisabled);
        rect(rx[i] + (rw[i] - KNOB_WIDTH) * value[i], ry[i], KNOB_WIDTH, rh[i],
                isPressed(i) ? colButtonDown : colKnob);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
