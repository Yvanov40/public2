package fr.minilauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * L'écran d'accueil et ses gestes.
 *
 * - Glisser vers le bas : moitié gauche → notifications, moitié droite → centre de contrôle.
 * - Bandes latérales (un quart de la largeur de chaque côté, presque toute la hauteur) :
 *   poser le doigt ouvre la roue dessous, et le même geste continue jusqu'à l'appli.
 *   Glisser vers l'intérieur l'ouvre tout de suite.
 * - Glisser vers le haut au milieu : la roue, comme un tiroir d'applis.
 * - Appui long au milieu : les réglages.
 */
final class HomeView extends FrameLayout {

    interface Host {
        void onShade(boolean controlCenter);

        void onHomeLongPress();
    }

    private static final long HOLD_MS = 130;
    private static final long LONG_PRESS_MS = 600;

    private final RadialView wheel;
    private final Host host;
    private final float dp, slop;
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    private boolean wheelActive;  // la roue est affichée
    private boolean forwarding;   // le geste en cours est transmis à la roue
    private boolean decided;      // le geste en cours a déjà été interprété
    private boolean inBand;
    private float downX, downY;
    private long downTime;

    private final Runnable holdOpen = () -> {
        if (decided || !inBand) return;
        decided = true;
        activateWheel(downX, downY, true);
    };

    private final Runnable longPress = () -> {
        if (decided || inBand) return;
        decided = true;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        HomeView.this.host.onHomeLongPress();
    };

    HomeView(Context context, RadialView wheel, Host host) {
        super(context);
        this.wheel = wheel;
        this.host = host;
        dp = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop() * 1.5f;
        edge.setColor(0x55FFFFFF);
        setWillNotDraw(false);
        addView(wheel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void wheelClosed() {
        wheelActive = false;
        forwarding = false;
        invalidate();
    }

    boolean isWheelActive() {
        return wheelActive;
    }

    private float bandWidth() {
        return getWidth() * 0.25f;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Sur les téléphones à gestes, le bord de l'écran sert au « retour » : on en réserve
        // une partie (Android limite à 200 dp par côté) pour pouvoir partir du bord.
        if (Build.VERSION.SDK_INT >= 29) {
            int h = b - t, w = r - l;
            int half = (int) (100 * dp), mid = h / 2, strip = (int) (32 * dp);
            List<Rect> rects = new ArrayList<>();
            rects.add(new Rect(0, mid - half, strip, mid + half));
            rects.add(new Rect(w - strip, mid - half, w, mid + half));
            setSystemGestureExclusionRects(rects);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (wheelActive) return;
        // Repères discrets des deux bandes.
        float h = getHeight();
        bar.set(5 * dp, h * 0.3f, 9 * dp, h * 0.7f);
        canvas.drawRoundRect(bar, 2 * dp, 2 * dp, edge);
        bar.set(getWidth() - 9 * dp, h * 0.3f, getWidth() - 5 * dp, h * 0.7f);
        canvas.drawRoundRect(bar, 2 * dp, 2 * dp, edge);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (wheelActive && !forwarding) return wheel.dispatchTouchEvent(e);

        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                downTime = e.getDownTime();
                decided = false;
                forwarding = false;
                inBand = x < bandWidth() || x > getWidth() - bandWidth();
                if (inBand) postDelayed(holdOpen, HOLD_MS);
                else postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (forwarding) return wheel.dispatchTouchEvent(e);
                if (decided) return true;
                float dx = x - downX, dy = y - downY;
                if (Math.hypot(dx, dy) < slop) return true;
                decided = true;
                removeCallbacks(holdOpen);
                removeCallbacks(longPress);
                if (dy > 0 && dy > 1.3f * Math.abs(dx)) {
                    host.onShade(downX > getWidth() / 2f);
                } else if (inBand) {
                    activateWheel(downX, downY, true);
                    wheel.dispatchTouchEvent(e);
                } else if (dy < 0 && -dy > 1.3f * Math.abs(dx)) {
                    activateWheel(getWidth() / 2f, getHeight() * 0.62f, false);
                }
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(holdOpen);
                removeCallbacks(longPress);
                if (forwarding) {
                    forwarding = false;
                    return wheel.dispatchTouchEvent(e);
                }
                if (!decided && inBand) {
                    // Simple toucher sur un bord : la roue s'ouvre et attend le doigt suivant.
                    activateWheel(downX, downY, false);
                }
                decided = true;
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(holdOpen);
                removeCallbacks(longPress);
                if (forwarding) {
                    forwarding = false;
                    return wheel.dispatchTouchEvent(e);
                }
                decided = true;
                return true;
        }
        return true;
    }

    private void activateWheel(float x, float y, boolean continueGesture) {
        wheelActive = true;
        wheel.openAt(x, y, continueGesture);
        invalidate();
        if (continueGesture) {
            forwarding = true;
            MotionEvent down = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_DOWN, x, y, 0);
            wheel.dispatchTouchEvent(down);
            down.recycle();
        }
    }
}
