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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L'alphabet vertical sur les bords de l'accueil, comme l'index des Contacts.
 *
 * On pose le doigt sur un bord et on le fait glisser de haut en bas : la lettre sous le
 * doigt s'allume et ses applis apparaissent à côté, en direct. On glisse vers l'intérieur
 * jusqu'à l'appli et on lâche. Si on lâche sur l'alphabet, les applis restent affichées et
 * on peut les toucher. En haut : ★ les favoris ; en bas : les groupes.
 */
final class AlphaStripView extends View {

    interface Listener {
        void onStripLaunch(AppRepository.AppEntry app, Rect screenBounds);

        void onStripAppMenu(AppRepository.AppEntry app, String group);

        void onStripClosed();
    }

    static final int LEFT = 1, RIGHT = 2;

    private static final int PHASE_IDLE = 0;    // rien d'ouvert
    private static final int PHASE_LETTERS = 1; // le doigt parcourt l'alphabet
    private static final int PHASE_APPS = 2;    // le doigt est parti vers les applis
    private static final int PHASE_STICKY = 3;  // doigt levé, les applis restent affichées
    private static final long LONG_PRESS_MS = 550;

    /** Une entrée de l'alphabet : ★, une lettre, # ou un groupe. */
    private static final class Entry {
        final String label;
        final int color; // 0 = lettre ordinaire
        final boolean group;
        final List<AppRepository.AppEntry> apps = new ArrayList<>();

        Entry(String label, int color, boolean group) {
            this.label = label;
            this.color = color;
            this.group = group;
        }

        String shortLabel() {
            return group && label.length() > 2 ? label.substring(0, 2) : label;
        }
    }

    private static final class Cell {
        float x, y;
        AppRepository.AppEntry app;
    }

    private final float dp;
    private final Store store;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect tmp = new Rect();
    private final RectF rect = new RectF();
    private final Rect safe = new Rect();

    private Listener listener;
    private List<AppRepository.AppEntry> allApps = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();

    private int phase = PHASE_IDLE;
    private int side = LEFT;
    private int current = -1;        // entrée sélectionnée
    private float fingerY;
    private float startX;
    private final List<Cell> cells = new ArrayList<>();
    private int hoverCell = -1;
    private float panelCenterY;
    private float anim = 1f;         // apparition du panneau
    private long animStart;
    private boolean moved;
    private float downX, downY;
    private int accent;
    private boolean haptics;

    private final Runnable longPress = () -> {
        if (moved || hoverCell < 0 || listener == null) return;
        Cell c = cells.get(hoverCell);
        if (c.app == null) return;
        haptic(HapticFeedbackConstants.LONG_PRESS);
        Entry e = current >= 0 ? entries.get(current) : null;
        listener.onStripAppMenu(c.app, e != null && e.group ? e.label : null);
    };

    AlphaStripView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        store = Store.get(context);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        label.setColor(Color.WHITE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        label.setShadowLayer(3 * dp, 0, dp, 0xCC000000);
        setHapticFeedbackEnabled(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setSafe(Rect r) {
        safe.set(r);
        invalidate();
    }

    void setApps(List<AppRepository.AppEntry> apps) {
        allApps = new ArrayList<>(apps);
        refresh();
    }

    boolean isActive() {
        return phase != PHASE_IDLE;
    }

    /** Côtés où l'alphabet est actif (LEFT, RIGHT, ou les deux). */
    int sides() {
        int s = store.alphaSide();
        return s == 1 ? LEFT : s == 2 ? RIGHT : LEFT | RIGHT;
    }

    /** Relit les applis, favoris, groupes et réglages. */
    void refresh() {
        accent = store.accent();
        haptics = store.haptic();
        String currentLabel = current >= 0 && current < entries.size() ? entries.get(current).label : null;
        entries.clear();
        Map<String, AppRepository.AppEntry> byKey = new HashMap<>();
        for (AppRepository.AppEntry e : allApps) byKey.put(Store.key(e.component), e);

        // ★ favoris : épinglés, puis les plus utilisés
        Entry fav = new Entry("★", 0xFFFFE07A, false);
        for (String k : store.pinned()) {
            AppRepository.AppEntry e = byKey.get(k);
            if (e != null) fav.apps.add(e);
        }
        if (store.autoFavorites()) {
            for (Map.Entry<String, Integer> u : store.usage()) {
                if (fav.apps.size() >= Math.max(8, store.maxFav()) || u.getValue() < 2) break;
                AppRepository.AppEntry e = byKey.get(u.getKey());
                if (e != null && !fav.apps.contains(e)) fav.apps.add(e);
            }
        }
        if (!fav.apps.isEmpty()) entries.add(fav);

        Map<String, Entry> letters = new HashMap<>();
        for (String l : RadialView.LETTERS) {
            Entry en = new Entry(l, 0, false);
            entries.add(en);
            letters.put(l, en);
        }
        for (AppRepository.AppEntry e : allApps) {
            Entry en = letters.get(e.letter);
            if (en != null) en.apps.add(e);
        }
        for (Store.Group g : store.groups()) {
            Entry en = new Entry(g.name, g.color, true);
            Set<String> members = new HashSet<>(g.apps);
            for (AppRepository.AppEntry e : allApps) {
                if (members.contains(Store.key(e.component))) en.apps.add(e);
            }
            entries.add(en);
        }
        current = -1;
        if (currentLabel != null) {
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).label.equals(currentLabel)) current = i;
        }
        if (phase != PHASE_IDLE) {
            if (current < 0) close();
            else layoutPanel();
        }
        invalidate();
    }

    void close() {
        removeCallbacks(longPress);
        boolean was = phase != PHASE_IDLE;
        phase = PHASE_IDLE;
        current = -1;
        cells.clear();
        hoverCell = -1;
        invalidate();
        if (was && listener != null) listener.onStripClosed();
    }

    /** Démarre l'alphabet sur un côté (le DOWN suivra). */
    void begin(int side) {
        this.side = side;
    }

    private void haptic(int type) {
        if (haptics) performHapticFeedback(type);
    }

    // ------------------------------------------------------------ géométrie

    private float usableTop() {
        return Math.max(safe.top, 24 * dp) + 16 * dp;
    }

    private float usableBottom() {
        return getHeight() - Math.max(safe.bottom, 16 * dp) - 16 * dp;
    }

    /** Haut de l'alphabet : hauteur et position réglables. */
    private float top() {
        float u0 = usableTop(), u1 = usableBottom(), u = u1 - u0;
        float h = u * Math.max(20, Math.min(100, store.stripHeight())) / 100f;
        float c = u0 + u * store.stripPos() / 100f;
        return Math.max(u0, Math.min(u1 - h, c - h / 2f));
    }

    private float bottom() {
        float u = usableBottom() - usableTop();
        return top() + u * Math.max(20, Math.min(100, store.stripHeight())) / 100f;
    }

    /** Le doigt est-il à hauteur de l'alphabet (avec une petite marge) ? */
    boolean containsY(float y) {
        return y >= top() - 28 * dp && y <= bottom() + 28 * dp;
    }

    /** Grossissement d'une lettre selon sa distance au doigt (effet loupe). */
    private float zoomAt(float y) {
        int z = store.zoom();
        if (z == 0 || phase != PHASE_LETTERS) return 1f;
        float amp = z == 1 ? 0.8f : z == 3 ? 2.4f : 1.5f;
        float sigma = 30 * dp;
        float d = (y - fingerY) / sigma;
        return 1f + amp * (float) Math.exp(-d * d);
    }

    private float entryY(int i) {
        int n = entries.size();
        if (n <= 1) return (top() + bottom()) / 2f;
        return top() + (bottom() - top()) * i / (n - 1f);
    }

    private int entryAt(float y) {
        int n = entries.size();
        if (n == 0) return -1;
        float t = (y - top()) / Math.max(1f, bottom() - top());
        return Math.max(0, Math.min(n - 1, Math.round(t * (n - 1))));
    }

    /** Abscisse du centre de l'alphabet pour un côté. */
    private float stripX(int s) {
        return s == LEFT ? safe.left + 14 * dp : getWidth() - safe.right - 14 * dp;
    }

    /** Distance du doigt vers l'intérieur de l'écran, depuis son point de départ. */
    private float inward(float x) {
        return side == LEFT ? x - startX : startX - x;
    }

    /** Range les applis de l'entrée courante à côté de l'alphabet, centrées sur la lettre. */
    private void layoutPanel() {
        cells.clear();
        hoverCell = -1;
        if (current < 0) return;
        List<AppRepository.AppEntry> apps = entries.get(current).apps;
        int n = apps.size();
        if (n == 0) return;
        float cellW = 74 * dp, cellH = 80 * dp;
        float edgeGap = (store.zoom() >= 2 ? 78 : 64) * dp;
        float startPanel = Math.max(edgeGap, (side == LEFT ? startX - stripX(LEFT) : stripX(RIGHT) - startX) + 36 * dp);
        float avail = getWidth() - safe.left - safe.right - startPanel - 8 * dp;
        int maxCols = Math.max(1, (int) (avail / cellW));
        int maxRows = Math.max(1, (int) ((usableBottom() - usableTop()) / cellH));
        int rows = Math.min(n, Math.min(maxRows, 5));
        int cols = (n + rows - 1) / rows;
        if (cols > maxCols) {
            cols = maxCols;
            rows = Math.min(maxRows, (n + cols - 1) / cols);
        }
        int count = Math.min(n, rows * cols);
        float h = rows * cellH;
        panelCenterY = Math.max(usableTop() + h / 2f, Math.min(usableBottom() - h / 2f, fingerY));
        float y0 = panelCenterY - h / 2f + cellH / 2f;
        for (int i = 0; i < count; i++) {
            int c = i / rows, r = i % rows;
            Cell cell = new Cell();
            float dx = startPanel + cellW / 2f + c * cellW;
            cell.x = side == LEFT ? stripX(LEFT) + dx : stripX(RIGHT) - dx;
            cell.y = y0 + r * cellH;
            cell.app = apps.get(i);
            cells.add(cell);
        }
        animStart = SystemClock.uptimeMillis();
    }

    private int cellAt(float x, float y) {
        int best = -1;
        double bestD = 42 * dp;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            double d = Math.hypot(x - c.x, y - c.y);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private boolean inStripZone(float x) {
        float band = getWidth() * store.band() / 100f;
        return (side == LEFT && x < band) || (side == RIGHT && x > getWidth() - band);
    }

    // ------------------------------------------------------------ toucher

    /** Les touchers n'arrivent que par {@link #handleTouch}, jamais par le parent. */
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return false;
    }

    /** Appelé par l'écran d'accueil pour chaque événement du geste qu'il confie à l'alphabet. */
    boolean handleTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                moved = false;
                if (phase == PHASE_STICKY) {
                    int hit = cellAt(x, y);
                    if (hit >= 0) {
                        hoverCell = hit;
                        postDelayed(longPress, LONG_PRESS_MS);
                        invalidate();
                        return true;
                    }
                    if (inStripZone(x)) {
                        startLetters(x, y);
                        return true;
                    }
                    close();
                    return true;
                }
                startLetters(x, y);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.hypot(x - downX, y - downY) > 8 * dp) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                if (phase == PHASE_LETTERS) {
                    fingerY = y;
                    int i = entryAt(y);
                    if (i != current) {
                        current = i;
                        if (!entries.get(i).apps.isEmpty()) haptic(HapticFeedbackConstants.CLOCK_TICK);
                        layoutPanel();
                    }
                    if (inward(x) > 44 * dp && !cells.isEmpty()) {
                        phase = PHASE_APPS;
                        hoverCell = cellAt(x, y);
                    }
                    invalidate();
                } else if (phase == PHASE_APPS || phase == PHASE_STICKY) {
                    if (phase == PHASE_APPS && inward(x) < 20 * dp) {
                        phase = PHASE_LETTERS;
                        hoverCell = -1;
                    } else {
                        int h = cellAt(x, y);
                        if (h != hoverCell) {
                            hoverCell = h;
                            if (h >= 0) haptic(HapticFeedbackConstants.CLOCK_TICK);
                        }
                    }
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (phase == PHASE_APPS || phase == PHASE_STICKY) {
                    int hit = cellAt(x, y);
                    if (hit >= 0) {
                        launch(cells.get(hit));
                        return true;
                    }
                    phase = cells.isEmpty() ? PHASE_IDLE : PHASE_STICKY;
                    hoverCell = -1;
                    if (phase == PHASE_IDLE) close();
                    invalidate();
                } else if (phase == PHASE_LETTERS) {
                    // Doigt levé sur l'alphabet : les applis restent pour un toucher.
                    if (cells.isEmpty()) close();
                    else phase = PHASE_STICKY;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                close();
                return true;
        }
        return true;
    }

    private void startLetters(float x, float y) {
        phase = PHASE_LETTERS;
        startX = x;
        fingerY = y;
        current = entryAt(y);
        if (current >= 0 && !entries.get(current).apps.isEmpty()) haptic(HapticFeedbackConstants.CLOCK_TICK);
        layoutPanel();
        invalidate();
    }

    private void launch(Cell c) {
        if (c.app == null || listener == null) return;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int half = (int) (24 * dp);
        int sx = (int) c.x + loc[0], sy = (int) (c.y - 8 * dp) + loc[1];
        listener.onStripLaunch(c.app, new Rect(sx - half, sy - half, sx + half, sy + half));
        close();
    }

    // ------------------------------------------------------------ dessin

    @Override
    protected void onDraw(Canvas canvas) {
        boolean active = phase != PHASE_IDLE;
        if (active) {
            canvas.drawColor(Color.argb((int) (255 * store.dimPercent() / 100f * 0.8f), 0, 0, 0));
        } else if (!store.alphaVisible() || store.edgeMode() != 0) {
            return;
        }
        int s = sides();
        if ((s & LEFT) != 0 && (!active || side == LEFT)) drawStrip(canvas, LEFT, active);
        if ((s & RIGHT) != 0 && (!active || side == RIGHT)) drawStrip(canvas, RIGHT, active);
        if (active) drawPanel(canvas);
    }

    private void drawStrip(Canvas canvas, int s, boolean active) {
        float x0 = stripX(s);
        int bgAlpha = (int) (255 * store.stripAlpha() / 100f * (active ? 1f : 0.45f));
        if (bgAlpha > 0) {
            fill.setColor(0xFF000000 | store.stripBg());
            fill.setAlpha(bgAlpha);
            rect.set(x0 - 13 * dp, top() - 12 * dp, x0 + 13 * dp, bottom() + 12 * dp);
            canvas.drawRoundRect(rect, 13 * dp, 13 * dp, fill);
        }
        float size = Math.min(12 * dp, (bottom() - top()) / Math.max(1, entries.size()) * 0.72f);
        int fg = store.stripFg();
        float inwardSign = s == LEFT ? 1f : -1f;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            float y = entryY(i);
            // Effet loupe : les lettres proches du doigt grossissent et s'écartent du bord.
            float z = active ? zoomAt(y) : 1f;
            float x = x0 + inwardSign * (z - 1f) * 16 * dp;
            boolean sel = active && i == current;
            boolean empty = e.apps.isEmpty();
            if (e.group) {
                fill.setColor(e.color);
                fill.setAlpha(active ? 255 : 150);
                canvas.drawCircle(x, y, size * 0.75f * z, fill);
                if (sel) {
                    fill.setColor(Color.WHITE);
                    fill.setAlpha(255);
                    canvas.drawCircle(x, y, size * 0.35f * z, fill);
                }
                continue;
            }
            if (sel && !empty) {
                fill.setColor(accent);
                fill.setAlpha(255);
                canvas.drawCircle(x, y, size * 0.95f * z, fill);
            }
            text.setTextSize(size * z);
            text.setShadowLayer(active ? 0 : 3 * dp, 0, dp, 0xAA000000);
            int base = e.color != 0 ? e.color : fg;
            text.setColor(sel && !empty ? 0xFF111320 : base);
            text.setAlpha(empty ? (active ? 70 : 45) : (active ? 255 : 150));
            drawCentered(canvas, e.label, x, y, text);
        }
    }

    private void drawPanel(Canvas canvas) {
        if (current < 0) return;
        Entry e = entries.get(current);
        float t = Math.min(1f, (SystemClock.uptimeMillis() - animStart) / Math.max(1f, 160f * store.speedFactor()));
        if (store.speedFactor() == 0f) t = 1f;

        // La bulle de la lettre, comme dans les Contacts.
        if (phase == PHASE_LETTERS && store.zoom() == 0) {
            float bx = side == LEFT ? stripX(LEFT) + 40 * dp : stripX(RIGHT) - 40 * dp;
            float by = entryY(current);
            fill.setColor(e.group ? e.color : accent);
            fill.setAlpha(255);
            canvas.drawCircle(bx, by, 22 * dp, fill);
            text.setShadowLayer(0, 0, 0, 0);
            text.setColor(0xFF111320);
            text.setTextSize((e.group ? 13 : 22) * dp);
            drawCentered(canvas, e.shortLabel(), bx, by, text);
        }

        float half = 23 * dp;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            float k = Math.max(0f, Math.min(1f, t * 1.5f - 0.5f * i / Math.max(1, cells.size())));
            if (k <= 0f) continue;
            int alpha = (int) (255 * k);
            float iy = c.y - 8 * dp;
            boolean h = i == hoverCell;
            float hh = half * (h ? 1.14f : 1f) * (0.7f + 0.3f * k);
            if (h) {
                fill.setColor(accent);
                fill.setAlpha(90);
                canvas.drawCircle(c.x, iy, 33 * dp, fill);
            }
            Drawable icon = c.app.icon;
            if (icon != null) {
                icon.setBounds((int) (c.x - hh), (int) (iy - hh), (int) (c.x + hh), (int) (iy + hh));
                icon.setAlpha(alpha);
                icon.draw(canvas);
                icon.setAlpha(255);
            } else {
                fill.setColor(0x33FFFFFF);
                canvas.drawCircle(c.x, iy, hh, fill);
            }
            label.setAlpha(alpha);
            CharSequence name = TextUtils.ellipsize(c.app.label, label, 70 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(name, 0, name.length(), c.x, c.y + 32 * dp, label);
        }
        if (t < 1f) postInvalidateOnAnimation();
    }

    private void drawCentered(Canvas canvas, String s, float x, float y, Paint paint) {
        paint.getTextBounds(s, 0, s.length(), tmp);
        canvas.drawText(s, x, y + tmp.height() / 2f - tmp.bottom, paint);
    }
}
