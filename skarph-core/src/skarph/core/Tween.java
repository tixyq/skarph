// Copyright (C) 2026 tixyq. MIT License, see LICENSE.
package skarph.core;

/**
 * A value that moves from one number to another over a number of logic ticks with an easing curve. Create it once
 * (in init()), restart it with {@link #start} whenever needed; nothing allocates. Counted in TICKS, like everything in
 * the engine:
 * <pre>
 *   fade.start(0f, 1f, 30, Ease.OUT_CUBIC);       // half a second
 *   update():  fade.update();
 *   render():  float a = fade.getValue(engine.getAlpha());   // smooth at any display rate
 * </pre>
 */
public final class Tween {
    private float from;
    private float to;
    private int duration = 1;
    private int ease;
    private int elapsed = 1;
    private int delay;

    public Tween() {
    }

    public Tween(float from, float to, int durationTicks, int ease) {
        start(from, to, durationTicks, ease);
    }

    /** (Re)starts the tween. A duration below 1 jumps to the end value. */
    public Tween start(float from, float to, int durationTicks, int ease) {
        this.from = from;
        this.to = to;
        this.duration = durationTicks < 1 ? 1 : durationTicks;
        this.ease = ease;
        this.elapsed = 0;
        this.delay = 0;
        return this;
    }

    /** Waits this many ticks before the movement begins (call after start()). */
    public Tween setDelay(int ticks) {
        delay = ticks < 0 ? 0 : ticks;
        return this;
    }

    /** Call once per logic tick. */
    public void update() {
        if (delay > 0) {
            delay--;
        } else if (elapsed < duration) {
            elapsed++;
        }
    }

    /** The value at the last tick. */
    public float getValue() {
        return from + (to - from) * Ease.apply(ease, (float) elapsed / duration);
    }

    /** The value a fraction of a tick later: pass Engine.getAlpha() in render() for motion that is smooth at any frame rate. */
    public float getValue(float alpha) {
        if (delay > 0 || elapsed >= duration) {
            return getValue();
        }
        return from + (to - from) * Ease.apply(ease, (elapsed + alpha) / duration);
    }

    public boolean isFinished() {
        return delay == 0 && elapsed >= duration;
    }

    /** Jumps to the end value. */
    public void finish() {
        delay = 0;
        elapsed = duration;
    }

    public float getTarget() {
        return to;
    }
}
