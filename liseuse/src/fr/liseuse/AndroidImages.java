package fr.liseuse;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.ByteArrayOutputStream;

/** Images pour Android : réduction des très grandes images, couverture générée. */
public final class AndroidImages implements DocxConverter.Images {

    private static final int MAX = 1600;

    @Override
    public Object[] traiter(byte[] data, String ext, boolean compresser) {
        if (ext.equals("jpeg")) ext = "jpg";
        if (ext.equals("svg")) return new Object[]{data, ext};
        if (ext.equals("gif")) return new Object[]{data, ext};
        boolean web = ext.equals("png") || ext.equals("jpg") || ext.equals("webp");
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return web ? new Object[]{data, ext} : null;
            int grand = Math.max(o.outWidth, o.outHeight);
            if (web && (!compresser || (grand <= MAX && data.length < 600 * 1024))) return new Object[]{data, ext};
            int ech = 1;
            while (grand / (ech * 2) >= MAX) ech *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = ech;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            if (b == null) return web ? new Object[]{data, ext} : null;
            int g = Math.max(b.getWidth(), b.getHeight());
            if (g > MAX) {
                float f = MAX / (float) g;
                Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * f)), Math.max(1, Math.round(b.getHeight() * f)), true);
                if (s != b) b.recycle();
                b = s;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            String sortie;
            if (b.hasAlpha() && !ext.equals("jpg")) {
                b.compress(Bitmap.CompressFormat.PNG, 100, bo);
                sortie = "png";
            } else {
                b.compress(Bitmap.CompressFormat.JPEG, 85, bo);
                sortie = "jpg";
            }
            b.recycle();
            byte[] r = bo.toByteArray();
            if (web && r.length >= data.length) return new Object[]{data, ext};
            return new Object[]{r, sortie};
        } catch (Throwable t) {
            return web ? new Object[]{data, ext} : null;
        }
    }

    private static final int[][] TEINTES = {
            {0xFF1F3A5F, 0xFF3D6B99}, {0xFF5B2333, 0xFF9E3B54}, {0xFF234F3B, 0xFF3F8A66},
            {0xFF4A3B6B, 0xFF7A63A8}, {0xFF6B4A23, 0xFFA8783F}, {0xFF2C4A52, 0xFF4F8794},
            {0xFF3B3B3B, 0xFF6E6E6E}, {0xFF6B2E1F, 0xFFB5543A}};

    @Override
    public byte[] couverture(String titre, String auteur) {
        try {
            int w = 900, h = 1350;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            int[] t = TEINTES[Math.abs((titre == null ? 0 : titre.hashCode()) % TEINTES.length)];
            Paint fond = new Paint();
            fond.setShader(new LinearGradient(0, 0, w, h, t[0], t[1], Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fond);
            Paint cadre = new Paint(Paint.ANTI_ALIAS_FLAG);
            cadre.setStyle(Paint.Style.STROKE);
            cadre.setStrokeWidth(4);
            cadre.setColor(0x66FFFFFF);
            c.drawRect(50, 50, w - 50, h - 50, cadre);
            Paint ligne = new Paint(Paint.ANTI_ALIAS_FLAG);
            ligne.setColor(0xCCFFFFFF);
            ligne.setStrokeWidth(3);
            c.drawLine(w / 2f - 90, h * 0.62f, w / 2f + 90, h * 0.62f, ligne);

            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.WHITE);
            tp.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            String ti = titre == null || titre.isEmpty() ? "Sans titre" : titre;
            float taille = ti.length() > 60 ? 58 : ti.length() > 30 ? 72 : 88;
            tp.setTextSize(taille);
            StaticLayout sl = new StaticLayout(ti, tp, w - 160, Layout.Alignment.ALIGN_CENTER, 1.05f, 0, false);
            while (sl.getHeight() > h * 0.45 && taille > 30) {
                taille -= 6;
                tp.setTextSize(taille);
                sl = new StaticLayout(ti, tp, w - 160, Layout.Alignment.ALIGN_CENTER, 1.05f, 0, false);
            }
            c.save();
            c.translate(80, h * 0.58f - sl.getHeight());
            sl.draw(c);
            c.restore();
            if (auteur != null && !auteur.isEmpty()) {
                TextPaint ta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                ta.setColor(0xE6FFFFFF);
                ta.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
                ta.setTextSize(48);
                StaticLayout la = new StaticLayout(auteur, ta, w - 160, Layout.Alignment.ALIGN_CENTER, 1f, 0, false);
                c.save();
                c.translate(80, h * 0.66f);
                la.draw(c);
                c.restore();
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            b.recycle();
            return bo.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }
}
