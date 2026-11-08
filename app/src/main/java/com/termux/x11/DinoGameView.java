package com.termux.x11;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.Random;

/** A small canvas-only game displayed only while the X server is disconnected. */
public final class DinoGameView extends View {
    private static final float JUMP_DURATION_SECONDS = .68f;
    private static final float GRAVITY = 2450f;
    private static final float JUMP_VELOCITY = -830f;
    private static final float INITIAL_SPEED = 310f;
    private static final float MAX_SPEED = 680f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Cactus> cactuses = new ArrayList<>();
    private final Random random = new Random();
    private final Runnable frameRunner = this::nextFrame;
    private final float density;
    private boolean playing, gameOver, hostResumed = true, available = true, jumping, framePosted;
    private float dinoY, velocityY, speed, distanceUntilNext, score;
    // Process-memory only: intentionally not persisted to disk or preferences.
    private static int highScore;
    private int surfaceColor, inkColor, mutedColor;
    private long previousFrameTime;

    public DinoGameView(Context context) { this(context, null); }

    public DinoGameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setFocusable(true);
        setContentDescription("Game dino. Ketuk untuk mulai atau melompat.");
        refreshColors();
    }

    private void refreshColors() {
        surfaceColor = MaterialColors.getColor(getContext(), com.google.android.material.R.attr.colorSurfaceVariant, 0xffe7e0ec);
        inkColor = MaterialColors.getColor(getContext(), com.google.android.material.R.attr.colorOnSurface, 0xff1d1b20);
        mutedColor = MaterialColors.getColor(getContext(), com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff605d66);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (heightMode != MeasureSpec.EXACTLY) {
            height = Math.round(width * .75f); // 4:3 card when portrait uses wrap_content.
            if (heightMode == MeasureSpec.AT_MOST) height = Math.min(height, MeasureSpec.getSize(heightMeasureSpec));
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!playing) dinoY = groundY() - dinoHeight();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        refreshColors();
        canvas.drawColor(surfaceColor);
        float ground = groundY();
        paint.setColor(inkColor);
        canvas.drawRect(dp(20), ground, getWidth() - dp(20), ground + dp(2), paint);

        float dinoX = dp(52), dinoHeight = dinoHeight(), dinoWidth = dp(24);
        // All game art is composed of rectangles, so no image asset or game library is needed.
        canvas.drawRect(dinoX, dinoY + dp(9), dinoX + dinoWidth, dinoY + dinoHeight - dp(5), paint);
        canvas.drawRect(dinoX + dp(11), dinoY, dinoX + dinoWidth + dp(7), dinoY + dp(15), paint);
        canvas.drawRect(dinoX + dp(3), dinoY + dinoHeight - dp(4), dinoX + dp(8), dinoY + dinoHeight, paint);
        canvas.drawRect(dinoX + dp(16), dinoY + dinoHeight - dp(4), dinoX + dp(21), dinoY + dinoHeight, paint);
        paint.setColor(surfaceColor);
        canvas.drawRect(dinoX + dp(22), dinoY + dp(4), dinoX + dp(24), dinoY + dp(6), paint);

        paint.setColor(inkColor);
        for (Cactus cactus : cactuses) {
            float top = ground - cactus.height;
            canvas.drawRect(cactus.x, top, cactus.x + cactus.width, ground, paint);
            if (cactus.height > dp(30)) {
                canvas.drawRect(cactus.x - dp(5), top + dp(12), cactus.x, top + dp(22), paint);
                canvas.drawRect(cactus.x + cactus.width, top + dp(18), cactus.x + cactus.width + dp(5), top + dp(28), paint);
            }
        }

        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(dp(12));
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setColor(mutedColor);
        canvas.drawText(String.format("HI %05d  %05d", highScore, (int) score), getWidth() - dp(20), dp(28), paint);
        if (!playing) {
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(13));
            canvas.drawText(gameOver ? "Game over · ketuk untuk ulang" : "Ketuk untuk main", getWidth() / 2f, getHeight() * .38f, paint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) { performClick(); return true; }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (!available) return true;
        if (!playing) startNewGame();
        else if (!jumping) { jumping = true; velocityY = JUMP_VELOCITY * density; }
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        stopFrame();
        super.onDetachedFromWindow();
    }

    private void startNewGame() {
        playing = true; gameOver = false; jumping = false; score = 0;
        speed = INITIAL_SPEED * density;
        dinoY = groundY() - dinoHeight();
        cactuses.clear();
        distanceUntilNext = getWidth() * .62f;
        previousFrameTime = SystemClock.uptimeMillis();
        scheduleFrame();
        invalidate();
    }

    private void nextFrame() {
        framePosted = false;
        if (!playing || !available || !hostResumed) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(.04f, (now - previousFrameTime) / 1000f);
        previousFrameTime = now;
        update(dt);
        invalidate();
        scheduleFrame();
    }

    private void update(float dt) {
        speed = Math.min(MAX_SPEED * density, speed + 7f * density * dt);
        score += dt * 10;
        if (jumping) {
            velocityY += GRAVITY * density * dt;
            dinoY += velocityY * dt;
            float floor = groundY() - dinoHeight();
            if (dinoY >= floor) { dinoY = floor; jumping = false; velocityY = 0; }
        }
        distanceUntilNext -= speed * dt;
        if (distanceUntilNext <= 0) {
            cactuses.add(new Cactus(getWidth() - distanceUntilNext, dp(11 + random.nextInt(9)), dp(22 + random.nextInt(20))));
            // jump duration × speed + a 90px safe gap + random 0–150px.
            distanceUntilNext = JUMP_DURATION_SECONDS * speed + 90 + random.nextInt(151);
        }
        RectF dino = new RectF(dp(52), dinoY, dp(83), dinoY + dinoHeight());
        for (int i = cactuses.size() - 1; i >= 0; i--) {
            Cactus cactus = cactuses.get(i);
            cactus.x -= speed * dt;
            if (cactus.x + cactus.width < 0) cactuses.remove(i);
            else if (RectF.intersects(dino, new RectF(cactus.x, groundY() - cactus.height, cactus.x + cactus.width, groundY()))) {
                playing = false; gameOver = true; highScore = Math.max(highScore, (int) score); stopFrame(); break;
            }
        }
    }

    public void setGameAvailable(boolean available) {
        this.available = available;
        if (!available) stopFrame();
        else if (playing && hostResumed) { previousFrameTime = SystemClock.uptimeMillis(); scheduleFrame(); }
    }
    public void onHostPause() { hostResumed = false; stopFrame(); }
    public void onHostResume() { hostResumed = true; if (playing && available) { previousFrameTime = SystemClock.uptimeMillis(); scheduleFrame(); } }
    private void scheduleFrame() { if (!framePosted && playing && available && hostResumed) { framePosted = true; postOnAnimation(frameRunner); } }
    private void stopFrame() { removeCallbacks(frameRunner); framePosted = false; }
    private float groundY() { return Math.max(dp(45), getHeight() - dp(42)); }
    private float dinoHeight() { return dp(28); }
    private float dp(float value) { return value * density; }

    private static final class Cactus {
        float x; final float width, height;
        Cactus(float x, float width, float height) { this.x = x; this.width = width; this.height = height; }
    }
}
