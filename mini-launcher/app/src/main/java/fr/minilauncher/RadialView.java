package fr.minilauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dessine et gère la roue.
 *
 * Mode lettres : 27 lettres (A–Z et #) en cercle autour de l'icône. On touche une lettre,
 * ou on glisse le doigt dessus ; si on s'y arrête un instant, ses applis s'ouvrent sans
 * lever le doigt, et on glisse jusqu'à l'appli voulue.
 *
 * Mode applis : les applis de la lettre se déploient en nid d'abeille autour du centre,
 * de la plus proche à la plus éloignée, dans l'ordre alphabétique.
 */
final class RadialView extends View {

    interface Listener {
        void onLaunch(AppRepository.AppEntry app, Rect bounds);

        void onAppInfo(AppRepository.AppEntry app);

        void onClosed();
    }

    private static final String[] LETTERS = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "#"};

    private static final int MODE_LETTERS = 0;
    private static final int MODE_APPS = 1;
    private static final int HIT_NONE = -1;
    private static final int HIT_CENTER = -2;

    private static final int COLOR_PANEL = 0xF01B1D26;
    private static final int COLOR_ACCENT = 0xFF8FA6FF;
    private static final long DWELL_MS = 380;
    private static final long LONG_PRESS_MS = 550;

    private final float dp;
    private final float touchSlop;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect safe = new Rect();
    private final Rect tmp = new Rect();
    private final RectF oval = new RectF();

    private Listener listener;
    private final Map<String, List<AppRepository.AppEntry>> byLetter = new HashMap<>();

    private Rect anchor;
    private boolean anchorUsable;
    private final SharedPreferences prefs;
    private boolean dragging; // déplacement de la roue (appui long sur le centre)
    private int mode = MODE_LETTERS;
    private float cx, cy;
    // Position de chaque lettre (rayon et angle en degrés), et arcs de fond à dessiner.
    private final float[] letterRad = new float[LETTERS.length];
    private final float[] letterAng = new float[LETTERS.length];
    private final List<float[]> arcs = new ArrayList<>(); // {rayon, début, balayage}
    private int hoverLetter = HIT_NONE;
    private String letter;
    private int page;

    private static final class Cell {
        final float x, y;
        AppRepository.AppEntry app;
        int more; // > 0 : case « +n » ; < 0 : retour à la première page

        Cell(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private final List<Cell> cells = new ArrayList<>();
    private int hoverCell = HIT_NONE;

    private float progress;
    private float dim;
    private ValueAnimator animator;
    private boolean closing;

    // Suivi du doigt
    private float downX, downY, lastX, lastY, switchX, switchY;
    private boolean moved, waitMoveAfterSwitch, longPressFired, tracking;

    private final Runnable dwell = new Runnable() {
        @Override
        public void run() {
            if (mode != MODE_LETTERS || hoverLetter < 0) return;
            String l = LETTERS[hoverLetter];
            if (!byLetter.containsKey(l)) return;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            showLetter(l);
            waitMoveAfterSwitch = true;
            switchX = lastX;
            switchY = lastY;
        }
    };

    private final Runnable startDrag = new Runnable() {
        @Override
        public void run() {
            if (mode != MODE_LETTERS || moved) return;
            dragging = true;
            hoverLetter = HIT_NONE;
            removeCallbacks(dwell);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    };

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (mode != MODE_APPS || hoverCell < 0) return;
            Cell c = cells.get(hoverCell);
            if (c.app == null) return;
            longPressFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (listener != null) listener.onAppInfo(c.app);
        }
    };

    RadialView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        float sp = getResources().getDisplayMetrics().scaledDensity;
        band.setStyle(Paint.Style.STROKE);
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
        prefs = context.getSharedPreferences("roue", Context.MODE_PRIVATE);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setApps(List<AppRepository.AppEntry> apps) {
        byLetter.clear();
        for (AppRepository.AppEntry e : apps) {
            List<AppRepository.AppEntry> list = byLetter.get(e.letter);
            if (list == null) byLetter.put(e.letter, list = new ArrayList<>());
            list.add(e);
        }
        if (mode == MODE_APPS) {
            if (!byLetter.containsKey(letter)) {
                showLetters();
                return;
            }
            int keepHover = hoverCell;
            buildCells();
            if (keepHover < cells.size()) hoverCell = keepHover;
        }
        invalidate();
    }

    /** Ouvre la roue autour de {@code sourceBounds} (coordonnées écran de l'icône touchée). */
    void open(Rect sourceBounds) {
        anchor = sourceBounds == null ? null : new Rect(sourceBounds);
        closing = false;
        mode = MODE_LETTERS;
        hoverLetter = HIT_NONE;
        letter = null;
        dim = 0;
        layoutCenter();
        animate(0f, 1f, 260);
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
        float ax = prefs.getFloat("x", 0.5f) * w, ay = prefs.getFloat("y", 0.62f) * h;
        anchorUsable = false;
        if (anchor != null) {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            // Certains launchers donnent une position fausse, collée en haut à gauche
            // (sous la barre d'état) : on l'ignore et on garde la position mémorisée.
            float minTop = Math.max(safe.top, 24 * dp);
            if (anchor.top >= minTop && anchor.left >= 0 && anchor.width() > 0) {
                anchorUsable = true;
                ax = anchor.exactCenterX() - loc[0];
                ay = anchor.exactCenterY() - loc[1];
            }
        }
        // Le centre reste sur l'icône (juste assez à l'intérieur pour que le rond central se voie).
        float in = 30 * dp;
        cx = clamp(ax, safe.left + in, w - safe.right - in);
        cy = clamp(ay, safe.top + in, h - safe.bottom - in);
        layoutLetters();
        band.setStrokeWidth(40 * dp);
        if (mode == MODE_APPS) buildCells();
        invalidate();
    }

    /**
     * Répartit les lettres autour de l'icône. Au milieu de l'écran : un cercle complet.
     * Près d'un bord : un éventail sur la partie visible du cercle. Dans un coin, si un arc
     * ne suffit pas : plusieurs arcs concentriques.
     */
    private void layoutLetters() {
        arcs.clear();
        float spacing = 31 * dp;
        int n = LETTERS.length;

        // D'abord : un seul anneau, le plus petit possible qui contient les 27 lettres.
        for (float r = 105 * dp; r <= 150 * dp; r += 3 * dp) {
            float[] arc = visibleArc(r);
            if (arc != null && capacity(r, arc[1], spacing) >= n) {
                placeOnArc(0, n, r, arc);
                return;
            }
        }
        // Sinon : plusieurs anneaux, du plus proche au plus éloigné.
        int placed = 0;
        float r = 90 * dp;
        for (int guard = 0; placed < n && guard < 12; guard++, r += 42 * dp) {
            float[] arc = visibleArc(r);
            if (arc == null) continue;
            int take = Math.min(n - placed, capacity(r, arc[1], spacing));
            if (take <= 0) continue;
            placeOnArc(placed, take, r, arc);
            placed += take;
        }
        // Filet de sécurité (écran minuscule) : le reste sur le dernier cercle.
        for (int i = placed; i < n; i++) {
            letterRad[i] = r;
            letterAng[i] = -90 + 360f * i / n;
        }
    }

    private int capacity(float r, float sweep, float spacing) {
        double len = Math.toRadians(sweep) * r;
        return sweep >= 360f ? (int) (len / spacing) : (int) (len / spacing) + 1;
    }

    private void placeOnArc(int from, int count, float r, float[] arc) {
        float start = arc[0], sweep = arc[1];
        boolean full = sweep >= 360f;
        float step = full ? 360f / count : (count > 1 ? sweep / (count - 1) : 0f);
        float first = full ? -90f : (count > 1 ? start : start + sweep / 2f);
        for (int i = 0; i < count; i++) {
            letterRad[from + i] = r;
            letterAng[from + i] = first + i * step;
        }
        if (full) arcs.add(new float[]{r, 0f, 360f});
        else arcs.add(new float[]{r, first, step * (count - 1)});
    }

    /** Plus grande portion du cercle de rayon r qui tient dans l'écran : {début, balayage} en degrés. */
    private float[] visibleArc(float r) {
        float m = 20 * dp;
        float left = safe.left + m, top = safe.top + m;
        float right = getWidth() - safe.right - m, bottom = getHeight() - safe.bottom - m;
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
            if (!ok[a] || ok[(a + 359) % 360]) continue; // début d'une plage visible
            int len = 0;
            while (len < 360 && ok[(a + len) % 360]) len++;
            if (len > bestLen) {
                bestLen = len;
                bestStart = a;
            }
        }
        // Une petite marge à chaque bout de l'arc.
        if (bestLen > 4) return new float[]{bestStart + 1, bestLen - 3};
        return new float[]{bestStart, Math.max(0, bestLen - 1)};
    }

    /** Place les applis en nid d'abeille autour du centre, en restant dans l'écran. */
    private void buildCells() {
        cells.clear();
        hoverCell = HIT_NONE;
        List<AppRepository.AppEntry> list = byLetter.get(letter);
        if (list == null) list = Collections.emptyList();
        float s = 78 * dp, rowH = s * 0.866f;
        float left = safe.left + 38 * dp, right = getWidth() - safe.right - 38 * dp;
        float top = safe.top + 36 * dp, bottom = getHeight() - safe.bottom - 34 * dp;
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

    private void showLetter(String l) {
        letter = l;
        page = 0;
        mode = MODE_APPS;
        hoverLetter = HIT_NONE;
        buildCells();
        animate(0f, 1f, 240);
    }

    private void showLetters() {
        mode = MODE_LETTERS;
        letter = null;
        hoverCell = HIT_NONE;
        animate(0f, 1f, 200);
    }

    private void animate(float from, float to, long ms) {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(ms);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            dim = closing ? progress : Math.max(dim, progress);
            invalidate();
            if (closing && a.getAnimatedFraction() >= 1f && listener != null) listener.onClosed();
        });
        animator.start();
    }

    // ---------------------------------------------------------------- dessin

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.argb((int) (165 * dim), 0, 0, 0));
        if (mode == MODE_LETTERS) {
            drawLetters(canvas);
            if (dragging || (!anchorUsable && !prefs.contains("x"))) drawHint(canvas);
        }
        else drawApps(canvas);
    }

    private void drawLetters(Canvas canvas) {
        float p = progress;
        float grow = 0.55f + 0.45f * p;
        int alpha = (int) (255 * clamp(p, 0f, 1f));

        band.setAlpha((int) (Color.alpha(COLOR_PANEL) * clamp(p, 0f, 1f)));
        for (float[] arc : arcs) {
            float r = arc[0] * grow;
            if (arc[2] >= 360f) {
                canvas.drawCircle(cx, cy, r, band);
            } else {
                oval.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(oval, arc[1], arc[2], false, band);
            }
        }

        float letterSize = 15 * dp;
        for (int i = 0; i < LETTERS.length; i++) {
            float x = letterX(i, grow), y = letterY(i, grow);
            boolean available = byLetter.containsKey(LETTERS[i]);
            boolean hover = i == hoverLetter;
            if (hover) {
                fill.setColor(available ? COLOR_ACCENT : 0x66FFFFFF);
                fill.setAlpha(alpha);
                canvas.drawCircle(x, y, 19 * dp, fill);
            }
            text.setTextSize(hover ? letterSize * 1.25f : letterSize);
            text.setColor(hover && available ? 0xFF111320 : Color.WHITE);
            text.setAlpha(available ? alpha : alpha / 4);
            drawCentered(canvas, LETTERS[i], x, y, text);
        }

        fill.setColor(COLOR_PANEL);
        fill.setAlpha(alpha);
        canvas.drawCircle(cx, cy, 32 * dp * (0.6f + 0.4f * p), fill);
        if (hoverLetter >= 0) {
            String l = LETTERS[hoverLetter];
            List<AppRepository.AppEntry> list = byLetter.get(l);
            text.setColor(COLOR_ACCENT);
            text.setAlpha(alpha);
            text.setTextSize(24 * dp);
            drawCentered(canvas, l, cx, cy - 4 * dp, text);
            label.setAlpha(alpha);
            canvas.drawText(list == null ? "0" : String.valueOf(list.size()), cx, cy + 20 * dp, label);
        } else {
            text.setColor(0xAAFFFFFF);
            text.setAlpha(alpha * 2 / 3);
            text.setTextSize(22 * dp);
            drawCentered(canvas, "×", cx, cy, text);
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
            boolean hover = i == hoverCell;
            float scale = hover ? 1.12f : 1f;
            float iy = y - 8 * dp;
            if (hover) {
                fill.setColor(0x40FFFFFF);
                fill.setAlpha(alpha / 4);
                canvas.drawCircle(x, iy, 33 * dp, fill);
            }
            if (c.app == null) {
                fill.setColor(0x55FFFFFF);
                fill.setAlpha(alpha / 3);
                canvas.drawCircle(x, iy, iconHalf * scale, fill);
                text.setColor(Color.WHITE);
                text.setAlpha(alpha);
                text.setTextSize(15 * dp);
                drawCentered(canvas, c.more > 0 ? "+" + c.more : "↺", x, iy, text);
                continue;
            }
            Drawable icon = c.app.icon;
            float half = iconHalf * scale;
            if (icon != null) {
                icon.setBounds((int) (x - half), (int) (iy - half), (int) (x + half), (int) (iy + half));
                icon.setAlpha(alpha);
                icon.draw(canvas);
                icon.setAlpha(255);
            } else {
                fill.setColor(0x33FFFFFF);
                fill.setAlpha(alpha / 5);
                canvas.drawCircle(x, iy, half, fill);
            }
            label.setAlpha(alpha);
            CharSequence name = TextUtils.ellipsize(c.app.label, label, 74 * dp, TextUtils.TruncateAt.END);
            canvas.drawText(name, 0, name.length(), x, y + 30 * dp, label);
        }

        // Le centre : la lettre choisie (toucher = revenir aux lettres)
        int alpha = (int) (255 * clamp(p, 0f, 1f));
        fill.setColor(COLOR_ACCENT);
        fill.setAlpha(Math.max(alpha, 140));
        canvas.drawCircle(cx, cy, 28 * dp, fill);
        text.setColor(0xFF111320);
        text.setTextSize(24 * dp);
        drawCentered(canvas, letter == null ? "" : letter, cx, cy, text);
    }

    private void drawHint(Canvas canvas) {
        String s = dragging ? "Lâche le doigt sur ton icône" : "Maintiens le × puis glisse pour placer la roue";
        float y = cy > getHeight() / 2f ? safe.top + 60 * dp : getHeight() - safe.bottom - 60 * dp;
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

    private float letterX(int i, float grow) {
        return cx + (float) (letterRad[i] * grow * Math.cos(Math.toRadians(letterAng[i])));
    }

    private float letterY(int i, float grow) {
        return cy + (float) (letterRad[i] * grow * Math.sin(Math.toRadians(letterAng[i])));
    }

    private int letterAt(float x, float y) {
        if (Math.hypot(x - cx, y - cy) < 34 * dp) return HIT_CENTER;
        int best = HIT_NONE;
        double bestD = 34 * dp;
        for (int i = 0; i < LETTERS.length; i++) {
            double d = Math.hypot(x - letterX(i, 1f), y - letterY(i, 1f));
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
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
                updateHover(x, y);
                if (mode == MODE_APPS && hoverCell >= 0) postDelayed(longPress, LONG_PRESS_MS);
                if (mode == MODE_LETTERS && letterAt(x, y) == HIT_CENTER) postDelayed(startDrag, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                if (dragging) {
                    cx = clamp(x, safe.left + 30 * dp, getWidth() - safe.right - 30 * dp);
                    cy = clamp(y, safe.top + 30 * dp, getHeight() - safe.bottom - 30 * dp);
                    layoutLetters();
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
                    prefs.edit().putFloat("x", cx / getWidth()).putFloat("y", cy / getHeight()).apply();
                    invalidate();
                    return true;
                }
                if (longPressFired) return true;
                if (waitMoveAfterSwitch) {
                    // Les applis viennent de s'ouvrir sous le doigt : on les laisse affichées.
                    waitMoveAfterSwitch = false;
                    return true;
                }
                release(x, y);
                return true;

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(startDrag);
                dragging = false;
                removeCallbacks(longPress);
                removeCallbacks(dwell);
                tracking = false;
                hoverLetter = HIT_NONE;
                hoverCell = HIT_NONE;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void updateHover(float x, float y) {
        if (mode == MODE_LETTERS) {
            int hit = letterAt(x, y);
            int h = hit >= 0 ? hit : HIT_NONE;
            if (h != hoverLetter) {
                hoverLetter = h;
                removeCallbacks(dwell);
                if (h >= 0 && byLetter.containsKey(LETTERS[h])) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    postDelayed(dwell, DWELL_MS);
                }
                invalidate();
            }
        } else {
            int hit = cellAt(x, y);
            int h = hit >= 0 ? hit : HIT_NONE;
            if (h != hoverCell) {
                hoverCell = h;
                removeCallbacks(longPress);
                if (h >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                invalidate();
            }
        }
    }

    private void release(float x, float y) {
        if (mode == MODE_LETTERS) {
            int hit = letterAt(x, y);
            hoverLetter = HIT_NONE;
            if (hit >= 0) {
                if (byLetter.containsKey(LETTERS[hit])) showLetter(LETTERS[hit]);
                else invalidate();
            } else if (hit == HIT_CENTER || !moved) {
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
                if (listener != null) listener.onLaunch(c.app, screenBounds(c));
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

    private Rect screenBounds(Cell c) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int half = (int) (23 * dp);
        int x = (int) c.x + loc[0], y = (int) (c.y - 8 * dp) + loc[1];
        return new Rect(x - half, y - half, x + half, y + half);
    }

    private static float clamp(float v, float lo, float hi) {
        if (lo > hi) return (lo + hi) / 2f;
        return Math.max(lo, Math.min(hi, v));
    }
}
