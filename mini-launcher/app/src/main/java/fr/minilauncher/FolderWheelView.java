package fr.minilauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Un dossier de l'accueil ouvert en cercle : ses applis se déploient autour de l'icône.
 * Doigt posé → glisser jusqu'à l'appli → lâcher. Un simple toucher laisse le cercle ouvert.
 * Près d'un bord, le cercle devient un éventail (un ou plusieurs arcs).
 */
final class FolderWheelView extends View {

    interface Listener {
        void onFolderLaunch(AppRepository.AppEntry app, Rect screenBounds);

        void onFolderAppMenu(AppRepository.AppEntry app, String folder);

        void onFolderClosed();
    }

    private static final long LONG_PRESS_MS = 550;

    /** Styles d'ouverture d'un dossier. */
    static final int CIRCLE = 0, COLUMN = 1, GRID = 2, LIST = 3;
    static final String[] STYLES = {"Cercle", "Colonne (glisser vertical)", "Grille de tuiles", "Liste"};

    private int style = CIRCLE;
    private boolean showName = true;
    private final android.graphics.RectF panel = new android.graphics.RectF();
    private boolean panelUp;
    private final TextPaint rowText = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    void setStyle(int s) {
        style = Math.max(0, Math.min(3, s));
    }

    void setShowName(boolean show) {
        showName = show;
    }

    private static final class Spot {
        float x, y;
        float l, t, r, b; // case touchable (styles en panneau)
        AppRepository.AppEntry app;
    }

    private final float dp;
    private final Store store;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Rect safe = new Rect();
    private final List<Spot> spots = new ArrayList<>();
    private final List<float[]> arcs = new ArrayList<>(); // {rayon, début, balayage}

    private Listener listener;
    private String folder;
    private boolean realFolder = true;
    private float iconFactor = 1f; // taille des icônes propre au dossier (Personnaliser)

    /** Taille des icônes du cercle : réglage général × réglage du dossier. */
    private float iconPxBase() {
        return store.folderIcon() * dp * iconFactor;
    }

    /** Facteur de taille d'icônes du dossier qui va s'ouvrir (1 = normal). */
    void setIconFactor(float f) {
        iconFactor = f;
    }
    private int color;
    private float cx, cy;
    private boolean open, sticky, moved, tracking;
    private int hover = -1;
    private float downX, downY;
    private long openedAt;

    /** Appli soulevée (maintenue) dans un vrai dossier : l'accueil prend le relais du glisser. */
    interface LiftListener {
        void onLift(AppRepository.AppEntry app, String folder, float x, float y);
    }

    private LiftListener liftListener;
    private float holdX, holdY, lastX, lastY;

    void setLiftListener(LiftListener l) {
        liftListener = l;
    }

    /** Ouvre un sous-dossier à la place du dossier affiché (même centre). */
    interface SubOpener {
        void openSub(String name, float x, float y);
    }

    private SubOpener subOpener;

    void setSubOpener(SubOpener o) {
        subOpener = o;
    }

    private static boolean isSub(Spot s) {
        return s != null && Store.isSub(s.app.component);
    }

    /** Glisser et rester un instant sur un sous-dossier l'ouvre, le doigt continue dedans. */
    private final Runnable subDwell = () -> {
        if (!open || !tracking || hover < 0 || hover >= spots.size()) return;
        Spot s = spots.get(hover);
        if (!isSub(s) || subOpener == null) return;
        haptic(HapticFeedbackConstants.VIRTUAL_KEY);
        subOpener.openSub(s.app.label, cx, cy);
        tracking = true;
        moved = true;
    };

    void makeSticky() {
        sticky = true;
        hover = -1;
        invalidate();
    }

    private final Runnable longPress = () -> {
        if (hover < 0 || listener == null || !tracking) return;
        tracking = false;
        haptic(HapticFeedbackConstants.LONG_PRESS);
        AppRepository.AppEntry app = spots.get(hover).app;
        if (Store.isSub(app.component) && !(realFolder && liftListener != null)) {
            listener.onFolderAppMenu(app, folder); // menu du sous-dossier
            return;
        }
        if (realFolder && liftListener != null) {
            String f = folder;
            close();
            liftListener.onLift(app, f, lastX, lastY);
        } else {
            listener.onFolderAppMenu(app, realFolder ? folder : null);
        }
    };

    FolderWheelView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        store = Store.get(context);
        band.setStyle(Paint.Style.STROKE);
        band.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
        small.setColor(Color.WHITE);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);
        rowText.setColor(Color.WHITE);
        rowText.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        setHapticFeedbackEnabled(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setSafe(Rect r) {
        safe.set(r);
    }

    boolean isOpen() {
        return open;
    }

    /** L'utilisateur a-t-il bougé le doigt depuis l'ouverture ? */
    boolean hasMoved() {
        return moved;
    }

    /** Les touchers n'arrivent que par {@link #handleTouch}. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return false;
    }

    /** Ouvre le dossier autour du point (x, y) de la vue. */
    void open(String folder, int color, List<AppRepository.AppEntry> apps, float x, float y) {
        open(folder, color, apps, x, y, true);
    }

    /** {@code realFolder} = false pour les applis récentes (pas de « Retirer du dossier »). */
    void open(String folder, int color, List<AppRepository.AppEntry> apps, float x, float y, boolean realFolder) {
        if (!realFolder) {
            iconFactor = 1f;
            style = CIRCLE;
            showName = true;
        }
        this.realFolder = realFolder;
        this.folder = folder;
        this.color = color;
        cx = x;
        cy = y;
        open = true;
        sticky = false;
        hover = -1;
        openedAt = SystemClock.uptimeMillis();
        layout(apps);
        invalidate();
    }

    /** Applis liées d'une appli : même affichage qu'un dossier, dans le style choisi. */
    void openLinked(String title, int color, List<AppRepository.AppEntry> apps, float x, float y, int s) {
        open(title, color, apps, x, y, false);
        setStyle(s);
        layout(apps);
        invalidate();
    }

    void close() {
        removeCallbacks(longPress);
        removeCallbacks(subDwell);
        boolean was = open;
        open = false;
        sticky = false;
        tracking = false;
        spots.clear();
        invalidate();
        if (was && listener != null) listener.onFolderClosed();
    }

    private void haptic(int type) {
        if (store.haptic()) performHapticFeedback(type);
    }

    // ------------------------------------------------------------ mise en page

    private void layout(List<AppRepository.AppEntry> apps) {
        spots.clear();
        arcs.clear();
        int n = apps.size();
        if (n == 0) return;
        if (style != CIRCLE) {
            layoutPanel(apps);
            return;
        }
        float scale = store.folderRadius() / 100f;
        float icon = iconPxBase();
        boolean labels = store.folderLabels();
        float spacing = Math.max(icon + 14 * dp, labels ? 70 * dp : 0);
        float step = icon + (labels ? 38 : 22) * dp;
        int placed = 0;
        float r = Math.max(60 * dp, 88 * dp * scale) + (icon - 46 * dp) / 2f;
        for (int guard = 0; placed < n && guard < 10; guard++, r += step) {
            float[] arc = visibleArc(r);
            if (arc == null) continue;
            double len = Math.toRadians(arc[1]) * r;
            int cap = arc[1] >= 360f ? (int) (len / spacing) : (int) (len / spacing) + 1;
            int take = Math.min(n - placed, Math.max(0, cap));
            if (take <= 0) continue;
            boolean full = arc[1] >= 360f;
            float da = full ? 360f / take : (take > 1 ? arc[1] / (take - 1) : 0f);
            float first = full ? -90f : (take > 1 ? arc[0] : arc[0] + arc[1] / 2f);
            for (int i = 0; i < take; i++) {
                double a = Math.toRadians(first + i * da);
                Spot s = new Spot();
                s.x = cx + (float) (r * Math.cos(a));
                s.y = cy + (float) (r * Math.sin(a));
                s.app = apps.get(placed + i);
                spots.add(s);
            }
            arcs.add(full ? new float[]{r, 0f, 360f} : new float[]{r, first, da * (take - 1)});
            placed += take;
        }
    }

    /**
     * Colonne, grille ou liste : un panneau collé au dossier, au-dessus ou en dessous selon la
     * place. La première appli est la plus proche du dossier (le doigt part de là).
     */
    private void layoutPanel(List<AppRepository.AppEntry> apps) {
        int n = apps.size();
        float icon = iconPxBase();
        boolean labels = style != COLUMN || store.folderLabels();
        float left = safe.left + 12 * dp, right = getWidth() - safe.right - 12 * dp;
        float top = Math.max(safe.top, 24 * dp) + 12 * dp, bottom = getHeight() - Math.max(safe.bottom, 16 * dp) - 12 * dp;
        float gap = 38 * dp, pad = 10 * dp;
        float above = cy - gap - top, below = bottom - (cy + gap);
        panelUp = above >= below;
        float avail = Math.max(icon, panelUp ? above : below);
        float cellW, cellH;
        if (style == LIST) {
            cellW = Math.min(250 * dp, right - left - 2 * pad);
            cellH = Math.max(icon, 34 * dp) + 14 * dp;
        } else if (style == GRID) {
            cellW = Math.max(icon + 24 * dp, 78 * dp);
            cellH = icon + 36 * dp;
        } else {
            cellW = icon + 18 * dp;
            cellH = icon + (labels ? 32 : 14) * dp;
        }
        int maxCols = Math.max(1, (int) ((right - left - 2 * pad) / cellW));
        int rowsFit = Math.max(1, (int) ((avail - 2 * pad) / cellH));
        int cols, rows;
        if (style == GRID) {
            cols = Math.min(maxCols, Math.max(Math.min(4, n), (n + rowsFit - 1) / rowsFit));
            rows = (n + cols - 1) / cols;
        } else {
            rows = Math.min(n, rowsFit);
            cols = Math.min(maxCols, (n + rows - 1) / rows);
        }
        rows = Math.min(rows, rowsFit);
        int count = Math.min(n, rows * cols);
        float pw = cols * cellW + 2 * pad, ph = rows * cellH + 2 * pad;
        float px = Math.max(left, Math.min(right - pw, cx - pw / 2f));
        float py = panelUp ? cy - gap - ph : cy + gap;
        py = Math.max(top, Math.min(bottom - ph, py));
        panel.set(px, py, px + pw, py + ph);
        for (int i = 0; i < count; i++) {
            int c, r;
            if (style == GRID) {
                c = i % cols;
                r = i / cols;
            } else {
                c = i / rows;
                r = i % rows;
            }
            if (panelUp) r = rows - 1 - r; // la première appli au plus près du dossier
            Spot s = new Spot();
            s.l = px + pad + c * cellW;
            s.t = py + pad + r * cellH;
            s.r = s.l + cellW;
            s.b = s.t + cellH;
            if (style == LIST) {
                s.x = s.l + 10 * dp + icon / 2f;
                s.y = (s.t + s.b) / 2f;
            } else {
                s.x = (s.l + s.r) / 2f;
                s.y = (s.t + s.b) / 2f - (labels ? 9 * dp : 0);
            }
            s.app = apps.get(i);
            spots.add(s);
        }
    }

    /** Plus grande portion du cercle de rayon r qui tient dans l'écran : {début, balayage}. */
    private float[] visibleArc(float r) {
        float m = 34 * dp;
        float left = safe.left + m, top = Math.max(safe.top, 24 * dp) + m;
        float right = getWidth() - safe.right - m, bottom = getHeight() - Math.max(safe.bottom, 16 * dp) - m;
        boolean[] ok = new boolean[360];
        int count = 0;
        for (int a = 0; a < 360; a++) {
            double t = Math.toRadians(a);
            float x = cx + (float) (r * Math.cos(t)), y = cy + (float) (r * Math.sin(t));
            ok[a] = x >= left && x <= right && y >= top && y <= bottom;
            if (ok[a]) count++;
        }
        if (count == 360) return new float[]{-90f, 360f};
        if (count == 0) return null;
        int bestStart = 0, bestLen = 0;
        for (int a = 0; a < 360; a++) {
            if (!ok[a] || ok[(a + 359) % 360]) continue;
            int len = 0;
            while (len < 360 && ok[(a + len) % 360]) len++;
            if (len > bestLen) {
                bestLen = len;
                bestStart = a;
            }
        }
        if (bestLen > 4) return new float[]{bestStart + 1, bestLen - 3};
        return new float[]{bestStart, Math.max(0, bestLen - 1)};
    }

    private int spotAt(float x, float y) {
        if (style != CIRCLE) {
            for (int i = 0; i < spots.size(); i++) {
                Spot s = spots.get(i);
                if (x >= s.l && x < s.r && y >= s.t && y < s.b) return i;
            }
        }
        int best = -1;
        double bestD = Math.max(26 * dp, iconPxBase() * 0.75f);
        for (int i = 0; i < spots.size(); i++) {
            double d = Math.hypot(x - spots.get(i).x, y - spots.get(i).y);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ toucher

    boolean handleTouch(MotionEvent e) {
        if (!open) return true;
        float x = e.getX(), y = e.getY();
        lastX = x;
        lastY = y;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                downX = x;
                downY = y;
                moved = false;
                if (sticky) {
                    hover = spotAt(x, y);
                    holdX = x;
                    holdY = y;
                    if (hover >= 0) postDelayed(longPress, LONG_PRESS_MS);
                    else if (Math.hypot(x - cx, y - cy) > 34 * dp) close();
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                if (!moved && Math.hypot(x - downX, y - downY) > 10 * dp) moved = true;
                int h = spotAt(x, y);
                if (h != hover) {
                    hover = h;
                    if (h >= 0) haptic(HapticFeedbackConstants.CLOCK_TICK);
                    removeCallbacks(subDwell);
                    if (h >= 0 && isSub(spots.get(h))) postDelayed(subDwell, 450);
                    invalidate();
                }
                // Un doigt qui glisse ne soulève jamais d'appli : seul l'appui long commencé
                // directement sur l'appli (cercle resté ouvert) le fait. Au lâcher, l'appli s'ouvre.
                if (Math.hypot(x - holdX, y - holdY) > 12 * dp) removeCallbacks(longPress);
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                removeCallbacks(subDwell);
                if (!tracking) return true;
                tracking = false;
                int hit = spotAt(x, y);
                if (hit >= 0 && (moved || sticky)) {
                    launch(spots.get(hit));
                } else if (sticky && !moved && hit < 0 && Math.hypot(x - cx, y - cy) <= 34 * dp) {
                    close(); // toucher le centre referme
                } else {
                    sticky = true; // le cercle reste ouvert pour un toucher
                    hover = -1;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                tracking = false;
                close();
                return true;
        }
        return true;
    }

    private void launch(Spot s) {
        if (isSub(s) && subOpener != null) {
            subOpener.openSub(s.app.label, cx, cy);
            makeSticky(); // ouvert d'un toucher ou d'un lâcher : il attend le choix
            return;
        }
        if (listener == null) return;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int half = (int) (24 * dp);
        int sx = (int) s.x + loc[0], sy = (int) s.y + loc[1];
        listener.onFolderLaunch(s.app, new Rect(sx - half, sy - half, sx + half, sy + half));
        close();
    }

    // ------------------------------------------------------------ dessin

    @Override
    protected void onDraw(Canvas canvas) {
        if (!open) return;
        float f = store.speedFactor();
        float t = f == 0f ? 1f : Math.min(1f, (SystemClock.uptimeMillis() - openedAt) / (200f * f));
        float ease = 1f - (1f - t) * (1f - t);
        canvas.drawColor(Color.argb((int) (255 * store.dimPercent() / 100f * ease), 0, 0, 0));

        boolean labels = store.folderLabels();
        float iconPx = iconPxBase();
        band.setColor(0xFF000000 | store.folderBg());
        band.setAlpha((int) (255 * store.folderBgAlpha() / 100f * ease));
        band.setStrokeWidth(iconPx + (labels ? 34 : 16) * dp);
        if (style != CIRCLE && !spots.isEmpty()) {
            fill.setColor(0xFF000000 | store.folderBg());
            fill.setAlpha((int) (255 * store.folderBgAlpha() / 100f * ease));
            float rr = 22 * dp;
            canvas.drawRoundRect(panel, rr, rr, fill);
        }
        for (float[] arc : arcs) {
            float r = arc[0] * (0.5f + 0.5f * ease);
            if (arc[2] >= 360f) {
                canvas.drawCircle(cx, cy, r, band);
            } else {
                oval.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(oval, arc[1], arc[2], false, band);
            }
        }

        float half = iconPx / 2f;
        for (int i = 0; i < spots.size(); i++) {
            Spot s = spots.get(i);
            float k = Math.max(0f, Math.min(1f, ease * 1.4f - 0.4f * i / Math.max(1, spots.size())));
            if (k <= 0f) continue;
            float x = cx + (s.x - cx) * (0.5f + 0.5f * k), y = cy + (s.y - cy) * (0.5f + 0.5f * k);
            boolean h = i == hover;
            float hh = half * (h ? 1.18f : 1f);
            if (h) {
                fill.setColor(store.accent());
                fill.setAlpha(110);
                if (style == LIST || style == GRID) {
                    oval.set(s.l + 3 * dp, s.t + 3 * dp, s.r - 3 * dp, s.b - 3 * dp);
                    canvas.drawRoundRect(oval, 14 * dp, 14 * dp, fill);
                } else {
                    canvas.drawCircle(x, y, hh + 7 * dp, fill);
                }
            }
            Drawable icon = s.app.icon;
            if (icon != null) {
                icon.setBounds((int) (x - hh), (int) (y - hh), (int) (x + hh), (int) (y + hh));
                icon.setAlpha((int) (255 * k));
                icon.draw(canvas);
                icon.setAlpha(255);
            }
            Badges.draw(getContext(), canvas, x, y, hh, Badges.count(getContext(), s.app));

            if (style == LIST) {
                rowText.setAlpha((int) (255 * k));
                float tx = x + hh + 12 * dp;
                CharSequence nm = TextUtils.ellipsize(s.app.label, rowText, s.r - tx - 10 * dp, TextUtils.TruncateAt.END);
                canvas.drawText(nm, 0, nm.length(), tx, y + 5 * dp, rowText);
            } else if (labels || style == GRID) {
                small.setAlpha((int) (255 * k));
                float w = style == GRID ? (s.r - s.l - 6 * dp) : 64 * dp;
                CharSequence nm = TextUtils.ellipsize(s.app.label, small, w, TextUtils.TruncateAt.END);
                canvas.drawText(nm, 0, nm.length(), x, y + hh + 14 * dp, small);
            }
        }

        // Centre : la pastille du dossier, avec le nom de l'appli survolée ou du dossier.
        fill.setColor(color);
        fill.setAlpha(255);
        canvas.drawCircle(cx, cy, 26 * dp * (0.6f + 0.4f * ease), fill);
        // Grille et liste montrent déjà les noms ; nom du dossier seulement s'il est affiché.
        boolean hoverName = hover >= 0 && (style == CIRCLE || style == COLUMN);
        String title = hoverName ? spots.get(hover).app.label : showName ? folder : null;
        if (title == null) {
            if (t < 1f) postInvalidateOnAnimation();
            return;
        }
        CharSequence s = TextUtils.ellipsize(title, text, 220 * dp, TextUtils.TruncateAt.END);
        float tw = text.measureText(s, 0, s.length());
        float ty = cy - 40 * dp;
        if (style != CIRCLE) ty = panelUp ? cy + 52 * dp : cy - 40 * dp;
        else if (ty < Math.max(safe.top, 24 * dp) + 30 * dp) ty = cy + 52 * dp;
        fill.setColor(0xF01B1D26);
        oval.set(cx - tw / 2 - 12 * dp, ty - 20 * dp, cx + tw / 2 + 12 * dp, ty + 8 * dp);
        canvas.drawRoundRect(oval, 14 * dp, 14 * dp, fill);
        canvas.drawText(s, 0, s.length(), cx, ty, text);

        if (t < 1f) postInvalidateOnAnimation();
    }
}
