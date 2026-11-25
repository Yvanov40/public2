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

        /** Un tracé (lettre dessinée) : {x, y, temps} par point. */
        void onDrawing(List<float[]> points);

        /**
         * Appli sortie d'un dossier et lâchée en (x, y) de la grille. {@code moved} = faux si le
         * doigt n'a pas bougé (menu de l'appli au lieu d'un dépôt).
         */
        void onFolderAppDrop(AppRepository.AppEntry app, String folder, float x, float y, boolean moved);

        /** Appui long sur une appli de l'alphabet ou d'un dossier : son menu, tout de suite. */
        void onLiftMenu(AppRepository.AppEntry app, String folder);
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
    private boolean pendingSwipe;                  // geste en cours, interprété au lever du doigt
    private final List<float[]> trail = new ArrayList<>();
    private final android.graphics.Paint ink = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path inkPath = new android.graphics.Path();

    private final Runnable homeMenu = () -> {
        // Appui long dans le vide : menu de l'accueil.
        if (decided || touchedItem != null) return;
        decided = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        host().onHomeLongPress();
    };

    // Appli soulevée d'un dossier, qui suit le doigt.
    private AppRepository.AppEntry ghostApp;
    private String ghostFolder;
    private float ghostX, ghostY, ghostStartX, ghostStartY;
    private boolean ghostMoved;

    private void onLift(AppRepository.AppEntry app, String folder, float x, float y) {
        ghostApp = app;
        ghostFolder = folder;
        ghostX = ghostStartX = x;
        ghostY = ghostStartY = y;
        ghostMoved = false;
        folderForwarding = false;
        stripForwarding = false;
        host().onLiftMenu(app, folder); // le menu tout de suite ; un glissé le fermera
    }

    /** Placement d'une appli venue de la recherche : l'icône flotte, le doigt suivant la pose. */
    private boolean placing;

    void startPlacement(AppRepository.AppEntry app) {
        ghostApp = app;
        ghostFolder = null;
        ghostMoved = true;
        placing = true;
        ghostX = getWidth() / 2f;
        ghostY = getHeight() * 0.55f;
        grid.setExternalDrag(true);
        invalidate();
    }

    void cancelPlacement() {
        if (!placing) return;
        placing = false;
        ghostApp = null;
        grid.setExternalDrag(false);
        invalidate();
    }

    private boolean placeTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                ghostX = x;
                ghostY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                AppRepository.AppEntry app = ghostApp;
                placing = false;
                ghostApp = null;
                grid.setExternalDrag(false);
                invalidate();
                // On vise avec l'icône, dessinée au-dessus du doigt.
                host.onFolderAppDrop(app, null, x - grid.getLeft(), y - 46 * dp - grid.getTop(), true);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancelPlacement();
                return true;
        }
        return true;
    }

    private boolean ghostTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                ghostX = x;
                ghostY = y;
                if (!ghostMoved && Math.hypot(x - ghostStartX, y - ghostStartY) > slop * 1.5f) {
                    ghostMoved = true;
                    Sheet.dismissAll();
                    if (Store.get(getContext()).homeLocked()) {
                        android.widget.Toast.makeText(getContext(), "Accueil verrouillé",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        grid.setExternalDrag(true);
                    }
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                AppRepository.AppEntry app = ghostApp;
                String folder = ghostFolder;
                boolean moved = ghostMoved;
                ghostApp = null;
                grid.setExternalDrag(false);
                invalidate();
                // Lâché sur place : le menu, déjà ouvert, reste. Sinon, dépôt (si l'accueil
                // n'est pas verrouillé) ; on vise avec l'icône, dessinée au-dessus du doigt.
                if (moved && !Store.get(getContext()).homeLocked()) {
                    host.onFolderAppDrop(app, folder, x - grid.getLeft(), y - 46 * dp - grid.getTop(), true);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                ghostApp = null;
                grid.setExternalDrag(false);
                invalidate();
                return true;
        }
        return true;
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
            // Le menu s'ouvre tout de suite ; si le doigt glisse ensuite, il se ferme et
            // l'élément part en glisser-déposer.
            pendingItemDrag = true;
            host().onHomeItemMenu(touchedItem);
        } else {
            host().onHomeLongPress();
        }
    };

    // Redimensionnement : poignée tenue, ou toucher à côté qui termine (et ne fait rien d'autre).
    private int resizeHandle = -1;
    private boolean resizeConsume;

    private boolean resizeTouch(MotionEvent e) {
        float x = e.getX() - grid.getLeft(), y = e.getY() - grid.getTop();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resizeHandle = grid.handleAt(x, y);
                if (resizeHandle < 0) {
                    grid.endResize();
                    resizeConsume = true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (resizeHandle >= 0) grid.dragHandle(resizeHandle, x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (resizeHandle >= 0) grid.applyResize();
                resizeHandle = -1;
                resizeConsume = false;
                return true;
        }
        return true;
    }

    /** Appui long sur un élément de l'accueil, menu ouvert : on attend un glissé éventuel. */
    private boolean pendingItemDrag;

    private boolean pendingTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(x - downX, y - downY) > slop * 1.5f) {
                    pendingItemDrag = false;
                    if (Store.get(getContext()).homeLocked()) {
                        android.widget.Toast.makeText(getContext(), "Accueil verrouillé",
                                android.widget.Toast.LENGTH_SHORT).show();
                        decided = true;
                        return true;
                    }
                    Sheet.dismissAll();
                    draggingItem = true;
                    dragMoved = true;
                    grid.startDrag(touchedItem, downX, downY);
                    grid.moveDrag(x, y);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pendingItemDrag = false; // lâché sur place : le menu reste ouvert
                return true;
        }
        return true;
    }

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
        folderWheel.setLiftListener(this::onLift);
        strip.setLiftListener(this::onLift);
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
        if (ghostApp != null && ghostMoved && ghostApp.icon != null && !Store.get(getContext()).homeLocked()) {
            // L'appli soulevée, un peu au-dessus du doigt pour rester visible.
            float half = 30 * dp, gx = ghostX, gy = ghostY - 46 * dp;
            ink.setStyle(android.graphics.Paint.Style.FILL);
            ink.setColor(0x55000000);
            canvas.drawCircle(gx, gy + 4 * dp, half + 4 * dp, ink);
            ink.setStyle(android.graphics.Paint.Style.STROKE);
            ghostApp.icon.setBounds((int) (gx - half), (int) (gy - half), (int) (gx + half), (int) (gy + half));
            ghostApp.icon.draw(canvas);
        }
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
        if (placing) return placeTouch(e);
        if (ghostApp != null) return ghostTouch(e);
        if (grid.isResizing() || resizeConsume) return resizeTouch(e);
        if (pendingItemDrag) return pendingTouch(e);

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

                if (touchedItem != null && touchedItem.folder != null && !grid.folderDirect(touchedItem)) {
                    // Dossier : il s'ouvre en cercle tout de suite, sous le doigt.
                    float[] c = grid.centerOf(touchedItem);
                    if (c != null) {
                        folderWheel.setIconFactor(grid.folderIconFactor(touchedItem.folder));
                        folderWheel.setStyle(grid.folderOpenStyle(touchedItem.folder));
                        folderWheel.setShowName(grid.folderShowName(touchedItem.folder));
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
                pendingSwipe = false;
                trail.clear();
                trail.add(new float[]{x, y, e.getEventTime()});
                if (touchedItem == null && !onClock(x, y)) {
                    // Vide : appui long = menu de l'accueil.
                    postDelayed(homeMenu, LONG_PRESS_MS);
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
                if (pendingSwipe) {
                    trail.add(new float[]{x, y, e.getEventTime()});
                    invalidate();
                    return true;
                }
                if (decided || !far) return true;
                decided = true;
                removeCallbacks(longPress);
                removeCallbacks(homeMenu);
                lastTapTime = 0;
                if (touchedItem == null && dy < 0 && -dy > 1.3f * Math.abs(dx)
                        && Actions.actionFor(getContext(), "gUp") == Actions.RECENTS) {
                    // Un seul geste : la roue des récentes s'ouvre là où le doigt est parti,
                    // on continue jusqu'à l'appli et on lâche.
                    openRecents(downX, downY, e);
                    return true;
                }
                if (!Store.get(getContext()).drawOn()) {
                    // Sans lettres dessinées : le geste part tout de suite, sans attendre le lever.
                    trail.clear();
                    trail.add(new float[]{downX, downY, downTime});
                    trail.add(new float[]{x, y, e.getEventTime()});
                    pendingSwipe = true;
                    finishSwipe();
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
                removeCallbacks(homeMenu);
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
                    } else if (touchedItem != null && touchedItem.folder != null) {
                        // Dossier à icônes directes : la mini-icône touchée s'ouvre ; ailleurs (ou
                        // sur « +n »), le cercle du dossier s'ouvre et reste affiché.
                        AppRepository.AppEntry app = grid.folderAppAt(touchedItem, downX - grid.getLeft(), downY - grid.getTop());
                        if (app != null) {
                            host.onLaunchHomeApp(app, grid.screenBoundsOf(touchedItem));
                        } else {
                            float[] c = grid.centerOf(touchedItem);
                            if (c != null) {
                                folderWheel.setIconFactor(grid.folderIconFactor(touchedItem.folder));
                        folderWheel.setStyle(grid.folderOpenStyle(touchedItem.folder));
                        folderWheel.setShowName(grid.folderShowName(touchedItem.folder));
                        folderWheel.open(touchedItem.folder, grid.folderColor(touchedItem.folder),
                                        grid.folderApps(touchedItem.folder), c[0] + grid.getLeft(), c[1] + grid.getTop());
                                MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                                        c[0] + grid.getLeft(), c[1] + grid.getTop(), 0);
                                folderWheel.handleTouch(down);
                                down.setAction(MotionEvent.ACTION_UP);
                                folderWheel.handleTouch(down); // simple toucher : le cercle reste ouvert
                                down.recycle();
                            }
                        }
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
                removeCallbacks(homeMenu);
                pendingSwipe = false;
                trail.clear();
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
        boolean drawing = Store.get(getContext()).drawOn() && length > 90 * dp && length > 1.3 * chord;
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
                return true;
            case MotionEvent.ACTION_CANCEL:
                draggingItem = false;
                grid.endDrag(-1, -1);
                return true;
        }
        return true;
    }
}
