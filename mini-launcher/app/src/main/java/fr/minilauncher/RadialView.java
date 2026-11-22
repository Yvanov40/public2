package fr.minilauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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

    private Listener listener;
    private final Map<String, List<AppRepository.AppEntry>> byLetter = new HashMap<>();

    private Rect anchor;
    private int mode = MODE_LETTERS;
    private float cx, cy, ringR;
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
        float m = 8 * dp, letterR = 16 * dp;
        float left = safe.left + m, top = safe.top + m;
        float right = w - safe.right - m, bottom = h - safe.bottom - m;
        ringR = Math.max(60 * dp, Math.min(135 * dp, Math.min(right - left, bottom - top) / 2f - letterR));
        float ax = w / 2f, ay = h * 0.62f;
        if (anchor != null) {
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            ax = anchor.exactCenterX() - loc[0];
            ay = anchor.exactCenterY() - loc[1];
        }
        float ext = ringR + letterR;
        cx = clamp(ax, left + ext, right - ext);
        cy = clamp(ay, top + ext, bottom - ext);
        band.setStrokeWidth(40 * dp);
        if (mode == MODE_APPS) buildCells();
        invalidate();
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
        if (mode == MODE_LETTERS) drawLetters(canvas);
        else drawApps(canvas);
    }

    private void drawLetters(Canvas canvas) {
        float p = progress;
        float r = ringR * (0.55f + 0.45f * p);
        int alpha = (int) (255 * clamp(p, 0f, 1f));

        band.setAlpha((int) (Color.alpha(COLOR_PANEL) * clamp(p, 0f, 1f)));
        canvas.drawCircle(cx, cy, r, band);

        float letterSize = 15 * dp;
        for (int i = 0; i < LETTERS.length; i++) {
            double a = Math.toRadians(-90 + i * 360.0 / LETTERS.length);
            float x = cx + (float) (r * Math.cos(a));
            float y = cy + (float) (r * Math.sin(a));
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

    private void drawCentered(Canvas canvas, String s, float x, float y, Paint paint) {
        paint.getTextBounds(s, 0, s.length(), tmp);
        canvas.drawText(s, x, y + tmp.height() / 2f - tmp.bottom, paint);
    }

    // ---------------------------------------------------------------- toucher

    private int letterAt(float x, float y) {
        float dx = x - cx, dy = y - cy;
        double d = Math.hypot(dx, dy);
        if (d < 34 * dp) return HIT_CENTER;
        if (d > ringR + 60 * dp) return HIT_NONE;
        double ang = Math.toDegrees(Math.atan2(dy, dx)) + 90;
        if (ang < 0) ang += 360;
        double step = 360.0 / LETTERS.length;
        return (int) Math.round(ang / step) % LETTERS.length;
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
                updateHover(x, y);
                if (mode == MODE_APPS && hoverCell >= 0) postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
                    moved = true;
                    removeCallbacks(longPress);
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
                if (!tracking) return true;
                tracking = false;
                if (longPressFired) return true;
                if (waitMoveAfterSwitch) {
                    // Les applis viennent de s'ouvrir sous le doigt : on les laisse affichées.
                    waitMoveAfterSwitch = false;
                    return true;
                }
                release(x, y);
                return true;

            case MotionEvent.ACTION_CANCEL:
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
