package com.pcxx.tiaotiao;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

/**
 * 悬浮球本体：一个<b>透明圆形</b>，里面只有一个「下一曲」Logo，没有任何文字。
 *
 * <p>视觉分三层，都刻意做得很轻，避免挡住底下的其他应用：
 * <ol>
 *   <li>圆形底：{@link #FILL_COLOR}，黑色 18% 透明度 —— 在白墙纸和深色壁纸上都能看清轮廓；</li>
 *   <li>描边：{@link #RING_COLOR}，白色 36%，1.2dp，勾出圆的边界；</li>
 *   <li>Logo：纯白三角形 + 竖条（标准的 next 图标），带轻微圆角。</li>
 * </ol>
 *
 * <p>想调透明度只改这两个常量即可，无需动绘制逻辑。
 *
 * <p>手势契约：
 * <ul>
 *   <li>位移 &lt; touchSlop 且未长按 → <b>点击</b>，回调 {@link Listener#onBallTap()}；</li>
 *   <li>位移 ≥ touchSlop → <b>拖动</b>，持续回调 {@link Listener#onBallDrag(int, int)}（相对按下点的增量）；</li>
 *   <li>按住 ≥ {@link #LONG_PRESS_MS} 且未移动 → <b>长按</b>，回调 {@link Listener#onBallLongPress()}。</li>
 * </ul>
 */
public class FloatingBallView extends View {

    /**
     * 圆形底：黑色 30%。这个值是折中的结果：
     * 浅色壁纸上白 Logo 需要一层暗底才看得清，30% 刚好够；
     * 深色壁纸上这层底几乎消失，只剩 Logo 和一圈细描边，够「透明」。
     * 想要更通透就调小（0x26 约 15%），想要更实就调大（0x66 约 40%）。
     */
    private static final int FILL_COLOR = 0x4D000000;
    /** 描边：白色 30%，只用来勾出圆的边界 */
    private static final int RING_COLOR = 0x4DFFFFFF;
    /** 成功回执的绿色 */
    private static final int GLOW_COLOR = 0xFF34D399;

    private static final long LONG_PRESS_MS = 520L;
    private static final float RING_STROKE_DP = 1.2f;
    private static final float PRESS_SCALE = 0.90f;

    /** 拖动 / 点击 / 长按的外抛回调 */
    public interface Listener {
        void onBallTap();

        void onBallLongPress();

        void onBallDragStart();

        /** dx / dy 是相对「按下那一刻」的累计像素增量 */
        void onBallDrag(int dx, int dy);

        void onBallDragEnd();
    }

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyphPath = new Path();
    private final RectF barRect = new RectF();

    private final int touchSlop;
    private final float ringStrokePx;

    private Listener listener;

    private float downRawX;
    private float downRawY;
    private boolean dragging;
    private boolean longPressFired;

    private float pressScale = 1f;
    private float glow = 0f;

    private float cx;
    private float cy;
    private float radius;
    private boolean geometryReady;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!dragging && !longPressFired) {
                longPressFired = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                if (listener != null) {
                    listener.onBallLongPress();
                }
            }
        }
    };

    public FloatingBallView(Context context) {
        this(context, null);
    }

    public FloatingBallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        ringStrokePx = dp(RING_STROKE_DP);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(FILL_COLOR);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringStrokePx);
        ringPaint.setColor(RING_COLOR);

        glyphPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setColor(Color.WHITE);

        glowPaint.setStyle(Paint.Style.STROKE);

        setClickable(true);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        geometryReady = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!geometryReady) {
            buildGeometry();
        }
        canvas.save();
        canvas.scale(pressScale, pressScale, cx, cy);

        // 成功回执：一圈会淡出的绿环
        if (glow > 0.01f) {
            glowPaint.setStrokeWidth(dp(2.0f));
            glowPaint.setColor(Color.argb((int) (210 * glow),
                    Color.red(GLOW_COLOR), Color.green(GLOW_COLOR), Color.blue(GLOW_COLOR)));
            canvas.drawCircle(cx, cy, radius - dp(1.2f), glowPaint);
        }

        canvas.drawCircle(cx, cy, radius, fillPaint);
        canvas.drawCircle(cx, cy, radius - ringStrokePx / 2f, ringPaint);

        canvas.drawPath(glyphPath, glyphPaint);
        // 圆角半径不能超过自身宽度的一半，否则 drawRoundRect 会退化成怪形状，
        // 所以这里显式 clamp 一次，而不是只按高度算。
        float barRadius = Math.min(barRect.height() * 0.10f, barRect.width() / 2f);
        canvas.drawRoundRect(barRect, barRadius, barRadius, glyphPaint);

        canvas.restore();
    }

    /**
     * 用一个「三角形 + 竖条」拼出 next 图标，整体控制在圆心附近，
     * 留出足够的透明边距，看起来才像一个圆里的 logo 而不是被裁切的图。
     */
    private void buildGeometry() {
        float w = getWidth();
        float h = getHeight();
        cx = w / 2f;
        cy = h / 2f;
        radius = Math.min(w, h) / 2f - dp(1.0f);

        float glyphH = radius * 0.78f;      // 图标总高
        float triW = radius * 0.42f;        // 三角形从底边到顶点的水平距离
        float barW = radius * 0.11f;        // 竖条宽度
        float gap = radius * 0.13f;         // 三角与竖条的间隙
        float totalW = triW + gap + barW;

        float left = cx - totalW / 2f;
        float top = cy - glyphH / 2f;
        float bottom = cy + glyphH / 2f;

        glyphPath.reset();
        glyphPath.moveTo(left, top);
        glyphPath.lineTo(left + triW, cy);
        glyphPath.lineTo(left, bottom);
        glyphPath.close();

        barRect.set(left + triW + gap, top, left + totalW, bottom);

        // 用很细的描边把三角形的尖角磨圆一点，小尺寸下更耐看
        glyphPaint.setStrokeWidth(radius * 0.045f);

        geometryReady = true;
    }

    // ---------------------------------------------------------------- 手势

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = ev.getRawX();
                downRawY = ev.getRawY();
                dragging = false;
                longPressFired = false;
                pressScale = PRESS_SCALE;
                invalidate();
                postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getRawX() - downRawX;
                float dy = ev.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    removeCallbacks(longPressRunnable);
                    pressScale = 1f;
                    invalidate();
                    if (listener != null) {
                        listener.onBallDragStart();
                    }
                }
                if (dragging && listener != null) {
                    listener.onBallDrag((int) dx, (int) dy);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                pressScale = 1f;
                invalidate();
                if (dragging) {
                    dragging = false;
                    if (listener != null) {
                        listener.onBallDragEnd();
                    }
                } else if (!longPressFired) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (listener != null) {
                        listener.onBallTap();
                    }
                }
                return true;

            default:
                return super.onTouchEvent(ev);
        }
    }

    // ---------------------------------------------------------------- 动画

    /** 命令下发成功时调用：绿色光环淡出，作为「确实点了」的回执 */
    public void pulseSuccess() {
        android.animation.ValueAnimator va =
                android.animation.ValueAnimator.ofFloat(1f, 0f);
        va.setDuration(520);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(a -> {
            glow = (float) a.getAnimatedValue();
            invalidate();
        });
        va.start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
