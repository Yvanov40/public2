package fr.minilauncher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Pastilles de notification : nombres par appli (fournis par NotifService) et dessin. */
final class Badges {

    private static Map<String, Integer> counts = Collections.emptyMap();
    private static boolean connected;
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Rect tb = new Rect();

    static final int[] COLORS = {0xFFFF5A5F, 0xFFFFB86B, 0xFF7EE0A1, 0xFF6FD3FF, 0x00000000};
    static final String[] COLOR_NAMES = {"Rouge", "Orange", "Vert", "Bleu ciel", "Couleur d'accent"};

    private Badges() {
    }

    static void set(Map<String, Integer> c, boolean isConnected) {
        counts = new HashMap<>(c);
        connected = isConnected;
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    static boolean isConnected() {
        return connected;
    }

    /** L'utilisateur a-t-il donné l'accès aux notifications ? */
    static boolean accessGranted(Context c) {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            if (nm != null) return nm.isNotificationListenerAccessGranted(new ComponentName(c, NotifService.class));
        }
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        String me = new ComponentName(c, NotifService.class).flattenToString();
        String meShort = new ComponentName(c, NotifService.class).flattenToShortString();
        for (String s : flat.split(":")) if (s.equals(me) || s.equals(meShort)) return true;
        return false;
    }

    static int count(Context c, String pkg) {
        if (!Store.get(c).badges()) return 0;
        Integer n = counts.get(pkg);
        return n == null ? 0 : n;
    }

    static int count(Context c, AppRepository.AppEntry app) {
        return app == null ? 0 : count(c, app.component.getPackageName());
    }

    /** Somme pour une liste d'applis (dossier, lettre). */
    static int count(Context c, List<AppRepository.AppEntry> apps) {
        if (!Store.get(c).badges()) return 0;
        int sum = 0;
        for (AppRepository.AppEntry e : apps) sum += count(c, e);
        return sum;
    }

    private static int color(Context c) {
        int col = Store.get(c).badgeColor();
        return col == 0 ? Store.get(c).accent() : col;
    }

    /**
     * Dessine la pastille au coin haut-droit d'une icône de centre (x, y) et de demi-taille
     * {@code half}. Style : point ou nombre (réglage).
     */
    static void draw(Context c, Canvas canvas, float x, float y, float half, int n) {
        if (n <= 0) return;
        float dp = c.getResources().getDisplayMetrics().density;
        boolean number = Store.get(c).badgeStyle() == 1;
        float bx = x + half * 0.78f, by = y - half * 0.78f;
        fill.setColor(color(c));
        if (!number) {
            float r = Math.max(4.5f * dp, half * 0.2f);
            fill.setShadowLayer(2 * dp, 0, dp * 0.5f, 0x66000000);
            canvas.drawCircle(bx, by, r, fill);
            fill.clearShadowLayer();
            return;
        }
        String s = n > 99 ? "99+" : String.valueOf(n);
        text.setColor(Color.WHITE);
        text.setFakeBoldText(true);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(Math.max(10 * dp, half * 0.42f));
        text.getTextBounds(s, 0, s.length(), tb);
        float h = text.getTextSize() * 1.45f;
        float w = Math.max(h, tb.width() + h * 0.6f);
        fill.setShadowLayer(2 * dp, 0, dp * 0.5f, 0x66000000);
        canvas.drawRoundRect(bx - w / 2, by - h / 2, bx + w / 2, by + h / 2, h / 2, h / 2, fill);
        fill.clearShadowLayer();
        canvas.drawText(s, bx, by + tb.height() / 2f - tb.bottom, text);
    }

    /** Petit point à côté d'une lettre de l'alphabet. */
    static void dot(Context c, Canvas canvas, float x, float y, float r) {
        fill.setColor(color(c));
        canvas.drawCircle(x, y, r, fill);
    }
}
