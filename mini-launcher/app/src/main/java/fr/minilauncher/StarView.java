package fr.minilauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

/**
 * L'étoile : 8 applis autour du doigt (0 = haut, puis dans le sens des aiguilles d'une montre).
 * Elle ne s'affiche que si l'on hésite ; un coup de doigt rapide dans une direction ouvre
 * l'appli sans la montrer.
 */
final class StarView extends View {

    static final String[] DIRECTIONS = {"Haut", "Haut-droite", "Droite", "Bas-droite", "Bas",
            "Bas-gauche", "Gauche", "Haut-gauche"};

    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect tb = new Rect();
    private final RectF pill = new RectF();

    private AppRepository.AppEntry[] apps = new AppRepository.AppEntry[8];
    private float cx, cy;
    private boolean visible;
    private int selected = -1;

    StarView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return false; // l'accueil gère les touchers
    }

    float radius() {
        return 92 * dp;
    }

    /** Prépare l'étoile autour du point (x, y), sans l'afficher encore. */
    void arm(float x, float y, AppRepository.AppEntry[] apps) {
        this.apps = apps;
        float m = radius() + 34 * dp;
        cx = Math.max(m, Math.min(getWidth() - m, x));
        cy = Math.max(m, Math.min(getHeight() - m, y));
        selected = -1;
        visible = false;
        invalidate();
    }

    void reveal() {
        visible = true;
        invalidate();
    }

    void select(int dir) {
        if (dir != selected) {
            selected = dir;
            invalidate();
        }
    }

    void hide() {
        visible = false;
        selected = -1;
        invalidate();
    }

    boolean isVisible() {
        return visible;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!visible) return;
        Store st = Store.get(getContext());
        int accent = st.accent();
        canvas.drawColor(Color.argb((int) (255 * st.dimPercent() / 100f * 0.7f), 0, 0, 0));
        float r = radius(), half = 22 * dp;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(-90 + i * 45);
            float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
            boolean sel = i == selected;
            fill.setColor(sel ? accent : 0xE61B1D26);
            canvas.drawCircle(x, y, (sel ? 34 : 30) * dp, fill);
            AppRepository.AppEntry app = apps[i];
            if (app != null && app.icon != null) {
                Drawable d = app.icon;
                float h = sel ? half * 1.12f : half;
                d.setBounds((int) (x - h), (int) (y - h), (int) (x + h), (int) (y + h));
                d.draw(canvas);
            } else {
                text.setTextSize(22 * dp);
                text.setColor(sel ? 0xFF111320 : 0x99FFFFFF);
                text.getTextBounds("+", 0, 1, tb);
                canvas.drawText("+", x, y + tb.height() / 2f - tb.bottom, text);
            }
        }
        fill.setColor(accent);
        canvas.drawCircle(cx, cy, 7 * dp, fill);

        // Le nom de l'appli visée (ou « choisir une appli » pour une branche vide).
        if (selected >= 0) {
            AppRepository.AppEntry app = apps[selected];
            String s = app != null ? app.label : "Lâcher pour choisir une appli";
            text.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
            text.setColor(Color.WHITE);
            CharSequence t = TextUtils.ellipsize(s, text, 240 * dp, TextUtils.TruncateAt.END);
            float tw = text.measureText(t, 0, t.length());
            float ty = cy + r + 62 * dp;
            if (ty > getHeight() - 40 * dp) ty = cy - r - 50 * dp;
            fill.setColor(0xF01B1D26);
            pill.set(cx - tw / 2 - 14 * dp, ty - 21 * dp, cx + tw / 2 + 14 * dp, ty + 9 * dp);
            canvas.drawRoundRect(pill, 15 * dp, 15 * dp, fill);
            canvas.drawText(t, 0, t.length(), cx, ty, text);
        }
    }
}
