package fr.minilauncher;

import android.content.Context;
import android.graphics.Rect;
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
 * - Bords : l'alphabet vertical (glisser de haut en bas, puis vers l'appli).
 * - Gestes réglables : double tap dans le vide, glisser vers le haut, vers le bas (moitié
 *   gauche / droite), vers la gauche, vers la droite.
 * - Dossier : poser le doigt l'ouvre en cercle ; glisser jusqu'à l'appli et lâcher.
 *   Maintenir sans bouger : déplacer le dossier (ou son menu si on lâche sur place).
 * - Applis posées : toucher = ouvrir ; appui long = déplacer (ou menu si on ne bouge pas).
 *   Lâcher une appli sur une autre crée un dossier ; sur un dossier, elle y entre.
 * - Widgets : reçoivent les touchers normalement ; appui long = déplacer ou menu.
 * - Appui long sur une zone vide : le menu de l'accueil.
 */
final class HomeView extends FrameLayout {

    interface Host {
        /** Un geste réglable : voir {@link Actions#GESTURES}. */
        void onGesture(String gesture);

        void onHomeLongPress();

        void onLaunchHomeApp(AppRepository.AppEntry app, Rect bounds);

        void onHomeItemMenu(Store.HomeItem item);

        /** Applis récentes, de la plus récente à la plus ancienne. */
        List<AppRepository.AppEntry> recentApps();

        /** Les 8 applis de l'étoile (null = branche vide). */
        AppRepository.AppEntry[] starApps();

        /** Branche {@code dir} de l'étoile choisie (vide : proposer d'y mettre une appli). */
        void onStar(int dir);

        /** Un tracé (lettre dessinée) : {x, y, temps} par point. */
        void onDrawing(List<float[]> points);
    }

    private static final long LONG_PRESS_MS = 500;

    private final HomeGrid grid;
    private final AlphaStripView strip;
    private final FolderWheelView folderWheel;
    private final Host host;
    private final float dp, slop;
    private final Rect insets = new Rect();

    private boolean stripForwarding;  // le geste en cours est transmis à l'alphabet
    private boolean folderForwarding; // le geste en cours est transmis au dossier ouvert
    private boolean decided;          // le geste en cours a déjà été interprété
    private boolean toChildren;       // le geste va au widget touché
    private boolean draggingItem;     // un élément de l'accueil est en cours de déplacement
    private boolean dragMoved;
    private Store.HomeItem touchedItem;
    private float downX, downY;
    private long downTime;
    private static final long STAR_ARM_MS = 220;   // appui sans bouger avant l'étoile
    private static final long STAR_SHOW_MS = 380;  // hésitation avant de la montrer
    private static final long HOME_MENU_MS = 750;  // appui long dans le vide : menu de l'accueil

    private StarView star;
    private boolean starArmed;
    private int starDir = -1;
    private boolean pendingSwipe;                  // geste en cours, interprété au lever du doigt
    private final List<float[]> trail = new ArrayList<>();
    private final android.graphics.Paint ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path inkPath = new android.graphics.Path();

    private final Runnable armStar = () -> {
        if (decided || touchedItem != null) return;
        starArmed = true;
        if (Store.get(getContext()).haptic()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        star.arm(downX, downY, host().starApps());
        postDelayed(this::revealStar, STAR_SHOW_MS);
    };

    private void revealStar() {
        if (starArmed) star.reveal();
    }

    private final Runnable homeMenu = () -> {
        if (!starArmed || starDir >= 0) return;
        starArmed = false;
        star.hide();
        decided = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        host().onHomeLongPress();
    };

    void setStar(StarView s) {
        star = s;
        addView(s, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private long lastTapTime;
    private float lastTapX, lastTapY;
    private View clockView;
    private Runnable onClockTap;

    private final Runnable longPress = () -> {
        if (decided && !folderForwarding) return;
        if (folderForwarding) {
            // Dossier maintenu sans bouger : on le déplace au lieu de l'ouvrir.
            if (folderWheel().hasMoved()) return;
            folderForwarding = false;
            folderWheel().close();
        }
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
            grid().startDrag(touchedItem, downX, downY);
        } else {
            host().onHomeLongPress();
        }
    };

    HomeView(Context context, HomeGrid grid, AlphaStripView strip, FolderWheelView folderWheel, Host host) {
        super(context);
        this.grid = grid;
        this.strip = strip;
        this.folderWheel = folderWheel;
        this.host = host;
        dp = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop() * 1.5f;
        addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(strip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(folderWheel, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        ink.setStyle(android.graphics.Paint.Style.STROKE);
        ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        ink.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        ink.setStrokeWidth(6 * dp);
        setWillNotDraw(false);
    }

    /** Le tracé du doigt, par-dessus tout (retour visuel des lettres dessinées). */
    @Override
    protected void dispatchDraw(android.graphics.Canvas canvas) {
        super.dispatchDraw(canvas);
        if (!pendingSwipe || trail.size() < 2) return;
        inkPath.reset();
        inkPath.moveTo(trail.get(0)[0], trail.get(0)[1]);
        for (int i = 1; i < trail.size(); i++) inkPath.lineTo(trail.get(i)[0], trail.get(i)[1]);
        ink.setColor((Store.get(getContext()).accent() & 0x00FFFFFF) | 0xCC000000);
        canvas.drawPath(inkPath, ink);
    }

    private HomeGrid grid() {
        return grid;
    }

    private FolderWheelView folderWheel() {
        return folderWheel;
    }

    private Host host() {
        return host;
    }

    void setClock(View clock, Runnable onTap) {
        clockView = clock;
        onClockTap = onTap;
    }

    void stripClosed() {
        // Le geste en cours reste confié à l'alphabet jusqu'au lever du doigt.
        invalidate();
    }

    void folderClosed() {
        invalidate();
    }

    private float bandWidth() {
        return getWidth() * Store.get(getContext()).band() / 100f;
    }

    /** Côté de l'alphabet sous x (0 s'il n'y est pas actif). */
    private int alphaSide(float x) {
        int side = x < getWidth() / 2f ? AlphaStripView.LEFT : AlphaStripView.RIGHT;
        return (strip.sides() & side) != 0 ? side : 0;
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
        folderWheel.setSafe(insets);
        return super.onApplyWindowInsets(in);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Sur les téléphones à gestes, le bord de l'écran sert au « retour » : on en réserve
        // une partie (Android limite à 200 dp par côté) pour pouvoir partir du bord.
        if (Build.VERSION.SDK_INT >= 29) {
            int h = b - t, w = r - l;
            int half = (int) (100 * dp), mid = h / 2, edge = (int) (32 * dp);
            List<Rect> rects = new ArrayList<>();
            rects.add(new Rect(0, mid - half, edge, mid + half));
            rects.add(new Rect(w - edge, mid - half, w, mid + half));
            setSystemGestureExclusionRects(rects);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();

        // Un dossier ouvert ou l'alphabet affiché garde la main sur les gestes suivants.
        if (folderForwarding || (folderWheel.isOpen() && action == MotionEvent.ACTION_DOWN)) {
            if (action == MotionEvent.ACTION_DOWN) folderForwarding = true;
            boolean r = folderWheel.handleTouch(e);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                folderForwarding = false;
                removeCallbacks(longPress);
            }
            if (action == MotionEvent.ACTION_MOVE && folderWheel.hasMoved()) removeCallbacks(longPress);
            return r;
        }
        if (draggingItem) return dragTouch(e);
        if (stripForwarding || (strip.isActive() && action == MotionEvent.ACTION_DOWN)) {
            stripForwarding = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL;
            return strip.handleTouch(e);
        }

        float x = e.getX(), y = e.getY();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                downTime = e.getDownTime();
                decided = false;
                draggingItem = false;
                touchedItem = grid.itemAt(x - grid.getLeft(), y - grid.getTop());
                toChildren = touchedItem != null && touchedItem.widget;
                boolean inBand = x < bandWidth() || x > getWidth() - bandWidth();

                if (touchedItem != null && touchedItem.folder != null) {
                    // Dossier : il s'ouvre en cercle tout de suite, sous le doigt.
                    float[] c = grid.centerOf(touchedItem);
                    if (c != null) {
                        folderWheel.open(touchedItem.folder, grid.folderColor(touchedItem.folder),
                                grid.folderApps(touchedItem.folder), c[0] + grid.getLeft(), c[1] + grid.getTop());
                        folderForwarding = true;
                        folderWheel.handleTouch(e);
                        postDelayed(longPress, LONG_PRESS_MS);
                        return true;
                    }
                }
                if (touchedItem == null && inBand && !onClock(x, y) && alphaSide(x) != 0 && strip.containsY(y)) {
                    decided = true;
                    stripForwarding = true;
                    strip.begin(alphaSide(x));
                    return strip.handleTouch(e);
                }
                if (toChildren) super.dispatchTouchEvent(e);
                starArmed = false;
                starDir = -1;
                pendingSwipe = false;
                trail.clear();
                trail.add(new float[]{x, y, e.getEventTime()});
                if (touchedItem == null && !onClock(x, y)) {
                    // Vide : appuyer-glisser = étoile ; appui long = menu de l'accueil.
                    postDelayed(armStar, STAR_ARM_MS);
                    postDelayed(homeMenu, HOME_MENU_MS);
                } else {
                    postDelayed(longPress, LONG_PRESS_MS);
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX, dy = y - downY;
                boolean far = Math.hypot(dx, dy) >= slop;
                if (toChildren) {
                    if (far) removeCallbacks(longPress);
                    return super.dispatchTouchEvent(e);
                }
                if (starArmed) {
                    // Étoile : la direction du doigt choisit la branche.
                    double dist = Math.hypot(dx, dy);
                    if (dist > slop) removeCallbacks(homeMenu);
                    int dir = -1;
                    if (dist > 36 * dp) {
                        double a = Math.toDegrees(Math.atan2(dx, -dy));
                        if (a < 0) a += 360;
                        dir = (int) Math.round(a / 45) % 8;
                    }
                    if (dir != starDir) {
                        starDir = dir;
                        star.select(dir);
                        if (dir >= 0 && Store.get(getContext()).haptic()) {
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                    }
                    return true;
                }
                if (pendingSwipe) {
                    trail.add(new float[]{x, y, e.getEventTime()});
                    invalidate();
                    return true;
                }
                if (decided || !far) return true;
                decided = true;
                removeCallbacks(longPress);
                removeCallbacks(armStar);
                removeCallbacks(homeMenu);
                lastTapTime = 0;
                if (touchedItem == null && dy < 0 && -dy > 1.3f * Math.abs(dx)
                        && Actions.actionFor(getContext(), "gUp") == Actions.RECENTS) {
                    // Un seul geste : la roue des récentes s'ouvre là où le doigt est parti,
                    // on continue jusqu'à l'appli et on lâche.
                    openRecents(downX, downY, e);
                    return true;
                }
                // Ligne droite (geste) ou tracé (lettre dessinée) : on le saura au lever du doigt.
                pendingSwipe = true;
                trail.add(new float[]{x, y, e.getEventTime()});
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                removeCallbacks(armStar);
                removeCallbacks(homeMenu);
                if (starArmed) {
                    starArmed = false;
                    int dir = starDir;
                    star.hide();
                    if (dir >= 0) host.onStar(dir);
                    decided = true;
                    return true;
                }
                if (pendingSwipe) {
                    trail.add(new float[]{x, y, e.getEventTime()});
                    finishSwipe();
                    decided = true;
                    return true;
                }
                if (toChildren) {
                    toChildren = false;
                    return super.dispatchTouchEvent(e);
                }
                if (!decided) {
                    if (touchedItem == null && onClock(downX, downY) && onClockTap != null) {
                        onClockTap.run();
                    } else if (touchedItem != null) {
                        AppRepository.AppEntry app = grid.appOf(touchedItem);
                        if (app != null) host.onLaunchHomeApp(app, grid.screenBoundsOf(touchedItem));
                    } else {
                        // Toucher dans le vide : un second toucher rapide au même endroit = double tap.
                        long now = e.getEventTime();
                        if (now - lastTapTime < 320 && Math.hypot(x - lastTapX, y - lastTapY) < 60 * dp) {
                            lastTapTime = 0;
                            host.onGesture("gDouble");
                        } else {
                            lastTapTime = now;
                            lastTapX = x;
                            lastTapY = y;
                        }
                    }
                }
                decided = true;
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                removeCallbacks(armStar);
                removeCallbacks(homeMenu);
                starArmed = false;
                pendingSwipe = false;
                trail.clear();
                star.hide();
                invalidate();
                if (toChildren) {
                    toChildren = false;
                    return super.dispatchTouchEvent(e);
                }
                decided = true;
                return true;
        }
        if (toChildren) return super.dispatchTouchEvent(e);
        return true;
    }

    /**
     * Fin d'un glissé : une ligne à peu près droite déclenche le geste de sa direction ; un
     * tracé sinueux est une lettre dessinée.
     */
    private void finishSwipe() {
        pendingSwipe = false;
        List<float[]> pts = new ArrayList<>(trail);
        trail.clear();
        invalidate();
        if (pts.size() < 2) return;
        float[] a = pts.get(0), b = pts.get(pts.size() - 1);
        float dx = b[0] - a[0], dy = b[1] - a[1];
        double chord = Math.hypot(dx, dy), length = 0;
        for (int i = 1; i < pts.size(); i++) {
            length += Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        }
        boolean drawing = length > 90 * dp && length > 1.3 * chord;
        if (drawing && touchedItem == null) {
            host.onDrawing(pts);
            return;
        }
        if (dy > 0 && dy > 1.3f * Math.abs(dx)) {
            host.onGesture(a[0] > getWidth() / 2f ? "gDownRight" : "gDownLeft");
        } else if (dy < 0 && -dy > 1.3f * Math.abs(dx)) {
            host.onGesture("gUp");
        } else if (Math.abs(dx) > 1.3f * Math.abs(dy)) {
            host.onGesture(dx < 0 ? "gLeft" : "gRight");
        }
    }

    /**
     * Ouvre la roue des applis récentes autour de (x, y). Si {@code current} n'est pas nul, le
     * geste en cours continue dans la roue.
     */
    void openRecents(float x, float y, MotionEvent current) {
        List<AppRepository.AppEntry> apps = host.recentApps();
        if (apps.isEmpty()) {
            android.widget.Toast.makeText(getContext(), "Aucune appli récente pour l'instant",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        folderWheel.open("Récentes", Store.get(getContext()).accent(), apps, x, y, false);
        if (current == null) return;
        folderForwarding = true;
        MotionEvent down = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_DOWN, x, y, 0);
        folderWheel.handleTouch(down);
        down.recycle();
        folderWheel.handleTouch(current);
    }

    /** Déplacement d'une appli, d'un dossier ou d'un widget. */
    private boolean dragTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(x - downX, y - downY) >= slop) dragMoved = true;
                grid.moveDrag(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                draggingItem = false;
                grid.endDrag(x - grid.getLeft(), y - grid.getTop());
                if (!dragMoved) host.onHomeItemMenu(touchedItem);
                return true;
            case MotionEvent.ACTION_CANCEL:
                draggingItem = false;
                grid.endDrag(-1, -1);
                return true;
        }
        return true;
    }
}
