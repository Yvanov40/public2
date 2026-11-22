package fr.minilauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La roue.
 *
 * Mode lettres : au centre la dernière appli utilisée ; autour, l'anneau des favoris ;
 * plus loin, les lettres A–Z, # et les groupes. Près d'un bord, les anneaux deviennent des
 * éventails. On touche, ou on glisse et on s'arrête un instant sur une lettre ou un groupe
 * pour l'ouvrir sans lever le doigt. Un petit coup de doigt rapide (flick) vers une lettre
 * l'ouvre aussi.
 *
 * Mode applis : les applis de la lettre ou du groupe en nid d'abeille autour du centre.
 */
final class RadialView extends View {

    interface Listener {
        void onLaunch(AppRepository.AppEntry app, Rect screenBounds);

        void onAppMenu(AppRepository.AppEntry app, String group);

        void onGroupMenu(String group);

        /** La roue a été déplacée par l'utilisateur (coordonnées de la vue). */
        void onMoved(float cx, float cy);

        void onClosed();
    }

    static final String[] LETTERS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"};
    static final int MAX_FAVS = 8;

    private static final int MODE_LETTERS = 0;
    private static final int MODE_APPS = 1;
    private static final int HIT_NONE = -1;
    private static final int HIT_CENTER = -2;
    private static final int FAV = 1000; // cibles FAV + i = i-ème favori

    private static final int COLOR_PANEL = 0xF01B1D26;
    private int COLOR_ACCENT = 0xFF8FA6FF; // couleur choisie dans les réglages
    private float scale = 1f;               // taille de la roue
    private int dimAlpha = 165;
    private long dwellMs = 380;
    private boolean haptics = true;
    private static final int COLOR_DARK = 0xFF111320;
    private static final long LONG_PRESS_MS = 550;
    private static final long FLICK_MS = 260;

    /** Une entrée de l'anneau extérieur : une lettre ou un groupe. */
    static final class Item {
        final String label;
        final boolean group;
        final int color;
        final List<AppRepository.AppEntry> apps = new ArrayList<>();
        float rad, ang;

        Item(String label, boolean group, int color) {
            this.label = label;
            this.group = group;
            this.color = color;
        }

        String shortLabel() {
            return group && label.length() > 3 ? label.substring(0, 3) : label;
        }
    }

    private static final class Cell {
        final float x, y;
        AppRepository.AppEntry app;
        int more; // > 0 : case « +n » ; < 0 : retour à la première page

        Cell(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private final float dp;
    private final float touchSlop;
    private final Store store;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect safe = new Rect();
    private final RectF area = new RectF();
    private final Rect tmp = new Rect();
    private final RectF oval = new RectF();

    private Listener listener;
    private List<AppRepository.AppEntry> allApps = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final List<AppRepository.AppEntry> favs = new ArrayList<>();
    private final float[] favRad = new float[MAX_FAVS];
    private final float[] favAng = new float[MAX_FAVS];
    private int favCount;
    private AppRepository.AppEntry lastApp;
    private final List<float[]> arcs = new ArrayList<>(); // {rayon, début, balayage, épaisseur}

    private Rect anchor;
    private boolean anchorUsable;
    private boolean anchorForced; // ouverte sous le doigt (écran d'accueil) : toujours là
    private boolean pressOpened;  // le doigt qui a ouvert la roue est encore posé
    private boolean movable;      // appui long au centre = déplacer (bulle)
    private int mode = MODE_LETTERS;
    private float cx, cy;
    private int hover = HIT_NONE;
    private Item current;
    private int page;
    private final List<Cell> cells = new ArrayList<>();
    private int hoverCell = HIT_NONE;

    private float progress;
    private float dim;
    private ValueAnimator animator;
    private boolean closing;

    // Suivi du doigt
    private float downX, downY, lastX, lastY, switchX, switchY;
    private int pressTarget = HIT_NONE;
    private boolean moved, waitMoveAfterSwitch, longPressFired, tracking, dragging;

    private final Runnable dwell = () -> {
        if (mode != MODE_LETTERS || hover < 0 || hover >= FAV || !tracking) return;
        haptic(HapticFeedbackConstants.VIRTUAL_KEY);
        openItem(items.get(hover), true);
    };

    private final Runnable startDrag = () -> {
        if (mode != MODE_LETTERS || moved || !tracking) return;
        dragging = true;
        hover = HIT_NONE;
        removeCallbacks(dwell);
        haptic(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    };

    private final Runnable longPress = () -> {
        if (moved || !tracking || listener == null) return;
        if (mode == MODE_LETTERS) {
            if (pressTarget >= FAV && pressTarget - FAV < favCount) {
                longPressFired = true;
                haptic(HapticFeedbackConstants.LONG_PRESS);
                listener.onAppMenu(favs.get(pressTarget - FAV), null);
            }
        } else if (pressTarget >= 0 && pressTarget < cells.size()) {
            Cell c = cells.get(pressTarget);
            if (c.app == null) return;
            longPressFired = true;
            haptic(HapticFeedbackConstants.LONG_PRESS);
            listener.onAppMenu(c.app, current != null && current.group ? current.label : null);
        } else if (pressTarget == HIT_CENTER && current != null && current.group) {
            longPressFired = true;
            haptic(HapticFeedbackConstants.LONG_PRESS);
            listener.onGroupMenu(current.label);
        }
    };

    RadialView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        store = Store.get(context);
        float sp = getResources().getDisplayMetrics().scaledDensity;
        band.setStyle(Paint.Style.STROKE);
        band.setStrokeCap(Paint.Cap.ROUND);
        band.setColor(COLOR_PANEL);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        label.setColor(Color.WHITE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(11 * sp);
        label.setShadowLayer(3 * dp, 0, dp, 0xCC000000);
        setHapticFeedbackEnabled(true);
        setFocusable(true);
    }

    private void haptic(int type) {
        if (haptics) performHapticFeedback(type);
    }

    void setMovable(boolean m) {
        movable = m;
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setApps(List<AppRepository.AppEntry> apps) {
        allApps = new ArrayList<>(apps);
        refresh();
    }

    /** Relit favoris, groupes et réglages (après un menu, par exemple). */
    void refresh() {
        reload();
        layoutCenter();
    }

    /** Ouvre la roue autour de {@code sourceBounds} (coordonnées écran de l'icône touchée). */
    void open(Rect sourceBounds) {
        anchor = sourceBounds == null ? null : new Rect(sourceBounds);
        anchorForced = false;
        pressOpened = false;
        closing = false;
        dragging = false;
        tracking = false;
        mode = MODE_LETTERS;
        hover = HIT_NONE;
        current = null;
        dim = 0;
        refresh();
        animate(0f, 1f, 260);
    }

    /**
     * Ouvre la roue sous le doigt (coordonnées de la vue), pour l'écran d'accueil.
     * Si {@code fingerDown}, le geste en cours continue dans la roue : lever le doigt sans
     * bouger laisse la roue ouverte au lieu de la fermer.
     */
    void openAt(float x, float y, boolean fingerDown) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int sx = (int) x + loc[0], sy = (int) y + loc[1];
        open(new Rect(sx - 1, sy - 1, sx + 1, sy + 1));
        anchorForced = true;
        pressOpened = fingerDown;
        refresh();
    }

    /** Fait disparaître la roue sans animation. */
    void dismissNow() {
        if (animator != null) animator.cancel();
        closing = true;
        tracking = false;
        dragging = false;
        pressOpened = false;
        removeCallbacks(dwell);
        removeCallbacks(longPress);
        removeCallbacks(startDrag);
        progress = 0;
        dim = 0;
        mode = MODE_LETTERS;
        current = null;
        invalidate();
    }

    /** Retour arrière : applis → lettres. Renvoie false s'il n'y a plus rien à refermer. */
    boolean goBack() {
        if (closing) return true;
        if (mode == MODE_APPS) {
            showLetters();
            return true;
        }
        return false;
    }

    void close() {
        if (closing) return;
        closing = true;
        animate(progress, 0f, 140);
    }

    // ---------------------------------------------------------------- données

    private void reload() {
        COLOR_ACCENT = store.accent();
        scale = store.wheelScale() / 100f;
        dimAlpha = (int) (255 * store.dimPercent() / 100f);
        dwellMs = store.dwell();
        haptics = store.haptic();
        int maxFav = Math.max(0, Math.min(MAX_FAVS, store.maxFav()));
        Map<String, AppRepository.AppEntry> byKey = new HashMap<>();
        Map<String, Item> letters = new HashMap<>();
        String currentLabel = current == null ? null : current.label;
        boolean currentGroup = current != null && current.group;
        items.clear();
        for (String l : LETTERS) {
            Item it = new Item(l, false, COLOR_ACCENT);
            items.add(it);
            letters.put(l, it);
        }
        for (AppRepository.AppEntry e : allApps) {
            byKey.put(Store.key(e.component), e);
            Item it = letters.get(e.letter);
            if (it != null) it.apps.add(e);
        }
        for (Store.Group g : store.groups()) {
            Item it = new Item(g.name, true, g.color);
            Set<String> members = new HashSet<>(g.apps);
            for (AppRepository.AppEntry e : allApps) {
                if (members.contains(Store.key(e.component))) it.apps.add(e);
            }
            items.add(it);
        }

        favs.clear();
        for (String k : store.pinned()) {
            AppRepository.AppEntry e = byKey.get(k);
            if (e != null && favs.size() < maxFav) favs.add(e);
        }
        if (store.autoFavorites()) {
            for (Map.Entry<String, Integer> u : store.usage()) {
                if (favs.size() >= maxFav || u.getValue() < 2) break;
                AppRepository.AppEntry e = byKey.get(u.getKey());
                if (e != null && !favs.contains(e)) favs.add(e);
            }
        }
        String last = store.last();
        lastApp = store.centerLast() && last != null ? byKey.get(last) : null;

        current = null;
        if (currentLabel != null) {
            for (Item it : items) {
                if (it.group == currentGroup && it.label.equals(currentLabel)) current = it;
            }
        }
        if (mode == MODE_APPS && (current == null || current.apps.isEmpty())) {
            mode = MODE_LETTERS;
            current = null;
        }
    }

    // ---------------------------------------------------------------- mise en page

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        safe.set(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        layoutCenter();
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutCenter();
    }

    private void layoutCenter() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        area.set(safe.left, Math.max(safe.top, 24 * dp), w - safe.right, h - Math.max(safe.bottom, 16 * dp));
        float[] saved = store.position();
        float ax = saved != null ? saved[0] * w : w / 2f;
        float ay = saved != null ? saved[1] * h : h * 0.62f;
        anchorUsable = false;
        if (anchor != null) {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            // Certains launchers donnent une position fausse, collée en haut à gauche
            // (sous la barre d'état) : on l'ignore et on garde la position mémorisée.
            if (anchorForced || (anchor.top >= area.top && anchor.left >= 0 && anchor.width() > 0)) {
                anchorUsable = true;
                ax = anchor.exactCenterX() - loc[0];
                ay = anchor.exactCenterY() - loc[1];
            }
        }
        float in = 30 * dp;
        cx = clamp(ax, area.left + in, area.right - in);
        cy = clamp(ay, area.top + in, area.bottom - in);
        layoutRings();
        if (mode == MODE_APPS) buildCells();
        invalidate();
    }

    /**
     * Anneau des favoris, puis anneau des lettres et groupes. Au milieu de l'écran : des
     * cercles complets. Près d'un bord : des éventails sur la partie visible. Dans un coin,
     * si un arc ne suffit pas : plusieurs arcs concentriques.
     */
    private void layoutRings() {
        arcs.clear();
        favCount = 0;
        if (!favs.isEmpty()) {
            float r = 70 * dp * scale;
            float[] arc = visibleArc(r, 24 * dp);
            if (arc != null) {
                favCount = Math.min(favs.size(), capacity(r, arc[1], 50 * dp * Math.min(1f, scale)));
                if (favCount > 0) {
                    float[] a = spread(favCount, arc, r, 50 * dp);
                    for (int i = 0; i < favCount; i++) {
                        favRad[i] = r;
                        favAng[i] = a[i];
                    }
                }
            }
        }

        int n = items.size();
        float spacing = 31 * dp * Math.min(1f, scale);
        float base = (favCount > 0 ? 122 * dp : 105 * dp) * scale;
        for (float r = base; r <= base + 45 * dp * scale; r += 3 * dp) {
            float[] arc = visibleArc(r, 20 * dp);
            if (arc != null && capacity(r, arc[1], spacing) >= n) {
                placeItems(0, n, r, arc);
                return;
            }
        }
        int placed = 0;
        float r = base;
        for (int guard = 0; placed < n && guard < 14; guard++, r += 42 * dp * Math.min(1f, scale)) {
            float[] arc = visibleArc(r, 20 * dp);
            if (arc == null) continue;
            int take = Math.min(n - placed, capacity(r, arc[1], spacing));
            if (take <= 0) continue;
            placeItems(placed, take, r, arc);
            placed += take;
        }
        for (int i = placed; i < n; i++) {
            items.get(i).rad = r;
            items.get(i).ang = -90 + 360f * i / n;
        }
    }

    private void placeItems(int from, int count, float r, float[] arc) {
        float[] a = spread(count, arc, r, 40 * dp);
        for (int i = 0; i < count; i++) {
            items.get(from + i).rad = r;
            items.get(from + i).ang = a[i];
        }
    }

    /** Angles de {@code count} éléments répartis sur l'arc, et ajoute l'arc de fond. */
    private float[] spread(int count, float[] arc, float r, float width) {
        float start = arc[0], sweep = arc[1];
        boolean full = sweep >= 360f;
        float step = full ? 360f / count : (count > 1 ? sweep / (count - 1) : 0f);
        float first = full ? -90f : (count > 1 ? start : start + sweep / 2f);
        float[] a = new float[count];
        for (int i = 0; i < count; i++) a[i] = first + i * step;
        arcs.add(full ? new float[]{r, 0f, 360f, width} : new float[]{r, first, step * (count - 1), width});
        return a;
    }

    private int capacity(float r, float sweep, float spacing) {
        double len = Math.toRadians(sweep) * r;
        return sweep >= 360f ? (int) (len / spacing) : (int) (len / spacing) + 1;
    }

    /** Plus grande portion du cercle de rayon r qui tient dans l'écran : {début, balayage} en degrés. */
    private float[] visibleArc(float r, float m) {
        float left = area.left + m, top = area.top + m, right = area.right - m, bottom = area.bottom - m;
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

    /** Place les applis en nid d'abeille autour du centre, en restant dans l'écran. */
    private void buildCells() {
        cells.clear();
        hoverCell = HIT_NONE;
        List<AppRepository.AppEntry> list = current == null ? new ArrayList<>() : current.apps;
        float s = 78 * dp, rowH = s * 0.866f;
        float left = area.left + 38 * dp, right = area.right - 38 * dp;
        float top = area.top + 36 * dp, bottom = area.bottom - 34 * dp;
        int rows = (int) (getHeight() / rowH) + 2, cols = (int) (getWidth() / s) + 2;
        List<float[]> spots = new ArrayList<>();
        for (int r = -rows; r <= rows; r++) {
            for (int c = -cols; c <= cols; c++) {
                if (r == 0 && c == 0) continue;
                float x = cx + (c + ((r & 1) != 0 ? 0.5f : 0f)) * s;
                float y = cy + r * rowH;
                if (x < left || x > right || y < top || y > bottom) continue;
                float dx = x - cx, dy = y - cy;
                double ang = Math.atan2(dx, -dy);
                if (ang < 0) ang += 2 * Math.PI;
                spots.add(new float[]{x, y, Math.round(Math.hypot(dx, dy) / (s * 0.25f)), (float) ang});
            }
        }
        spots.sort((a, b) -> a[2] != b[2] ? Float.compare(a[2], b[2]) : Float.compare(a[3], b[3]));

        int n = list.size(), k = spots.size();
        if (k == 0 || n == 0) return;
        int perPage = n <= k ? n : Math.max(1, k - 1);
        int pages = (n + perPage - 1) / perPage;
        if (page >= pages) page = 0;
        int start = page * perPage, end = Math.min(n, start + perPage);
        for (int i = start; i < end; i++) {
            float[] p = spots.get(i - start);
            Cell cell = new Cell(p[0], p[1]);
            cell.app = list.get(i);
            cells.add(cell);
        }
        if (n > k) {
            float[] p = spots.get(k - 1);
            Cell more = new Cell(p[0], p[1]);
            more.more = end < n ? n - end : -1;
            cells.add(more);
        }
    }

    private void openItem(Item it, boolean whileDown) {
        if (it.apps.isEmpty()) return;
        if (it.apps.size() == 1 && store.directSingle()) {
            launch(it.apps.get(0), itemX(it, 1f), itemY(it, 1f));
            return;
        }
        current = it;
        page = 0;
        mode = MODE_APPS;
        hover = HIT_NONE;
        buildCells();
        animate(0f, 1f, 240);
        if (whileDown) {
            waitMoveAfterSwitch = true;
            switchX = lastX;
            switchY = lastY;
        }
    }

    private void showLetters() {
        mode = MODE_LETTERS;
        current = null;
        hoverCell = HIT_NONE;
        animate(0f, 1f, 200);
    }

    private void launch(AppRepository.AppEntry app, float x, float y) {
        if (listener == null) return;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int half = (int) (23 * dp);
        int sx = (int) x + loc[0], sy = (int) y + loc[1];
        closing = true; // plus aucun toucher jusqu'à la prochaine ouverture
        tracking = false;
        removeCallbacks(dwell);
        removeCallbacks(longPress);
        listener.onLaunch(app, new Rect(sx - half, sy - half, sx + half, sy + half));
    }

    private void animate(float from, float to, long ms) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration((long) (ms * store.speedFactor()));
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        final boolean closingAnim = closing;
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            dim = closingAnim ? progress : Math.max(dim, progress);
            invalidate();
            if (closingAnim && a.getAnimatedFraction() >= 1f && listener != null) listener.onClosed();
        });
        animator.start();
    }

    // ---------------------------------------------------------------- positions

    private float itemX(Item it, float grow) {
        return cx + (float) (it.rad * grow * Math.cos(Math.toRadians(it.ang)));
    }

    private float itemY(Item it, float grow) {
        return cy + (float) (it.rad * grow * Math.sin(Math.toRadians(it.ang)));
    }

    private float favX(int i, float grow) {
        return cx + (float) (favRad[i] * grow * Math.cos(Math.toRadians(favAng[i])));
    }

    private float favY(int i, float grow) {
        return cy + (float) (favRad[i] * grow * Math.sin(Math.toRadians(favAng[i])));
    }

    // ---------------------------------------------------------------- dessin

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.argb((int) (dimAlpha * dim), 0, 0, 0));
        if (mode == MODE_LETTERS) {
            drawLetters(canvas);
            if (dragging || (!anchorUsable && !anchorForced && store.position() == null)) drawHint(canvas);
        } else {
            drawApps(canvas);
        }
    }

    private void drawLetters(Canvas canvas) {
        float p = progress;
        float grow = 0.55f + 0.45f * p;
        float k = clamp(p, 0f, 1f);
        int alpha = (int) (255 * k);

        band.setAlpha((int) (Color.alpha(COLOR_PANEL) * k));
        for (float[] arc : arcs) {
            float r = arc[0] * grow;
            band.setStrokeWidth(arc[3]);
            if (arc[2] >= 360f) {
                canvas.drawCircle(cx, cy, r, band);
            } else {
                oval.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(oval, arc[1], arc[2], false, band);
            }
        }

        // Favoris
        float favHalf = 19 * dp;
        for (int i = 0; i < favCount; i++) {
            float x = favX(i, grow), y = favY(i, grow);
            boolean h = hover == FAV + i;
            float half = h ? favHalf * 1.18f : favHalf;
            if (h) {
                fill.setColor(COLOR_ACCENT);
                fill.setAlpha(alpha / 2);
                canvas.drawCircle(x, y, half + 4 * dp, fill);
            }
            drawIcon(canvas, favs.get(i), x, y, half, alpha);
        }

        // Lettres et groupes
        float letterSize = 15 * dp;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            float x = itemX(it, grow), y = itemY(it, grow);
            boolean available = !it.apps.isEmpty();
            boolean h = i == hover;
            if (it.group) {
                fill.setColor(it.color);
                fill.setAlpha(alpha);
                canvas.drawCircle(x, y, (h ? 19 : 15) * dp, fill);
                text.setColor(COLOR_DARK);
                text.setAlpha(alpha);
                text.setTextSize((h ? 12 : 10) * dp);
                drawCentered(canvas, it.shortLabel(), x, y, text);
                continue;
            }
            if (h) {
                fill.setColor(available ? COLOR_ACCENT : 0x66FFFFFF);
                fill.setAlpha(alpha);
                canvas.drawCircle(x, y, 19 * dp, fill);
            }
            text.setTextSize(h ? letterSize * 1.25f : letterSize);
            text.setColor(h && available ? COLOR_DARK : Color.WHITE);
            text.setAlpha(available ? alpha : alpha / 4);
            drawCentered(canvas, it.label, x, y, text);
        }

        // Centre
        fill.setColor(COLOR_PANEL);
        fill.setAlpha(alpha);
        canvas.drawCircle(cx, cy, 30 * dp * (0.6f + 0.4f * p), fill);
        if (hover >= 0 && hover < FAV) {
            Item it = items.get(hover);
            text.setColor(it.group ? it.color : COLOR_ACCENT);
            text.setAlpha(alpha);
            text.setTextSize((it.group ? 13 : 24) * dp);
            drawCentered(canvas, it.shortLabel(), cx, cy - 4 * dp, text);
            label.setAlpha(alpha);
            canvas.drawText(String.valueOf(it.apps.size()), cx, cy + 20 * dp, label);
        } else if (hover >= FAV) {
            label.setAlpha(alpha);
            String name = favs.get(hover - FAV).label;
            CharSequence s = TextUtils.ellipsize(name, label, 56 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(s, 0, s.length(), cx, cy + 4 * dp, label);
        } else if (lastApp != null && !dragging) {
            drawIcon(canvas, lastApp, cx, cy, 17 * dp, alpha);
        } else {
            text.setColor(0xAAFFFFFF);
            text.setAlpha(alpha * 2 / 3);
            text.setTextSize(22 * dp);
            drawCentered(canvas, dragging ? "✥" : "×", cx, cy, text);
        }
    }

    private void drawApps(Canvas canvas) {
        float p = progress;
        int n = cells.size();
        float iconHalf = 23 * dp;
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            float t = clamp((p - 0.35f * i / Math.max(1, n)) / 0.65f, 0f, 1f);
            if (t <= 0f) continue;
            float x = cx + (c.x - cx) * (0.35f + 0.65f * t);
            float y = cy + (c.y - cy) * (0.35f + 0.65f * t);
            int alpha = (int) (255 * t);
            boolean h = i == hoverCell;
            float half = iconHalf * (h ? 1.12f : 1f);
            float iy = y - 8 * dp;
            if (h) {
                fill.setColor(0x40FFFFFF);
                fill.setAlpha(alpha / 4);
                canvas.drawCircle(x, iy, 33 * dp, fill);
            }
            if (c.app == null) {
                fill.setColor(0x55FFFFFF);
                fill.setAlpha(alpha / 3);
                canvas.drawCircle(x, iy, half, fill);
                text.setColor(Color.WHITE);
                text.setAlpha(alpha);
                text.setTextSize(15 * dp);
                drawCentered(canvas, c.more > 0 ? "+" + c.more : "↺", x, iy, text);
                continue;
            }
            drawIcon(canvas, c.app, x, iy, half, alpha);
            label.setAlpha(alpha);
            CharSequence name = TextUtils.ellipsize(c.app.label, label, 74 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(name, 0, name.length(), x, y + 30 * dp, label);
        }

        int alpha = (int) (255 * clamp(p, 0f, 1f));
        int color = current != null && current.group ? current.color : COLOR_ACCENT;
        fill.setColor(color);
        fill.setAlpha(Math.max(alpha, 140));
        canvas.drawCircle(cx, cy, 28 * dp, fill);
        text.setColor(COLOR_DARK);
        text.setAlpha(255);
        text.setTextSize((current != null && current.group ? 13 : 24) * dp);
        drawCentered(canvas, current == null ? "" : current.shortLabel(), cx, cy, text);
    }

    private void drawIcon(Canvas canvas, AppRepository.AppEntry app, float x, float y, float half, int alpha) {
        Drawable icon = app.icon;
        if (icon != null) {
            icon.setBounds((int) (x - half), (int) (y - half), (int) (x + half), (int) (y + half));
            icon.setAlpha(alpha);
            icon.draw(canvas);
            icon.setAlpha(255);
        } else {
            fill.setColor(0x33FFFFFF);
            fill.setAlpha(alpha / 5);
            canvas.drawCircle(x, y, half, fill);
        }
    }

    private void drawHint(Canvas canvas) {
        String s = dragging ? (movable ? "Lâche la bulle où tu veux" : "Lâche le doigt sur ton icône") : "Maintiens le centre puis glisse pour placer la roue";
        float y = cy > getHeight() / 2f ? area.top + 40 * dp : area.bottom - 40 * dp;
        label.setAlpha(255);
        float tw = label.measureText(s);
        fill.setColor(COLOR_PANEL);
        oval.set(getWidth() / 2f - tw / 2 - 14 * dp, y - 22 * dp, getWidth() / 2f + tw / 2 + 14 * dp, y + 12 * dp);
        canvas.drawRoundRect(oval, 17 * dp, 17 * dp, fill);
        canvas.drawText(s, getWidth() / 2f, y, label);
    }

    private void drawCentered(Canvas canvas, String s, float x, float y, Paint paint) {
        paint.getTextBounds(s, 0, s.length(), tmp);
        canvas.drawText(s, x, y + tmp.height() / 2f - tmp.bottom, paint);
    }

    // ---------------------------------------------------------------- toucher

    private int targetAt(float x, float y) {
        if (Math.hypot(x - cx, y - cy) < 32 * dp) return HIT_CENTER;
        int best = HIT_NONE;
        double bestD = 34 * dp;
        for (int i = 0; i < favCount; i++) {
            double d = Math.hypot(x - favX(i, 1f), y - favY(i, 1f));
            if (d < 28 * dp && d < bestD) {
                bestD = d;
                best = FAV + i;
            }
        }
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            double d = Math.hypot(x - itemX(it, 1f), y - itemY(it, 1f));
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Flick : prolonge le geste et renvoie la première lettre ou le premier groupe rencontré. */
    private int flickTarget(float x, float y) {
        float dx = x - downX, dy = y - downY;
        float len = (float) Math.hypot(dx, dy);
        if (len < 30 * dp) return HIT_NONE;
        float ux = dx / len, uy = dy / len;
        for (float t = 0; t < 240 * dp; t += 6 * dp) {
            int h = targetAt(x + ux * t, y + uy * t);
            if (h >= 0 && h < FAV && !items.get(h).apps.isEmpty()) return h;
        }
        return HIT_NONE;
    }

    private int cellAt(float x, float y) {
        if (Math.hypot(x - cx, y - cy) < 30 * dp) return HIT_CENTER;
        int best = HIT_NONE;
        double bestD = 40 * dp;
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            double d = Math.hypot(x - c.x, y - c.y + 4 * dp);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (closing) return true;
        float x = e.getX(), y = e.getY();
        lastX = x;
        lastY = y;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                downX = x;
                downY = y;
                moved = false;
                waitMoveAfterSwitch = false;
                longPressFired = false;
                dragging = false;
                pressTarget = mode == MODE_LETTERS ? targetAt(x, y) : cellAt(x, y);
                updateHover(x, y);
                if (mode == MODE_LETTERS) {
                    if (pressTarget == HIT_CENTER && (!anchorForced || movable)) postDelayed(startDrag, LONG_PRESS_MS);
                    else if (pressTarget >= FAV) postDelayed(longPress, LONG_PRESS_MS);
                } else if (pressTarget >= 0 || pressTarget == HIT_CENTER) {
                    postDelayed(longPress, LONG_PRESS_MS);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                if (dragging) {
                    cx = clamp(x, area.left + 30 * dp, area.right - 30 * dp);
                    cy = clamp(y, area.top + 30 * dp, area.bottom - 30 * dp);
                    layoutRings();
                    invalidate();
                    return true;
                }
                if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
                    moved = true;
                    removeCallbacks(longPress);
                    removeCallbacks(startDrag);
                }
                if (waitMoveAfterSwitch) {
                    if (Math.hypot(x - switchX, y - switchY) < 24 * dp) return true;
                    waitMoveAfterSwitch = false;
                }
                updateHover(x, y);
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                removeCallbacks(dwell);
                removeCallbacks(startDrag);
                if (!tracking) return true;
                tracking = false;
                if (dragging) {
                    dragging = false;
                    store.setPosition(cx / getWidth(), cy / getHeight());
                    if (listener != null) listener.onMoved(cx, cy);
                    invalidate();
                    return true;
                }
                if (longPressFired) return true;
                if (waitMoveAfterSwitch) {
                    // Les applis viennent de s'ouvrir sous le doigt : on les laisse affichées.
                    waitMoveAfterSwitch = false;
                    return true;
                }
                boolean firstPress = pressOpened;
                pressOpened = false;
                if (firstPress && !moved) {
                    invalidate(); // simple appui sur le bord : la roue reste ouverte
                    return true;
                }
                release(x, y, e.getEventTime() - e.getDownTime());
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(startDrag);
                removeCallbacks(longPress);
                removeCallbacks(dwell);
                dragging = false;
                tracking = false;
                hover = HIT_NONE;
                hoverCell = HIT_NONE;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void updateHover(float x, float y) {
        if (mode == MODE_LETTERS) {
            int hit = targetAt(x, y);
            int h = hit >= 0 ? hit : HIT_NONE;
            if (h != hover) {
                hover = h;
                removeCallbacks(dwell);
                if (h >= FAV) {
                    haptic(HapticFeedbackConstants.CLOCK_TICK);
                } else if (h >= 0 && !items.get(h).apps.isEmpty()) {
                    haptic(HapticFeedbackConstants.CLOCK_TICK);
                    postDelayed(dwell, dwellMs);
                }
                invalidate();
            }
        } else {
            int hit = cellAt(x, y);
            int h = hit >= 0 ? hit : HIT_NONE;
            if (h != hoverCell) {
                hoverCell = h;
                if (h >= 0) haptic(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
            }
        }
    }

    private void release(float x, float y, long duration) {
        if (mode == MODE_LETTERS) {
            int hit = targetAt(x, y);
            hover = HIT_NONE;
            boolean quick = moved && duration < FLICK_MS && Math.hypot(x - downX, y - downY) > 40 * dp;
            if (quick && (hit < 0 || hit >= FAV)) {
                int f = flickTarget(x, y);
                if (f >= 0) {
                    openItem(items.get(f), false);
                    return;
                }
            }
            if (hit >= FAV) {
                int i = hit - FAV;
                launch(favs.get(i), favX(i, 1f), favY(i, 1f));
            } else if (hit >= 0) {
                openItem(items.get(hit), false);
                invalidate();
            } else if (hit == HIT_CENTER && !moved) {
                if (lastApp != null) launch(lastApp, cx, cy);
                else close();
            } else if (!moved) {
                close();
            } else {
                invalidate();
            }
            return;
        }

        int hit = cellAt(x, y);
        hoverCell = HIT_NONE;
        if (hit >= 0) {
            Cell c = cells.get(hit);
            if (c.app != null) {
                launch(c.app, c.x, c.y - 8 * dp);
            } else {
                page = c.more > 0 ? page + 1 : 0;
                buildCells();
                animate(0f, 1f, 220);
            }
        } else if (hit == HIT_CENTER) {
            showLetters();
        } else if (!moved) {
            close();
        } else {
            invalidate();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (lo > hi) return (lo + hi) / 2f;
        return Math.max(lo, Math.min(hi, v));
    }
}
