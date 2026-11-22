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

    private static final class Spot {
        float x, y;
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
    private int color;
    private float cx, cy;
    private boolean open, sticky, moved, tracking;
    private int hover = -1;
    private float downX, downY;
    private long openedAt;

    private final Runnable longPress = () -> {
        if (moved || hover < 0 || listener == null) return;
        tracking = false;
        haptic(HapticFeedbackConstants.LONG_PRESS);
        listener.onFolderAppMenu(spots.get(hover).app, folder);
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

    void close() {
        removeCallbacks(longPress);
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
        float scale = store.folderRadius() / 100f;
        float icon = store.folderIcon() * dp;
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
        int best = -1;
        double bestD = Math.max(36 * dp, store.folderIcon() * dp * 0.75f);
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
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracking = true;
                downX = x;
                downY = y;
                moved = false;
                if (sticky) {
                    hover = spotAt(x, y);
                    if (hover >= 0) postDelayed(longPress, LONG_PRESS_MS);
                    else if (Math.hypot(x - cx, y - cy) > 34 * dp) close();
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!tracking) return true;
                if (!moved && Math.hypot(x - downX, y - downY) > 10 * dp) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                int h = spotAt(x, y);
                if (h != hover) {
                    hover = h;
                    if (h >= 0) haptic(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
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
        float iconPx = store.folderIcon() * dp;
        band.setColor(0xFF000000 | store.folderBg());
        band.setAlpha((int) (255 * store.folderBgAlpha() / 100f * ease));
        band.setStrokeWidth(iconPx + (labels ? 34 : 16) * dp);
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
                canvas.drawCircle(x, y, hh + 7 * dp, fill);
            }
            Drawable icon = s.app.icon;
            if (icon != null) {
                icon.setBounds((int) (x - hh), (int) (y - hh), (int) (x + hh), (int) (y + hh));
                icon.setAlpha((int) (255 * k));
                icon.draw(canvas);
                icon.setAlpha(255);
            }
            if (labels) {
                small.setAlpha((int) (255 * k));
                CharSequence nm = TextUtils.ellipsize(s.app.label, small, 64 * dp, TextUtils.TruncateAt.END);
                canvas.drawText(nm, 0, nm.length(), x, y + hh + 12 * dp, small);
            }
        }

        // Centre : la pastille du dossier, avec le nom de l'appli survolée ou du dossier.
        fill.setColor(color);
        fill.setAlpha(255);
        canvas.drawCircle(cx, cy, 26 * dp * (0.6f + 0.4f * ease), fill);
        String title = hover >= 0 ? spots.get(hover).app.label : folder;
        CharSequence s = TextUtils.ellipsize(title, text, 220 * dp, TextUtils.TruncateAt.END);
        float tw = text.measureText(s, 0, s.length());
        float ty = cy - 40 * dp;
        if (ty < Math.max(safe.top, 24 * dp) + 30 * dp) ty = cy + 52 * dp;
        fill.setColor(0xF01B1D26);
        oval.set(cx - tw / 2 - 12 * dp, ty - 20 * dp, cx + tw / 2 + 12 * dp, ty + 8 * dp);
        canvas.drawRoundRect(oval, 14 * dp, 14 * dp, fill);
        canvas.drawText(s, 0, s.length(), cx, ty, text);

        if (t < 1f) postInvalidateOnAnimation();
    }
}
