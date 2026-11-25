package fr.minilauncher;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * La grille de l'écran d'accueil : 4 colonnes sous l'horloge, avec des applis et des widgets.
 * Placement automatique à la première case libre ; déplacement par glisser-déposer.
 */
final class HomeGrid extends FrameLayout {

    private int cols = 4;
    /** Textes de l'accueil en foncé (fond d'écran clair). Réglé par HomeActivity. */
    static boolean darkText;
    private float topOffset = -1; // bas de l'horloge (-1 : pas encore connu)

    private final float dp;
    private final Store store;
    private final AppWidgetHost widgetHost;
    private final AppWidgetManager widgetManager;
    private final Map<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    private final Map<View, Store.HomeItem> viewItems = new HashMap<>();
    private final Rect safe = new Rect();

    private List<Store.HomeItem> items = Collections.emptyList();
    private Map<String, AppRepository.AppEntry> apps = new HashMap<>();
    private boolean appsLoaded;
    private List<AppRepository.AppEntry> lastList;

    private float left, top, cellW, cellH;
    private int rows;

    // Glisser-déposer
    private Store.HomeItem dragItem;
    private View dragView;
    private float dragStartX, dragStartY;

    HomeGrid(Context context, AppWidgetHost host) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        store = Store.get(context);
        widgetHost = host;
        widgetManager = AppWidgetManager.getInstance(context);
        setClipChildren(false);
    }

    void setSafe(Rect r) {
        if (!safe.equals(r)) {
            safe.set(r);
            rebuild();
        }
    }

    void setApps(List<AppRepository.AppEntry> list) {
        if (list == lastList) {
            // Même liste, icônes en cours de chargement : un simple rafraîchissement suffit.
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).invalidate();
            return;
        }
        lastList = list;
        Map<String, AppRepository.AppEntry> map = new HashMap<>();
        for (AppRepository.AppEntry e : list) map.put(Store.key(e.component), e);
        apps = map;
        appsLoaded = !list.isEmpty();
        rebuild();
    }

    /** Relit les éléments enregistrés (après un ajout depuis un menu, par exemple). */
    void reload() {
        items = store.homeItems();
        rebuild();
    }

    int rows() {
        return rows;
    }

    int cols() {
        return cols;
    }

    /** Le haut de la grille : juste sous l'horloge (ou en haut si elle est masquée). */
    void setTopOffset(float y) {
        if (Math.abs(y - topOffset) > 1) {
            topOffset = y;
            rebuild();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::rebuild);
    }

    private boolean computeGrid() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return false;
        cols = Math.max(2, Math.min(7, store.cols()));
        float pad = 10 * dp;
        left = safe.left + pad;
        cellW = (w - safe.left - safe.right - 2 * pad) / cols;
        top = Math.max(safe.top + 8 * dp, topOffset >= 0 ? topOffset : safe.top + 150 * dp);
        float bottom = h - Math.max(safe.bottom, 16 * dp) - 8 * dp;
        int wanted = store.rowsSetting();
        float iconCell = (store.iconSize() + (store.labels() ? 40 : 20)) * dp;
        if (wanted > 0) {
            rows = wanted;
            cellH = (bottom - top) / rows;
        } else {
            cellH = Math.max(iconCell, 90 * dp);
            rows = Math.max(1, (int) ((bottom - top) / cellH));
        }
        return true;
    }

    void rebuild() {
        if (!computeGrid()) return;
        if (dragItem != null) return; // pas pendant un déplacement
        // Les widgets déjà affichés restent en place (pas de rechargement) ; le reste est recréé.
        for (int i = getChildCount() - 1; i >= 0; i--) {
            if (!(getChildAt(i) instanceof AppWidgetHostView)) removeViewAt(i);
        }
        viewItems.clear();
        boolean changed = false;

        // Nettoyage : applis désinstallées, widgets disparus.
        for (int i = items.size() - 1; i >= 0; i--) {
            Store.HomeItem it = items.get(i);
            if (it.widget) {
                if (widgetManager.getAppWidgetInfo(it.widgetId) == null) {
                    widgetHost.deleteAppWidgetId(it.widgetId);
                    widgetViews.remove(it.widgetId);
                    items.remove(i);
                    changed = true;
                }
            } else if (it.folder != null) {
                if (Store.find(store.groups(), it.folder) == null) {
                    items.remove(i);
                    changed = true;
                }
            } else if (appsLoaded && !apps.containsKey(it.key)) {
                items.remove(i);
                changed = true;
            }
        }
        // Placement des nouveaux et de ceux qui ne tiennent plus (écran plus petit).
        for (Store.HomeItem it : items) {
            it.w = Math.max(1, Math.min(cols, it.w));
            it.h = Math.max(1, Math.min(rows, it.h));
            if (it.col < 0 || it.row < 0 || it.col + it.w > cols || it.row + it.h > rows || !fits(it.col, it.row, it.w, it.h, it)) {
                int[] free = findFree(it.w, it.h, it);
                if (free == null) {
                    it.col = -1;
                    continue;
                }
                it.col = free[0];
                it.row = free[1];
                changed = true;
            }
        }
        if (changed) store.setHomeItems(items);

        Set<View> kept = new HashSet<>();
        for (Store.HomeItem it : items) {
            if (it.col < 0) continue;
            View v = it.widget ? widgetView(it) : it.folder != null ? folderView(it) : appView(it);
            if (v == null) continue;
            LayoutParams lp = new LayoutParams((int) (it.w * cellW), (int) (it.h * cellH));
            lp.leftMargin = (int) (left + it.col * cellW);
            lp.topMargin = (int) (top + it.row * cellH);
            if (v.getParent() == this) {
                v.setLayoutParams(lp);
            } else {
                if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
                addView(v, lp);
            }
            kept.add(v);
            viewItems.put(v, it);
            // Transparence réglable (Réglages > Accueil > Grille).
            v.setAlpha((it.widget ? store.widgetAlpha() : store.iconAlpha()) / 100f);
            if (it.widget) sizeWidget((AppWidgetHostView) v, it);
        }
        for (int i = getChildCount() - 1; i >= 0; i--) {
            if (!kept.contains(getChildAt(i))) removeViewAt(i);
        }
    }

    private View appView(Store.HomeItem it) {
        AppRepository.AppEntry app = apps.get(it.key);
        if (app == null) return null;
        return new AppIconView(getContext(), app, store.iconSize(), store.labels());
    }

    private View folderView(Store.HomeItem it) {
        Store.Group g = Store.find(store.groups(), it.folder);
        if (g == null) return null;
        return new FolderIconView(getContext(), g, folderApps(g.name), store, it.w > 1 || it.h > 1);
    }

    /** Les applis d'un dossier, dans l'ordre alphabétique. */
    List<AppRepository.AppEntry> folderApps(String name) {
        List<AppRepository.AppEntry> out = new java.util.ArrayList<>();
        Store.Group g = Store.find(store.groups(), name);
        if (g == null) return out;
        // L'ordre choisi par l'utilisateur (Organiser) : place dans le cercle et applis visibles.
        Map<String, AppRepository.AppEntry> byKey = new HashMap<>();
        for (AppRepository.AppEntry e : sortedApps()) byKey.put(Store.key(e.component), e);
        for (String k : g.apps) {
            AppRepository.AppEntry e = byKey.get(k);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Nombre d'applis visibles sur l'icône du dossier (grille ou mini-icônes), 0 si aucune. */
    int folderVisibleCount(Store.HomeItem it) {
        for (Map.Entry<View, Store.HomeItem> e : viewItems.entrySet()) {
            if (e.getValue() == it && e.getKey() instanceof FolderIconView) {
                return ((FolderIconView) e.getKey()).visibleCount();
            }
        }
        return 0;
    }

    /** Toutes les applis, dans l'ordre alphabétique. */
    List<AppRepository.AppEntry> sortedApps() {
        return lastList == null ? new java.util.ArrayList<>() : lastList;
    }

    /** Dossier en mode « icônes directes » ? */
    boolean folderDirect(Store.HomeItem it) {
        if (it == null || it.folder == null) return false;
        Store.Group g = Store.find(store.groups(), it.folder);
        return g != null && g.direct && g.style != 3;
    }

    /**
     * Appli touchée dans un dossier à icônes directes (x, y en coordonnées de la grille), ou
     * null si le toucher n'est pas sur une mini-icône (ou sur « +n »).
     */
    AppRepository.AppEntry folderAppAt(Store.HomeItem it, float x, float y) {
        for (Map.Entry<View, Store.HomeItem> e : viewItems.entrySet()) {
            if (e.getValue() != it || !(e.getKey() instanceof FolderIconView)) continue;
            View v = e.getKey();
            int i = ((FolderIconView) v).appIndexAt(x - v.getLeft(), y - v.getTop());
            List<AppRepository.AppEntry> apps = folderApps(it.folder);
            return i >= 0 && i < apps.size() ? apps.get(i) : null;
        }
        return null;
    }

    /** Facteur de taille des icônes d'un dossier (Personnaliser > Taille des icônes). */
    float folderIconFactor(String name) {
        Store.Group g = Store.find(store.groups(), name);
        float[] factors = {0.55f, 0.75f, 1f, 1.3f, 1.6f};
        return g == null ? 1f : factors[Math.max(0, Math.min(4, g.iconSize))];
    }

    int folderOpenStyle(String name) {
        Store.Group g = Store.find(store.groups(), name);
        return g == null ? 0 : g.openStyle;
    }

    boolean folderShowName(String name) {
        Store.Group g = Store.find(store.groups(), name);
        return g == null || g.showName;
    }

    int folderColor(String name) {
        Store.Group g = Store.find(store.groups(), name);
        return g == null ? 0xFF8FA6FF : g.color;
    }

    private AppWidgetHostView widgetView(Store.HomeItem it) {
        AppWidgetHostView v = widgetViews.get(it.widgetId);
        if (v != null) return v;
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(it.widgetId);
        if (info == null) return null;
        v = widgetHost.createView(getContext().getApplicationContext(), it.widgetId, info);
        v.setAppWidget(it.widgetId, info);
        widgetViews.put(it.widgetId, v);
        return v;
    }

    @SuppressWarnings("deprecation")
    private void sizeWidget(AppWidgetHostView v, Store.HomeItem it) {
        int wDp = (int) (it.w * cellW / dp), hDp = (int) (it.h * cellH / dp);
        if (Build.VERSION.SDK_INT >= 31) {
            v.updateAppWidgetSize(new Bundle(), Collections.singletonList(new SizeF(wDp, hDp)));
        } else {
            v.updateAppWidgetSize(null, wDp, hDp, wDp, hDp);
        }
    }

    // ------------------------------------------------------------ cases

    /** Nombre de cases nécessaires pour un widget. */
    int[] spanFor(AppWidgetProviderInfo info) {
        computeGrid();
        int w = 0, h = 0;
        if (Build.VERSION.SDK_INT >= 31) {
            w = info.targetCellWidth;
            h = info.targetCellHeight;
        }
        if (w <= 0) w = (int) Math.ceil(info.minWidth / Math.max(1f, cellW));
        if (h <= 0) h = (int) Math.ceil(info.minHeight / Math.max(1f, cellH));
        return new int[]{Math.max(1, Math.min(cols, w)), Math.max(1, Math.min(Math.max(1, rows), h))};
    }

    boolean fits(int col, int row, int w, int h, Store.HomeItem exclude) {
        if (col < 0 || row < 0 || col + w > cols || row + h > rows) return false;
        for (Store.HomeItem o : items) {
            if (o == exclude || o.col < 0) continue;
            if (col < o.col + o.w && o.col < col + w && row < o.row + o.h && o.row < row + h) return false;
        }
        return true;
    }

    int[] findFree(int w, int h, Store.HomeItem exclude) {
        for (int r = 0; r + h <= rows; r++) {
            for (int c = 0; c + w <= cols; c++) {
                if (fits(c, r, w, h, exclude)) return new int[]{c, r};
            }
        }
        return null;
    }

    /** Ajoute un widget à la première place libre. Renvoie false s'il n'y a pas de place. */
    boolean addWidget(int widgetId, int w, int h) {
        items = store.homeItems();
        computeGrid();
        int[] free = findFree(w, h, null);
        if (free == null) return false;
        Store.HomeItem it = new Store.HomeItem();
        it.widget = true;
        it.widgetId = widgetId;
        it.w = w;
        it.h = h;
        it.col = free[0];
        it.row = free[1];
        items.add(it);
        store.setHomeItems(items);
        rebuild();
        return true;
    }

    void remove(Store.HomeItem it) {
        items = store.homeItems();
        Store.HomeItem match = same(it);
        if (match != null) items.remove(match);
        if (it.widget) {
            widgetHost.deleteAppWidgetId(it.widgetId);
            widgetViews.remove(it.widgetId);
        }
        store.setHomeItems(items);
        rebuild();
    }

    /** Change la taille ; déplace l'élément si la nouvelle taille ne tient pas sur place. */
    boolean resize(Store.HomeItem it, int w, int h) {
        items = store.homeItems();
        Store.HomeItem match = same(it);
        if (match == null) return false;
        if (fits(match.col, match.row, w, h, match)) {
            match.w = w;
            match.h = h;
        } else {
            int[] free = findFree(w, h, match);
            if (free == null) return false;
            match.col = free[0];
            match.row = free[1];
            match.w = w;
            match.h = h;
        }
        store.setHomeItems(items);
        rebuild();
        return true;
    }

    private Store.HomeItem same(Store.HomeItem it) {
        for (Store.HomeItem o : items) {
            if (o.widget != it.widget) continue;
            if (it.widget ? o.widgetId == it.widgetId
                    : it.folder != null ? it.folder.equals(o.folder)
                    : o.folder == null && it.key != null && it.key.equals(o.key)) return o;
        }
        return null;
    }

    // ------------------------------------------------------------ toucher

    /** L'élément sous le doigt (coordonnées de la grille), ou null. */
    Store.HomeItem itemAt(float x, float y) {
        for (Map.Entry<View, Store.HomeItem> e : viewItems.entrySet()) {
            View v = e.getKey();
            if (x >= v.getLeft() && x < v.getRight() && y >= v.getTop() && y < v.getBottom()) return e.getValue();
        }
        return null;
    }

    AppRepository.AppEntry appOf(Store.HomeItem it) {
        return it.widget ? null : apps.get(it.key);
    }

    Rect screenBoundsOf(Store.HomeItem it) {
        for (Map.Entry<View, Store.HomeItem> e : viewItems.entrySet()) {
            if (e.getValue() == it) {
                int[] loc = new int[2];
                e.getKey().getLocationOnScreen(loc);
                return new Rect(loc[0], loc[1], loc[0] + e.getKey().getWidth(), loc[1] + e.getKey().getHeight());
            }
        }
        return null;
    }

    void startDrag(Store.HomeItem it, float x, float y) {
        for (Map.Entry<View, Store.HomeItem> e : viewItems.entrySet()) {
            if (e.getValue() == it) {
                dragView = e.getKey();
                dragItem = it;
                dragStartX = x;
                dragStartY = y;
                dragView.bringToFront();
                dragView.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.85f).setDuration(120).start();
                invalidateIcons(); // dossiers invisibles : leur contour apparaît
                return;
            }
        }
    }

    void moveDrag(float x, float y) {
        if (dragView == null) return;
        dragView.setTranslationX(x - dragStartX);
        dragView.setTranslationY(y - dragStartY);
    }

    /**
     * Dépose l'élément. Une appli lâchée sur une autre appli crée un dossier ; sur un dossier,
     * elle y entre. Sinon : la case la plus proche, si elle est libre.
     */
    void endDrag(float fx, float fy) {
        if (dragView == null) return;
        Store.HomeItem it = dragItem;
        Store.HomeItem target = null;
        if (!it.widget && it.folder == null) {
            for (Map.Entry<View, Store.HomeItem> e : viewItems.entrySet()) {
                View v = e.getKey();
                Store.HomeItem o = e.getValue();
                if (o == it || o.widget) continue;
                if (fx >= v.getLeft() && fx < v.getRight() && fy >= v.getTop() && fy < v.getBottom()) target = o;
            }
        }
        if (target != null) {
            resetDragView();
            if (target.folder != null) {
                store.addToFolder(target.folder, it.key);
            } else {
                List<String> both = new java.util.ArrayList<>();
                both.add(target.key);
                both.add(it.key);
                String name = store.freeFolderName("Dossier");
                int col = target.col, row = target.row;
                items.remove(target);
                store.setHomeItems(items);
                store.createFolder(name, both, col, row);
                items = store.homeItems();
            }
            // L'appli déposée quitte l'accueil : elle est dans le dossier.
            items = store.homeItems();
            Store.HomeItem match = same(it);
            if (match != null) items.remove(match);
            store.setHomeItems(items);
            rebuild();
            return;
        }
        float nx = dragView.getLeft() + dragView.getTranslationX();
        float ny = dragView.getTop() + dragView.getTranslationY();
        int col = Math.round((nx - left) / cellW), row = Math.round((ny - top) / cellH);
        resetDragView();
        if (fits(col, row, it.w, it.h, it)) {
            it.col = col;
            it.row = row;
            store.setHomeItems(items);
        }
        rebuild();
    }

    private void resetDragView() {
        dragView.animate().cancel();
        dragView.setScaleX(1f);
        dragView.setScaleY(1f);
        dragView.setAlpha(1f);
        dragView.setTranslationX(0);
        dragView.setTranslationY(0);
        dragView = null;
        dragItem = null;
    }

    /** Centre d'un élément, en coordonnées de la grille. */
    float[] centerOf(Store.HomeItem it) {
        for (Map.Entry<View, Store.HomeItem> e : viewItems.entrySet()) {
            if (e.getValue() == it) {
                View v = e.getKey();
                float iconCy = v.getTop() + v.getHeight() / 2f - (store.labels() ? 10 * dp : 0);
                return new float[]{(v.getLeft() + v.getRight()) / 2f, iconCy};
            }
        }
        return null;
    }

    /** Redessine les icônes (pastilles de notification). */
    void invalidateIcons() {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (!(c instanceof AppWidgetHostView)) c.invalidate();
        }
    }

    boolean isDragging() {
        return dragView != null || externalDrag;
    }

    private boolean externalDrag;

    // ------------------------------------------------------------ redimensionner en étirant

    private Store.HomeItem resizeItem;
    private int resizeW, resizeH;
    private boolean resizeOk = true;

    /** Affiche le cadre et ses poignées autour de l'élément. */
    void startResize(Store.HomeItem it) {
        items = store.homeItems();
        resizeItem = same(it);
        if (resizeItem == null) return;
        resizeW = resizeItem.w;
        resizeH = resizeItem.h;
        resizeOk = true;
        invalidate();
    }

    boolean isResizing() {
        return resizeItem != null;
    }

    void endResize() {
        if (resizeItem == null) return;
        resizeItem = null;
        rebuild();
        invalidate();
    }

    private float[] frame() {
        float l = left + resizeItem.col * cellW, t = top + resizeItem.row * cellH;
        return new float[]{l, t, l + resizeW * cellW, t + resizeH * cellH};
    }

    /** Poignée sous (x, y) : 0 bord droit, 1 bord bas, 2 coin ; -1 aucune. */
    int handleAt(float x, float y) {
        if (resizeItem == null) return -1;
        float[] f = frame();
        float[][] hs = {{f[2], (f[1] + f[3]) / 2}, {(f[0] + f[2]) / 2, f[3]}, {f[2], f[3]}};
        int best = -1;
        double bestD = 34 * dp;
        for (int i = 0; i < 3; i++) {
            double d = Math.hypot(x - hs[i][0], y - hs[i][1]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    void dragHandle(int h, float x, float y) {
        if (resizeItem == null) return;
        float l = left + resizeItem.col * cellW, t = top + resizeItem.row * cellH;
        if (h == 0 || h == 2) {
            resizeW = Math.max(1, Math.min(cols - resizeItem.col, Math.round((x - l) / cellW)));
        }
        if (h == 1 || h == 2) {
            resizeH = Math.max(1, Math.min(rows - resizeItem.row, Math.round((y - t) / cellH)));
        }
        resizeOk = fits(resizeItem.col, resizeItem.row, resizeW, resizeH, resizeItem);
        invalidate();
    }

    /** Applique la taille visée si elle tient ; le cadre reste affiché pour d'autres réglages. */
    void applyResize() {
        if (resizeItem == null) return;
        if (resizeOk && (resizeW != resizeItem.w || resizeH != resizeItem.h)) {
            resizeItem.w = resizeW;
            resizeItem.h = resizeH;
            store.setHomeItems(items);
            Store.HomeItem keep = resizeItem;
            resizeItem = null;
            rebuild();
            items = store.homeItems();
            resizeItem = same(keep);
        } else {
            resizeW = resizeItem.w;
            resizeH = resizeItem.h;
            resizeOk = true;
        }
        invalidate();
    }

    private void drawResize(Canvas canvas) {
        float[] f = frame();
        int accent = store.accent();
        int color = resizeOk ? accent : 0xFFFF6B6B;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        android.graphics.RectF r = new android.graphics.RectF(f[0] + 3 * dp, f[1] + 3 * dp, f[2] - 3 * dp, f[3] - 3 * dp);
        p.setColor((color & 0x00FFFFFF) | 0x22000000);
        canvas.drawRoundRect(r, 18 * dp, 18 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f * dp);
        p.setColor(color);
        canvas.drawRoundRect(r, 18 * dp, 18 * dp, p);
        float[][] hs = {{f[2] - 3 * dp, (f[1] + f[3]) / 2}, {(f[0] + f[2]) / 2, f[3] - 3 * dp}, {f[2] - 3 * dp, f[3] - 3 * dp}};
        for (float[] h : hs) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            canvas.drawCircle(h[0], h[1], 11 * dp, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(color);
            canvas.drawCircle(h[0], h[1], 11 * dp, p);
        }
        String s = resizeW + " × " + resizeH;
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(16 * dp);
        tp.setFakeBoldText(true);
        float cx = (f[0] + f[2]) / 2, cy = (f[1] + f[3]) / 2;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xE01B1D26);
        canvas.drawRoundRect(cx - 34 * dp, cy - 16 * dp, cx + 34 * dp, cy + 16 * dp, 16 * dp, 16 * dp, p);
        tp.setColor(color);
        canvas.drawText(s, cx, cy + 6 * dp, tp);
    }

    /** Une appli sortie d'un dossier est en cours de glisser : on montre la grille. */
    void setExternalDrag(boolean on) {
        externalDrag = on;
        invalidateIcons();
        invalidate();
    }

    /**
     * Dépose une appli venue d'un dossier au point (x, y) de la grille :
     * sur un dossier → elle y passe ; sur une appli → nouveau dossier avec les deux ;
     * sur une case vide → posée sur l'accueil. Elle quitte son dossier d'origine.
     * Renvoie un message à afficher, ou null si tout s'est bien passé.
     */
    String dropApp(String key, String fromFolder, float x, float y) {
        items = store.homeItems();
        computeGrid();
        Store.HomeItem target = null;
        for (Map.Entry<View, Store.HomeItem> e : viewItems.entrySet()) {
            View v = e.getKey();
            if (x >= v.getLeft() && x < v.getRight() && y >= v.getTop() && y < v.getBottom()) target = e.getValue();
        }
        if (target != null) {
            Store.HomeItem t = same(target);
            if (t == null || t.widget) return "Impossible de déposer sur un widget";
            if (t.folder != null) {
                if (t.folder.equals(fromFolder)) return null; // même dossier : rien à faire
                store.addToFolder(t.folder, key);
            } else {
                if (key.equals(t.key)) return null;
                int col = t.col, row = t.row;
                items.remove(t);
                store.setHomeItems(items);
                List<String> both = new java.util.ArrayList<>();
                both.add(t.key);
                both.add(key);
                store.createFolder(store.freeFolderName("Dossier"), both, col, row);
            }
        } else {
            int col = (int) Math.floor((x - left) / cellW), row = (int) Math.floor((y - top) / cellH);
            // Si l'appli est déjà sur l'accueil, elle déménage dans la case visée.
            Store.HomeItem existing = null;
            for (Store.HomeItem o : items) if (!o.widget && o.folder == null && key.equals(o.key)) existing = o;
            if (!fits(col, row, 1, 1, existing)) return "Pas de place à cet endroit";
            if (existing == null) {
                existing = new Store.HomeItem();
                existing.key = key;
                items.add(existing);
            }
            existing.col = col;
            existing.row = row;
            existing.w = 1;
            existing.h = 1;
            store.setHomeItems(items);
        }
        // Elle quitte son dossier d'origine.
        List<Store.Group> gs = store.groups();
        Store.Group from = Store.find(gs, fromFolder);
        if (from != null && from.apps.remove(key)) store.setGroups(gs);
        items = store.homeItems();
        rebuild();
        return null;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (resizeItem != null) drawResize(canvas);
        if (dragView == null && !externalDrag && resizeItem == null) return;
        // Pendant un déplacement : la grille en pointillés discrets.
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x33FFFFFF);
        for (int r = 0; r <= rows; r++) {
            for (int c = 0; c <= cols; c++) {
                canvas.drawCircle(left + c * cellW, top + r * cellH, 2 * dp, p);
            }
        }
    }

    /**
     * Icône de dossier, selon sa personnalisation : grille des 4 premières applis, première
     * appli avec le nombre, ou initiale ; carré arrondi ou rond ; bordure de sa couleur.
     */
    private static final class FolderIconView extends View {
        private final Store.Group g;
        private final List<AppRepository.AppEntry> apps;
        private final int iconDp;
        private final boolean showLabel, border;
        private final int tileAlpha;
        private final float dp;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final Rect tb = new Rect();

        private final boolean large;
        /** Mini-icônes dessinées : {gauche, haut, droite, bas, index} (index -2 = « +n »). */
        private final List<float[]> spots = new java.util.ArrayList<>();

        FolderIconView(Context c, Store.Group g, List<AppRepository.AppEntry> apps, Store store, boolean large) {
            super(c);
            this.g = g;
            this.apps = apps;
            this.large = large;
            iconDp = store.iconSize();
            showLabel = store.labels();
            border = store.folderBorder();
            tileAlpha = (int) (255 * store.folderTile() / 100f);
            dp = getResources().getDisplayMetrics().density;
            label.setColor(darkText ? 0xFF15171C : Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
            label.setShadowLayer(3 * dp, 0, dp, darkText ? 0x88FFFFFF : 0xCC000000);
            big.setTextAlign(Paint.Align.CENTER);
            big.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float half = iconDp * dp / 2f;
            float block = 2 * half + (showLabel ? 22 * dp : 0);
            float cx = getWidth() / 2f, iy = Math.max(4 * dp, (getHeight() - block) / 2f) + half;
            float corner = g.shape == 1 ? half : half * 0.45f;
            r.set(cx - half, iy - half, cx + half, iy + half);
            spots.clear();

            if (g.style != 3 && (large || g.direct)) {
                drawPanel(canvas);
                return;
            }
            if (g.style == 3) {
                // Invisible : rien n'est dessiné, la case reste un dossier. Contour discret
                // seulement pendant un déplacement, pour le retrouver.
                if (getParent() instanceof HomeGrid && ((HomeGrid) getParent()).isDragging()) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.5f * dp);
                    paint.setColor(0x80FFFFFF);
                    paint.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * dp, 5 * dp}, 0));
                    canvas.drawRoundRect(r, corner, corner, paint);
                    paint.setPathEffect(null);
                    paint.setStyle(Paint.Style.FILL);
                }
                return;
            }
            if (g.style == 2) {
                // Initiale sur une pastille de la couleur du dossier.
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(g.color);
                canvas.drawRoundRect(r, corner, corner, paint);
                big.setColor(0xFF111320);
                big.setTextSize(half * 0.95f);
                String s = g.name.isEmpty() ? "?" : g.name.substring(0, 1).toUpperCase();
                big.getTextBounds(s, 0, s.length(), tb);
                canvas.drawText(s, cx, iy + tb.height() / 2f - tb.bottom, big);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF1B1D26);
                paint.setAlpha(tileAlpha);
                canvas.drawRoundRect(r, corner, corner, paint);
                if (border) {
                    paint.setColor(g.color);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(2 * dp);
                    canvas.drawRoundRect(r, corner, corner, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                if (g.style == 1) {
                    // Première appli en grand, nombre d'applis en pastille.
                    if (!apps.isEmpty() && apps.get(0).icon != null) {
                        float m = half * 0.72f;
                        Drawable d = apps.get(0).icon;
                        d.setBounds((int) (cx - m), (int) (iy - m), (int) (cx + m), (int) (iy + m));
                        d.draw(canvas);
                    }
                    float br = half * 0.36f;
                    float bx = cx + half - br * 0.6f, by = iy - half + br * 0.6f;
                    paint.setColor(g.color);
                    canvas.drawCircle(bx, by, br, paint);
                    big.setColor(0xFF111320);
                    big.setTextSize(br * 1.1f);
                    String n = String.valueOf(apps.size());
                    big.getTextBounds(n, 0, n.length(), tb);
                    canvas.drawText(n, bx, by + tb.height() / 2f - tb.bottom, big);
                } else {
                    float mini = half * 0.42f, gap = half * 0.08f;
                    for (int i = 0; i < Math.min(4, apps.size()); i++) {
                        Drawable d = apps.get(i).icon;
                        if (d == null) continue;
                        float mx = cx + (i % 2 == 0 ? -1 : 1) * (mini + gap / 2);
                        float my = iy + (i < 2 ? -1 : 1) * (mini + gap / 2);
                        d.setBounds((int) (mx - mini), (int) (my - mini), (int) (mx + mini), (int) (my + mini));
                        d.draw(canvas);
                    }
                }
            }
            Badges.draw(getContext(), canvas, cx, iy, half, Badges.count(getContext(), apps));
            if (!showLabel || !g.showName) return;
            CharSequence s = TextUtils.ellipsize(g.name, label, getWidth() - 8 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(s, 0, s.length(), cx, iy + half + 18 * dp, label);
        }

        /**
         * Grand dossier ou icônes directes : un panneau arrondi avec la grille de ses applis
         * (« +n » si elles ne tiennent pas toutes), et le nom dessous.
         */
        private void drawPanel(Canvas canvas) {
            float pad = 6 * dp, labelH = showLabel ? 22 * dp : 0;
            android.graphics.RectF box = new android.graphics.RectF(pad, pad, getWidth() - pad, getHeight() - pad - labelH);
            float corner = g.shape == 1 ? Math.min(box.width(), box.height()) / 2f : 18 * dp;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF1B1D26);
            paint.setAlpha(tileAlpha);
            canvas.drawRoundRect(box, corner, corner, paint);
            if (border) {
                paint.setColor(g.color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2 * dp);
                canvas.drawRoundRect(box, corner, corner, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            float inner = 8 * dp, gap = 6 * dp;
            float bw = box.width() - 2 * inner, bh = box.height() - 2 * inner;
            float[] factors = {0.55f, 0.75f, 1f, 1.3f, 1.6f};
            float factor = factors[Math.max(0, Math.min(4, g.iconSize))];
            float t = Math.max(14 * dp, Math.min(Math.min(bw, bh), 40 * dp * factor));
            int cols = Math.max(1, (int) ((bw + gap) / (t + gap)));
            int rows = Math.max(1, (int) ((bh + gap) / (t + gap)));
            int cap = cols * rows;
            int shown = apps.size() > cap ? cap - 1 : apps.size();
            float gw = cols * t + (cols - 1) * gap, gh = rows * t + (rows - 1) * gap;
            float x0 = box.left + (box.width() - gw) / 2f, y0 = box.top + (box.height() - gh) / 2f;
            for (int i = 0; i < Math.min(cap, apps.size() > cap ? cap : apps.size()); i++) {
                float l = x0 + (i % cols) * (t + gap), tp = y0 + (i / cols) * (t + gap);
                if (i < shown) {
                    Drawable d = apps.get(i).icon;
                    if (d != null) {
                        d.setBounds((int) l, (int) tp, (int) (l + t), (int) (tp + t));
                        d.draw(canvas);
                    }
                    spots.add(new float[]{l, tp, l + t, tp + t, i});
                } else {
                    paint.setColor(0x33FFFFFF);
                    canvas.drawCircle(l + t / 2, tp + t / 2, t / 2, paint);
                    big.setColor(Color.WHITE);
                    big.setTextSize(t * 0.38f);
                    String more = "+" + (apps.size() - shown);
                    big.getTextBounds(more, 0, more.length(), tb);
                    canvas.drawText(more, l + t / 2, tp + t / 2 + tb.height() / 2f - tb.bottom, big);
                    spots.add(new float[]{l, tp, l + t, tp + t, -2});
                }
            }
            Badges.draw(getContext(), canvas, box.right - 10 * dp, box.top + 10 * dp, 12 * dp,
                    Badges.count(getContext(), apps));
            if (!showLabel || !g.showName) return;
            CharSequence s = TextUtils.ellipsize(g.name, label, getWidth() - 8 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(s, 0, s.length(), getWidth() / 2f, box.bottom + 17 * dp, label);
        }

        /** Mini-icône sous (x, y) (coordonnées de la vue) : index de l'appli, -2 pour « +n », -1 sinon. */
        /** Applis dessinées sur l'icône : mini-icônes du panneau, ou les 4 de la grille compacte. */
        int visibleCount() {
            int n = 0;
            for (float[] sp : spots) if (sp[4] >= 0) n++;
            if (n == 0 && g.style == 0) n = Math.min(4, apps.size());
            if (n == 0 && g.style == 1) n = Math.min(1, apps.size());
            return n;
        }

        int appIndexAt(float x, float y) {
            for (float[] sp : spots) {
                float m = 4 * dp;
                if (x >= sp[0] - m && x <= sp[2] + m && y >= sp[1] - m && y <= sp[3] + m) return (int) sp[4];
            }
            return -1;
        }
    }

    // (méthodes de FolderIconView plus bas)

    /** Icône d'appli avec son nom. */
    private static final class AppIconView extends View {
        private final AppRepository.AppEntry app;
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private final int iconDp;
        private final boolean showLabel;

        AppIconView(Context c, AppRepository.AppEntry app, int iconDp, boolean showLabel) {
            super(c);
            this.app = app;
            this.iconDp = iconDp;
            this.showLabel = showLabel;
            dp = getResources().getDisplayMetrics().density;
            label.setColor(darkText ? 0xFF15171C : Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
            label.setShadowLayer(3 * dp, 0, dp, darkText ? 0x88FFFFFF : 0xCC000000);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float half = iconDp * dp / 2f;
            float block = 2 * half + (showLabel ? 22 * dp : 0);
            float cx = getWidth() / 2f, iy = Math.max(4 * dp, (getHeight() - block) / 2f) + half;
            Drawable icon = app.icon;
            if (icon != null) {
                icon.setBounds((int) (cx - half), (int) (iy - half), (int) (cx + half), (int) (iy + half));
                icon.draw(canvas);
            }
            Badges.draw(getContext(), canvas, cx, iy, half, Badges.count(getContext(), app));
            if (!showLabel) return;
            CharSequence s = TextUtils.ellipsize(app.label, label, getWidth() - 8 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(s, 0, s.length(), cx, iy + half + 18 * dp, label);
        }
    }
}
