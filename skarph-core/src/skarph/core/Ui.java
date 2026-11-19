// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
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

        /** Enter was pressed on the on-screen keyboard (or typeEnter()) while this text field had the focus. */
        default void onSubmit(int id) {
        }
    }

    public static final int LABEL = 0;
    public static final int BUTTON = 1;
    public static final int TOGGLE = 2;
    public static final int SLIDER = 3;
    public static final int PANEL = 4;
    public static final int IMAGE = 5;
    public static final int TEXTFIELD = 6;
    public static final int LIST = 7;
    public static final int KEYBOARD = 8;

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
    // 1.2
    private final TextureRegion[] image;
    private final float[] wrap;
    private final int[] measuredHash;
    private final float[] measuredScale;
    private final float[] measuredWrap;
    private final float[] measuredW;
    /** Draw a dark copy of every text one pixel down-right (readable on any background). Switch off to halve the glyph quads. */
    public boolean textShadow = true;
    private final int[] align;
    private final TextBuffer[][] items;
    private final int[] itemCount;
    private final int[] selected;
    private final float[] itemH;
    private final float[] scroll;
    private final float[] vel;
    private final float[] dragY0;
    private final float[] lastY;
    private final boolean[] dragging;
    private final int[] heldKey;
    private int focus = -1;
    private int boundKeyboard = -1;
    private int blink;
    private int kbLayout;           // 0 EN, 1 RU, 2 symbols
    private int kbLetters;          // the letter layout to return to from symbols
    private boolean kbShift;
    private TextureRegion skinPanel;
    private TextureRegion skinButton;
    private TextureRegion skinButtonDown;
    private float skinBorder;
    private float skinScale = 1f;

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
        image = new TextureRegion[max];
        wrap = new float[max];
        measuredHash = new int[max];
        measuredScale = new float[max];
        measuredWrap = new float[max];
        measuredW = new float[max];
        align = new int[max];
        items = new TextBuffer[max][];
        itemCount = new int[max];
        selected = new int[max];
        itemH = new float[max];
        scroll = new float[max];
        vel = new float[max];
        dragY0 = new float[max];
        lastY = new float[max];
        dragging = new boolean[max];
        heldKey = new int[max];
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
        image[i] = null;
        wrap[i] = 0f;
        measuredScale[i] = -1f;            // forces the first measurement
        align[i] = LEFT;
        items[i] = null;
        itemCount[i] = 0;
        selected[i] = -1;
        scroll[i] = 0f;
        vel[i] = 0f;
        dragging[i] = false;
        heldKey[i] = 0;
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

    public int addImage(TextureRegion region, float px, float py, float pw, float ph, int anchorX, int anchorY) {
        int i = add(IMAGE, px, py, pw, ph, anchorX, anchorY, null, 1f);
        if (i >= 0) {
            image[i] = region;
        }
        return i;
    }

    /** A one line input box. Touch it to focus it; type with typeChar() / backspace(), or with the on-screen keyboard. */
    public int addTextField(String initial, float px, float py, float pw, float ph, int anchorX, int anchorY, float textScale) {
        return add(TEXTFIELD, px, py, pw, ph, anchorX, anchorY, initial, textScale);
    }

    /**
     * A scrolling list of single line items. Drag to scroll (with inertia: call update() every tick), tap to select.
     * @param maxItems capacity  @param itemChars characters per item
     */
    public int addList(float px, float py, float pw, float ph, int anchorX, int anchorY,
                       int maxItems, int itemChars, float rowHeight, float textScale) {
        int i = add(LIST, px, py, pw, ph, anchorX, anchorY, null, textScale);
        if (i >= 0) {
            TextBuffer[] it = new TextBuffer[maxItems];
            for (int k = 0; k < maxItems; k++) {
                it[k] = new TextBuffer(itemChars);
            }
            items[i] = it;
            itemH[i] = rowHeight;
        }
        return i;
    }

    /**
     * An on-screen keyboard (Latin, Cyrillic, digits and symbols) that types into the focused text field. Make it about
     * 4:1 wide, anchored to the bottom. See bindKeyboard() to show it only while a field has the focus.
     */
    public int addKeyboard(float px, float py, float pw, float ph, int anchorX, int anchorY, float textScale) {
        return add(KEYBOARD, px, py, pw, ph, anchorX, anchorY, null, textScale);
    }

    /** Draws panels, buttons and toggles with nine-patch regions (border in texture pixels) instead of flat colors. */
    public void setSkin(TextureRegion panel, TextureRegion button, TextureRegion buttonDown, float border, float borderScale) {
        skinPanel = panel;
        skinButton = button;
        skinButtonDown = buttonDown;
        skinBorder = border;
        skinScale = borderScale;
    }

    public void setImage(int id, TextureRegion region) {
        image[id] = region;
    }

    /** Word wraps a label to this width (0 = no wrapping) and aligns its lines (BitmapFont.ALIGN_*). */
    public void setWrap(int id, float width, int alignment) {
        wrap[id] = width;
        align[id] = alignment;
        layout(id);
    }

    public void clear() {
        count = 0;
        focus = -1;
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

    /** Item text of a list; fill it with append(): no allocation. */
    public TextBuffer listText(int id, int index) {
        return items[id][index];
    }

    /** Adds an item. @return its index or -1 if the list is full */
    public int listAdd(int id, String s) {
        if (itemCount[id] >= items[id].length) {
            return -1;
        }
        items[id][itemCount[id]].clear().append(s);
        return itemCount[id]++;
    }

    public void listClear(int id) {
        itemCount[id] = 0;
        selected[id] = -1;
        scroll[id] = 0f;
        vel[id] = 0f;
    }

    public int listCount(int id) {
        return itemCount[id];
    }

    public int getSelected(int id) {
        return selected[id];
    }

    public void setSelected(int id, int index) {
        selected[id] = index < itemCount[id] ? index : -1;
    }

    /** Scrolls so that the item is visible. */
    public void listScrollTo(int id, int index) {
        float top = index * itemH[id];
        if (top < scroll[id]) {
            scroll[id] = top;
        } else if (top + itemH[id] > scroll[id] + rh[id]) {
            scroll[id] = top + itemH[id] - rh[id];
        }
        clampScroll(id);
    }

    private void clampScroll(int i) {
        float maxS = itemCount[i] * itemH[i] - rh[i];
        if (maxS < 0f) {
            maxS = 0f;
        }
        if (scroll[i] < 0f) {
            scroll[i] = 0f;
            vel[i] = 0f;
        } else if (scroll[i] > maxS) {
            scroll[i] = maxS;
            vel[i] = 0f;
        }
    }

    /** The text field that receives typing, or -1. */
    public int getFocus() {
        return focus;
    }

    public void setFocus(int id) {
        focus = id >= 0 && id < count && type[id] == TEXTFIELD ? id : -1;
        blink = 0;
        if (boundKeyboard >= 0) {
            visible[boundKeyboard] = focus >= 0;
        }
    }

    /** The keyboard widget is shown when a text field gets the focus and hidden when something else is touched. */
    public void bindKeyboard(int keyboardId) {
        boundKeyboard = keyboardId;
        visible[keyboardId] = focus >= 0;
    }

    /** Types a character into the focused field (hardware keyboards, your own keys). */
    public void typeChar(char c) {
        if (focus >= 0 && c >= ' ') {
            text[focus].append(c);
            blink = 0;
        }
    }

    public void backspace() {
        if (focus >= 0) {
            text[focus].deleteLast();
            blink = 0;
        }
    }

    public void typeEnter() {
        if (focus >= 0 && listener != null) {
            listener.onSubmit(focus);
        }
    }

    /**
     * Per logic tick (60 per second): list inertia and the text cursor blink. Allocation free. (Not needed if the UI
     * has no lists and no text fields.)
     */
    public void update() {
        blink++;
        for (int i = 0; i < count; i++) {
            if (type[i] == LIST && pressedBy[i] < 0 && vel[i] != 0f) {
                scroll[i] += vel[i];
                vel[i] *= 0.93f;
                if (vel[i] > -0.05f && vel[i] < 0.05f) {
                    vel[i] = 0f;
                }
                clampScroll(i);
            }
        }
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
        // measuring text is the expensive part: redo it only when the text, scale or wrap width changed
        int n = t.length();
        int h = n;
        for (int k = 0; k < n; k++) {
            h = h * 31 + t.charAt(k);
        }
        if (h != measuredHash[i] || scale[i] != measuredScale[i] || wrap[i] != measuredWrap[i]) {
            measuredHash[i] = h;
            measuredScale[i] = scale[i];
            measuredWrap[i] = wrap[i];
            font.setScale(scale[i]);
            if (wrap[i] > 0f) {
                rw[i] = wrap[i];
                rh[i] = font.getLineHeight() * font.countLines(t, wrap[i]);
            } else {
                int lines = 1;
                for (int k = 0; k < n; k++) {
                    if (t.charAt(k) == '\n') {
                        lines++;
                    }
                }
                rw[i] = font.getTextWidth(t);
                rh[i] = font.getLineHeight() * lines;
            }
        }
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
            boolean touchable = t == BUTTON || t == TOGGLE || t == SLIDER || t == TEXTFIELD || t == LIST || t == KEYBOARD
                    || (t == PANEL && blocking[i]);
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
                if (boundKeyboard >= 0 && i != boundKeyboard && (i < 0 || type[i] != TEXTFIELD)) {
                    focus = -1;
                    visible[boundKeyboard] = false;
                }
                if (i < 0) {
                    return false;
                }
                if (type[i] != PANEL && enabled[i]) {
                    pressedBy[i] = p;
                    inside[i] = true;
                    switch (type[i]) {
                        case SLIDER:
                            setSliderFromX(i, vx);
                            break;
                        case TEXTFIELD:
                            setFocus(i);
                            break;
                        case LIST:
                            dragging[i] = false;
                            dragY0[i] = vy;
                            lastY[i] = vy;
                            vel[i] = 0f;
                            break;
                        case KEYBOARD:
                            heldKey[i] = keyboard(i, vx, vy, false);
                            if (heldKey[i] != 0) {
                                pressKey(i, heldKey[i]);
                            }
                            break;
                        default:
                            break;
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
                    } else if (type[i] == LIST) {
                        if (!dragging[i] && Math.abs(vy - dragY0[i]) > 6f) {
                            dragging[i] = true;
                        }
                        if (dragging[i]) {
                            float dy = vy - lastY[i];
                            scroll[i] -= dy;
                            vel[i] = vel[i] * 0.6f - dy * 0.4f;
                            clampScroll(i);
                        }
                        lastY[i] = vy;
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
                    heldKey[i] = 0;
                    if (type[i] == LIST && !dragging[i]) {
                        int k = (int) ((vy - ry[i] + scroll[i]) / itemH[i]);
                        if (vy >= ry[i] && vy <= ry[i] + rh[i] && k >= 0 && k < itemCount[i]) {
                            selected[i] = k;
                            vel[i] = 0f;
                            if (listener != null) {
                                listener.onClick(i);
                            }
                        }
                    }
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

    // ---- on-screen keyboard ---------------------------------------------------------------

    private static final int K_SHIFT = -1;
    private static final int K_BACK = -2;
    private static final int K_ENTER = -3;
    private static final int K_LANG = -4;
    private static final int K_SYM = -5;
    private static final float[] BOTTOM_UNITS = {1.5f, 1.5f, 6f, 3f};
    private static final int[] BOTTOM_KEYS = {K_LANG, K_SYM, ' ', K_ENTER};
    private static final String[][] ROWS = {
        {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"},
        {"1234567890\u0451", "\u0439\u0446\u0443\u043a\u0435\u043d\u0433\u0448\u0449\u0437\u0445\u044a",
            "\u0444\u044b\u0432\u0430\u043f\u0440\u043e\u043b\u0434\u0436\u044d",
            "\u044f\u0447\u0441\u043c\u0438\u0442\u044c\u0431\u044e"},
        {"1234567890", "-/:;()$&@\"", ".,?!'#%+=*", "[]{}<>^~|\\"},
    };

    /**
     * Walks the keys of keyboard i. mode false: returns the code of the key under (qx, qy) or 0. mode true: draws them.
     * Row widths are measured in key units; the widest row (12 units) fills the width.
     */
    private int keyboard(int i, float qx, float qy, boolean draw) {
        String[] rows = ROWS[kbLayout];
        float unit = rw[i] / 12f;
        float rowH = rh[i] / 5f;
        float gap = 1.5f;
        for (int r = 0; r < 5; r++) {
            float y0 = ry[i] + r * rowH;
            if (!draw && (qy < y0 || qy >= y0 + rowH)) {
                continue;
            }
            // collect the keys of the row as (code, width in units)
            int n;
            float total;
            String keys = null;
            if (r < 4) {
                keys = rows[r];
                n = keys.length();
                total = n + (r == 3 ? 3f : 0f);
            } else {
                n = 0;
                total = 12f;
            }
            float cx = rx[i] + (rw[i] - total * unit) * 0.5f;
            if (r == 3) {
                cx = keyAt(i, K_SHIFT, cx, y0, unit * 1.5f, rowH, gap, qx, qy, draw);
                if (cx < 0f) {
                    return K_SHIFT;
                }
            }
            for (int k = 0; k < n; k++) {
                char c = keys.charAt(k);
                int code = kbShift && kbLayout != 2 ? Character.toUpperCase(c) : c;
                float nx = keyAt(i, code, cx, y0, unit, rowH, gap, qx, qy, draw);
                if (nx < 0f) {
                    return code;
                }
                cx = nx;
            }
            if (r == 3) {
                cx = keyAt(i, K_BACK, cx, y0, unit * 1.5f, rowH, gap, qx, qy, draw);
                if (cx < 0f) {
                    return K_BACK;
                }
            }
            if (r == 4) {
                cx = rx[i];
                for (int k = 0; k < 4; k++) {
                    float nx = keyAt(i, BOTTOM_KEYS[k], cx, y0, unit * BOTTOM_UNITS[k], rowH, gap, qx, qy, draw);
                    if (nx < 0f) {
                        return BOTTOM_KEYS[k];
                    }
                    cx = nx;
                }
            }
        }
        return 0;
    }

    /** Draws a key or tests it. @return the x after the key, or -1 if the point (qx, qy) is on it (test mode) */
    private float keyAt(int i, int code, float x0, float y0, float w0, float h0, float gap,
                        float qx, float qy, boolean draw) {
        if (!draw) {
            return qx >= x0 && qx < x0 + w0 && qy >= y0 && qy < y0 + h0 ? -1f : x0 + w0;
        }
        boolean down = heldKey[i] == code && pressedBy[i] >= 0;
        float[] fill = down ? colButtonDown : (code < 0 && code != K_ENTER ? colOff : colButton);
        if (code == K_SHIFT && kbShift) {
            fill = colOn;
        }
        rect(x0 + gap * 0.5f, y0 + gap * 0.5f, w0 - gap, h0 - gap, fill);
        font.setScale(scale[i]);
        float th = font.getLineHeight();
        float ty = y0 + (h0 - th) * 0.5f;
        batch.setColor(colText[0], colText[1], colText[2], colText[3]);
        if (code > 0) {
            if (code != ' ') {
                font.drawChar(batch, (char) code, x0, ty, w0, BitmapFont.ALIGN_CENTER);
            }
        } else {
            String label = code == K_SHIFT ? "Aa" : (code == K_BACK ? "<-" : (code == K_ENTER ? "OK"
                    : (code == K_LANG ? (kbLayout == 1 ? "EN" : "RU") : (kbLayout == 2 ? "abc" : "#+="))));
            font.drawAligned(batch, label, x0, ty, w0, BitmapFont.ALIGN_CENTER);
        }
        return x0 + w0;
    }

    private void pressKey(int i, int code) {
        if (code == K_SHIFT) {
            kbShift = !kbShift;
        } else if (code == K_BACK) {
            backspace();
        } else if (code == K_ENTER) {
            typeEnter();
        } else if (code == K_LANG) {
            kbLetters = kbLayout == 1 ? 0 : 1;
            kbLayout = kbLetters;
            kbShift = false;
        } else if (code == K_SYM) {
            kbLayout = kbLayout == 2 ? kbLetters : 2;
        } else {
            typeChar((char) code);
            if (kbShift) {
                kbShift = false;
            }
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

    /** Width of the text of a button / text field, measured again only when the text or its scale changed. */
    private float cachedWidth(int i) {
        TextBuffer t = text[i];
        int n = t.length();
        int h = n;
        for (int k = 0; k < n; k++) {
            h = h * 31 + t.charAt(k);
        }
        if (h != measuredHash[i] || scale[i] != measuredScale[i]) {
            measuredHash[i] = h;
            measuredScale[i] = scale[i];
            measuredWrap[i] = 0f;
            font.setScale(scale[i]);
            measuredW[i] = font.getTextWidth(t);
        }
        return measuredW[i];
    }

    private void drawText(int i, float tx, float ty, float[] color) {
        if (textShadow) {
            batch.setColor(0f, 0f, 0f, 0.75f * color[3]);
            font.draw(batch, text[i], tx + 1f, ty + 1f);
        }
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
                    if (skinPanel != null) {
                        batch.setColor(1f, 1f, 1f, 1f);
                        batch.drawNine(skinPanel, rx[i], ry[i], rw[i], rh[i], skinBorder, skinBorder, skinBorder, skinBorder, skinScale);
                    } else {
                        rect(rx[i], ry[i], rw[i], rh[i], colPanel);
                        frame(rx[i], ry[i], rw[i], rh[i]);
                    }
                    break;
                case LABEL:
                    layoutLabel(i);
                    drawLabel(i);
                    break;
                case IMAGE:
                    if (image[i] != null) {
                        batch.setColor(1f, 1f, 1f, 1f);
                        batch.draw(image[i], rx[i], ry[i], rw[i], rh[i]);
                    }
                    break;
                case TEXTFIELD:
                    drawField(i);
                    break;
                case LIST:
                    drawList(i);
                    break;
                case KEYBOARD:
                    rect(rx[i], ry[i], rw[i], rh[i], colPanel);
                    keyboard(i, 0f, 0f, true);
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

    private void drawLabel(int i) {
        if (wrap[i] > 0f) {
            if (textShadow) {
                batch.setColor(0f, 0f, 0f, 0.75f);
                font.drawWrapped(batch, text[i], rx[i] + 1f, ry[i] + 1f, wrap[i], align[i], 0);
            }
            batch.setColor(colText[0], colText[1], colText[2], colText[3]);
            font.drawWrapped(batch, text[i], rx[i], ry[i], wrap[i], align[i], 0);
        } else {
            drawText(i, rx[i], ry[i], colText);
        }
    }

    private int pxX(float v) {
        return (int) (v * screenW / virtualW);
    }

    private int pxY(float v) {
        return (int) (v * screenH / virtualH);
    }

    private void drawField(int i) {
        rect(rx[i], ry[i], rw[i], rh[i], colTrack);
        frame(rx[i], ry[i], rw[i], rh[i]);
        font.setScale(scale[i]);
        float pad = 4f;
        float avail = rw[i] - 2f * pad;
        float tw = cachedWidth(i);
        float th = font.getLineHeight();
        float ty = ry[i] + (rh[i] - th) * 0.5f;
        boolean cursor = focus == i && ((blink / 30) & 1) == 0;
        float caret = font.getCharWidth('|');
        batch.setColor(colText[0], colText[1], colText[2], colText[3]);
        batch.setScissor(pxX(rx[i] + 1f), pxY(ry[i] + 1f), pxX(rw[i] - 2f) + 1, pxY(rh[i] - 2f) + 1);
        if (tw + (cursor ? caret : 0f) <= avail) {
            font.draw(batch, text[i], rx[i] + pad, ty);
            if (cursor) {
                font.drawChar(batch, '|', rx[i] + pad + tw, ty, caret, BitmapFont.ALIGN_LEFT);
            }
        } else {
            // too long: show the end of the text
            font.drawAligned(batch, text[i], rx[i] + pad, ty, avail - (cursor ? caret : 0f), BitmapFont.ALIGN_RIGHT);
            if (cursor) {
                font.drawChar(batch, '|', rx[i] + rw[i] - pad - caret, ty, caret, BitmapFont.ALIGN_LEFT);
            }
        }
        batch.clearScissor();
    }

    private void drawList(int i) {
        rect(rx[i], ry[i], rw[i], rh[i], colPanel);
        frame(rx[i], ry[i], rw[i], rh[i]);
        font.setScale(scale[i]);
        float rowH = itemH[i];
        int first = (int) (scroll[i] / rowH);
        float th = font.getLineHeight();
        batch.setScissor(pxX(rx[i] + 1f), pxY(ry[i] + 1f), pxX(rw[i] - 2f) + 1, pxY(rh[i] - 2f) + 1);
        for (int k = first; k < itemCount[i]; k++) {
            float yy = ry[i] + k * rowH - scroll[i];
            if (yy > ry[i] + rh[i]) {
                break;
            }
            if (k == selected[i]) {
                rect(rx[i] + 1f, yy, rw[i] - 2f, rowH, colButton);
            }
            batch.setColor(colText[0], colText[1], colText[2], colText[3]);
            font.drawEllipsized(batch, items[i][k], rx[i] + 6f, yy + (rowH - th) * 0.5f, rw[i] - 12f);
        }
        // thin scroll indicator
        float content = itemCount[i] * rowH;
        if (content > rh[i]) {
            float bar = rh[i] * rh[i] / content;
            float by = ry[i] + (rh[i] - bar) * (scroll[i] / (content - rh[i]));
            rect(rx[i] + rw[i] - 4f, by, 3f, bar, colKnob);
        }
        batch.clearScissor();
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
        if (skinButton != null) {
            TextureRegion r = isPressed(i) && skinButtonDown != null ? skinButtonDown : skinButton;
            float[] tint = type[i] == TOGGLE && enabled[i] ? (checked[i] ? colOn : colOff) : (enabled[i] ? colText : colDisabled);
            batch.setColor(tint[0] + (1f - tint[0]) * 0.5f, tint[1] + (1f - tint[1]) * 0.5f, tint[2] + (1f - tint[2]) * 0.5f, 1f);
            batch.drawNine(r, rx[i], ry[i], rw[i], rh[i], skinBorder, skinBorder, skinBorder, skinBorder, skinScale);
        } else {
            rect(rx[i], ry[i], rw[i], rh[i], fill);
            frame(rx[i], ry[i], rw[i], rh[i]);
        }
        font.setScale(scale[i]);
        TextBuffer t = text[i];
        float tw = cachedWidth(i);
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
