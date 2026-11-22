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

    static final int COLS = 4;

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

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::rebuild);
    }

    private boolean computeGrid() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return false;
        float pad = 10 * dp;
        left = safe.left + pad;
        cellW = (w - safe.left - safe.right - 2 * pad) / COLS;
        top = safe.top + 150 * dp;
        cellH = 100 * dp;
        float bottom = h - Math.max(safe.bottom, 16 * dp) - 8 * dp;
        rows = Math.max(1, (int) ((bottom - top) / cellH));
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
            } else if (appsLoaded && !apps.containsKey(it.key)) {
                items.remove(i);
                changed = true;
            }
        }
        // Placement des nouveaux et de ceux qui ne tiennent plus (écran plus petit).
        for (Store.HomeItem it : items) {
            it.w = Math.max(1, Math.min(COLS, it.w));
            it.h = Math.max(1, Math.min(rows, it.h));
            if (it.col < 0 || it.row < 0 || it.col + it.w > COLS || it.row + it.h > rows || !fits(it.col, it.row, it.w, it.h, it)) {
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
            View v = it.widget ? widgetView(it) : appView(it);
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
            if (it.widget) sizeWidget((AppWidgetHostView) v, it);
        }
        for (int i = getChildCount() - 1; i >= 0; i--) {
            if (!kept.contains(getChildAt(i))) removeViewAt(i);
        }
    }

    private View appView(Store.HomeItem it) {
        AppRepository.AppEntry app = apps.get(it.key);
        if (app == null) return null;
        return new AppIconView(getContext(), app);
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
        return new int[]{Math.max(1, Math.min(COLS, w)), Math.max(1, Math.min(Math.max(1, rows), h))};
    }

    boolean fits(int col, int row, int w, int h, Store.HomeItem exclude) {
        if (col < 0 || row < 0 || col + w > COLS || row + h > rows) return false;
        for (Store.HomeItem o : items) {
            if (o == exclude || o.col < 0) continue;
            if (col < o.col + o.w && o.col < col + w && row < o.row + o.h && o.row < row + h) return false;
        }
        return true;
    }

    int[] findFree(int w, int h, Store.HomeItem exclude) {
        for (int r = 0; r + h <= rows; r++) {
            for (int c = 0; c + w <= COLS; c++) {
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
            if (o.widget == it.widget && (it.widget ? o.widgetId == it.widgetId : o.key.equals(it.key))) return o;
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
                return;
            }
        }
    }

    void moveDrag(float x, float y) {
        if (dragView == null) return;
        dragView.setTranslationX(x - dragStartX);
        dragView.setTranslationY(y - dragStartY);
    }

    /** Dépose l'élément sur la case la plus proche si elle est libre. */
    void endDrag() {
        if (dragView == null) return;
        Store.HomeItem it = dragItem;
        float nx = dragView.getLeft() + dragView.getTranslationX();
        float ny = dragView.getTop() + dragView.getTranslationY();
        int col = Math.round((nx - left) / cellW), row = Math.round((ny - top) / cellH);
        dragView.animate().cancel();
        dragView.setScaleX(1f);
        dragView.setScaleY(1f);
        dragView.setAlpha(1f);
        dragView.setTranslationX(0);
        dragView.setTranslationY(0);
        dragView = null;
        dragItem = null;
        if (fits(col, row, it.w, it.h, it)) {
            it.col = col;
            it.row = row;
            store.setHomeItems(items);
        }
        rebuild();
    }

    boolean isDragging() {
        return dragView != null;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (dragView == null) return;
        // Pendant un déplacement : la grille en pointillés discrets.
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x33FFFFFF);
        for (int r = 0; r <= rows; r++) {
            for (int c = 0; c <= COLS; c++) {
                canvas.drawCircle(left + c * cellW, top + r * cellH, 2 * dp, p);
            }
        }
    }

    /** Icône d'appli avec son nom. */
    private static final class AppIconView extends View {
        private final AppRepository.AppEntry app;
        private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;

        AppIconView(Context c, AppRepository.AppEntry app) {
            super(c);
            this.app = app;
            dp = getResources().getDisplayMetrics().density;
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
            label.setShadowLayer(3 * dp, 0, dp, 0xCC000000);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, half = 26 * dp, iy = 12 * dp + half;
            Drawable icon = app.icon;
            if (icon != null) {
                icon.setBounds((int) (cx - half), (int) (iy - half), (int) (cx + half), (int) (iy + half));
                icon.draw(canvas);
            }
            CharSequence s = TextUtils.ellipsize(app.label, label, getWidth() - 8 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(s, 0, s.length(), cx, iy + half + 18 * dp, label);
        }
    }
}
