package fr.minilauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * L'écran d'accueil et ses gestes.
 *
 * - Glisser vers le bas : moitié gauche → notifications, moitié droite → centre de contrôle.
 * - Bandes latérales (un quart de la largeur de chaque côté, presque toute la hauteur) :
 *   poser le doigt sur une zone vide ouvre la roue dessous, et le même geste continue
 *   jusqu'à l'appli. Glisser vers l'intérieur l'ouvre tout de suite.
 * - Appui long sur une zone vide : le menu de l'accueil (ajouter appli/widget…).
 * - Applis posées : toucher = ouvrir ; appui long = déplacer (ou menu si on ne bouge pas).
 * - Widgets : reçoivent les touchers normalement ; appui long = déplacer ou menu.
 */
final class HomeView extends FrameLayout {

    interface Host {
        void onShade(boolean controlCenter);

        void onHomeLongPress();

        void onLaunchHomeApp(AppRepository.AppEntry app, Rect bounds);

        void onHomeItemMenu(Store.HomeItem item);
    }

    private static final long HOLD_MS = 130;
    private static final long LONG_PRESS_MS = 500;

    private final RadialView wheel;
    private final HomeGrid grid;
    private final AlphaStripView strip;
    private boolean stripForwarding; // le geste en cours est transmis à l'alphabet
    private final Host host;
    private final float dp, slop;
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();
    private final Rect insets = new Rect();

    private boolean wheelActive;  // la roue est affichée
    private boolean forwarding;   // le geste en cours est transmis à la roue
    private boolean decided;      // le geste en cours a déjà été interprété
    private boolean inBand;
    private boolean toChildren;   // le geste va au widget touché
    private boolean draggingItem; // un élément de l'accueil est en cours de déplacement
    private boolean dragMoved;
    private Store.HomeItem touchedItem;
    private float downX, downY;
    private long downTime;

    private final Runnable holdOpen = () -> {
        if (decided || !inBand || touchedItem != null) return;
        decided = true;
        activateWheel(downX, downY, true);
    };

    private final Runnable longPress = () -> {
        if (decided) return;
        decided = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (touchedItem != null) {
            if (toChildren) {
                MotionEvent cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_CANCEL, downX, downY, 0);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                toChildren = false;
            }
            draggingItem = true;
            dragMoved = false;
            HomeView.this.grid.startDrag(touchedItem, downX, downY);
        } else if (!inBand) {
            hostRef().onHomeLongPress();
        }
    };

    HomeView(Context context, RadialView wheel, HomeGrid grid, AlphaStripView strip, Host host) {
        super(context);
        this.wheel = wheel;
        this.grid = grid;
        this.strip = strip;
        this.host = host;
        dp = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop() * 1.5f;
        edge.setColor(0x55FFFFFF);
        setWillNotDraw(false);
        addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(strip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(wheel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        wheel.setVisibility(View.INVISIBLE);
    }

    private Host hostRef() {
        return host;
    }

    void wheelClosed() {
        wheelActive = false;
        forwarding = false;
        wheel.setVisibility(View.INVISIBLE);
        invalidate();
    }

    boolean isWheelActive() {
        return wheelActive;
    }

    /** Ouvre la roue au milieu de l'écran (menu « Ajouter une appli », par exemple). */
    void openWheelCentered() {
        activateWheel(getWidth() / 2f, getHeight() * 0.55f, false);
    }

    private View clockView;
    private Runnable onClockTap;

    void setClock(View clock, Runnable onTap) {
        clockView = clock;
        onClockTap = onTap;
    }

    private float bandWidth() {
        return getWidth() * Store.get(getContext()).band() / 100f;
    }

    /** Côté de l'alphabet sous x (0 si le mode alphabet n'y est pas actif). */
    private int alphaSide(float x) {
        if (Store.get(getContext()).edgeMode() != 0) return 0;
        int side = x < getWidth() / 2f ? AlphaStripView.LEFT : AlphaStripView.RIGHT;
        return (strip.sides() & side) != 0 ? side : 0;
    }

    void stripClosed() {
        // Le geste en cours reste confié à l'alphabet jusqu'au lever du doigt.
        invalidate();
    }

    private boolean onClock(float x, float y) {
        return clockView != null && clockView.getVisibility() == View.VISIBLE
                && x >= clockView.getLeft() && x < clockView.getRight()
                && y >= clockView.getTop() && y < clockView.getBottom();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        insets.set(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
        grid.setSafe(insets);
        strip.setSafe(insets);
        return super.onApplyWindowInsets(in);
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
        if (wheelActive || Store.get(getContext()).edgeMode() == 0) return;
        // Repères discrets des deux bandes (mode roue).
        float h = getHeight();
        bar.set(5 * dp, h * 0.3f, 9 * dp, h * 0.7f);
        canvas.drawRoundRect(bar, 2 * dp, 2 * dp, edge);
        bar.set(getWidth() - 9 * dp, h * 0.3f, getWidth() - 5 * dp, h * 0.7f);
        canvas.drawRoundRect(bar, 2 * dp, 2 * dp, edge);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (wheelActive && !forwarding) return wheel.dispatchTouchEvent(e);
        if (stripForwarding || (strip.isActive() && e.getActionMasked() == MotionEvent.ACTION_DOWN)) {
            // L'alphabet a la main : tout le geste lui revient.
            stripForwarding = e.getActionMasked() != MotionEvent.ACTION_UP
                    && e.getActionMasked() != MotionEvent.ACTION_CANCEL;
            return strip.handleTouch(e);
        }

        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                downTime = e.getDownTime();
                decided = false;
                forwarding = false;
                draggingItem = false;
                touchedItem = grid.itemAt(x - grid.getLeft(), y - grid.getTop());
                toChildren = touchedItem != null && touchedItem.widget;
                inBand = x < bandWidth() || x > getWidth() - bandWidth();
                if (toChildren) super.dispatchTouchEvent(e);
                if (touchedItem == null && inBand && !onClock(x, y) && alphaSide(x) != 0 && strip.containsY(y)) {
                    // Mode alphabet : l'index s'ouvre tout de suite sous le doigt.
                    decided = true;
                    stripForwarding = true;
                    strip.begin(alphaSide(x));
                    return strip.handleTouch(e);
                }
                if (touchedItem == null && inBand && !onClock(x, y) && Store.get(getContext()).edgeMode() == 1) {
                    postDelayed(holdOpen, HOLD_MS);
                }
                postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                boolean far = Math.hypot(dx, dy) >= slop;
                if (draggingItem) {
                    if (far) dragMoved = true;
                    grid.moveDrag(x, y);
                    return true;
                }
                if (toChildren) {
                    if (far) removeCallbacks(longPress);
                    return super.dispatchTouchEvent(e);
                }
                if (forwarding) return wheel.dispatchTouchEvent(e);
                if (decided || !far) return true;
                decided = true;
                removeCallbacks(holdOpen);
                removeCallbacks(longPress);
                if (dy > 0 && dy > 1.3f * Math.abs(dx)) {
                    host.onShade(downX > getWidth() / 2f);
                } else if (inBand && Store.get(getContext()).edgeMode() == 1) {
                    activateWheel(downX, downY, true);
                    wheel.dispatchTouchEvent(e);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                removeCallbacks(holdOpen);
                removeCallbacks(longPress);
                if (draggingItem) {
                    draggingItem = false;
                    if (dragMoved) {
                        grid.endDrag();
                    } else {
                        grid.endDrag();
                        host.onHomeItemMenu(touchedItem);
                    }
                    return true;
                }
                if (toChildren) {
                    toChildren = false;
                    return super.dispatchTouchEvent(e);
                }
                if (forwarding) {
                    forwarding = false;
                    return wheel.dispatchTouchEvent(e);
                }
                if (!decided) {
                    if (touchedItem == null && onClock(downX, downY) && onClockTap != null) {
                        onClockTap.run();
                    } else if (touchedItem != null) {
                        AppRepository.AppEntry app = grid.appOf(touchedItem);
                        if (app != null) host.onLaunchHomeApp(app, grid.screenBoundsOf(touchedItem));
                    } else if (inBand && Store.get(getContext()).edgeMode() == 1) {
                        // Simple toucher sur un bord : la roue s'ouvre et attend le doigt suivant.
                        activateWheel(downX, downY, false);
                    }
                }
                decided = true;
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(holdOpen);
                removeCallbacks(longPress);
                if (draggingItem) {
                    draggingItem = false;
                    grid.endDrag();
                }
                if (toChildren) {
                    toChildren = false;
                    return super.dispatchTouchEvent(e);
                }
                if (forwarding) {
                    forwarding = false;
                    return wheel.dispatchTouchEvent(e);
                }
                decided = true;
                return true;
        }
        if (toChildren) return super.dispatchTouchEvent(e);
        return true;
    }

    private void activateWheel(float x, float y, boolean continueGesture) {
        wheelActive = true;
        wheel.setVisibility(View.VISIBLE);
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
