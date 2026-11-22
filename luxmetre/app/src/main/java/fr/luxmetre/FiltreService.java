package fr.luxmetre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * Pose un voile noir semi-transparent sur tout l'écran, par-dessus les autres applis,
 * pour assombrir l'affichage en dessous du minimum réglable du téléphone.
 * Les touchers passent à travers le voile.
 */
public class FiltreService extends Service {

    static final String ACTION_REGLER = "fr.luxmetre.REGLER";
    static final String ACTION_ARRETER = "fr.luxmetre.ARRETER";
    static final String EXTRA_NIVEAU = "niveau";

    /** Android 12+ bloque les touchers sous une fenêtre plus opaque que 80 %. */
    static final int NIVEAU_MAX = 80;

    private static final String CANAL = "filtre";
    private static final int NOTIF_ID = 1;

    static volatile boolean actif = false;

    private WindowManager windowManager;
    private View voile;
    private WindowManager.LayoutParams params;

    static void regler(Context ctx, int niveau) {
        Intent i = new Intent(ctx, FiltreService.class).setAction(ACTION_REGLER).putExtra(EXTRA_NIVEAU, niveau);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
    }

    static void arreter(Context ctx) {
        ctx.startService(new Intent(ctx, FiltreService.class).setAction(ACTION_ARRETER));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_ARRETER.equals(action) || !Settings.canDrawOverlays(this)) {
            arreterTout();
            return START_NOT_STICKY;
        }
        int niveau = intent == null
                ? Reglages.niveau(this)
                : intent.getIntExtra(EXTRA_NIVEAU, Reglages.niveau(this));
        demarrerPremierPlan(niveau);
        afficher(niveau);
        actif = true;
        return START_STICKY;
    }

    private void afficher(int niveau) {
        float alpha = Math.max(0, Math.min(NIVEAU_MAX, niveau)) / 100f;
        if (voile == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            voile = new View(this);
            voile.setBackgroundColor(Color.BLACK);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            int taille = 2 * Math.max(getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            params = new WindowManager.LayoutParams(taille, taille, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.x = 0;
            params.y = 0;
            params.alpha = alpha;
            windowManager.addView(voile, params);
        } else {
            params.alpha = alpha;
            windowManager.updateViewLayout(voile, params);
        }
    }

    private void demarrerPremierPlan(int niveau) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CANAL, "Filtre d'écran", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CANAL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent ouvrir = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FiltreService.class).setAction(ACTION_ARRETER),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = b.setSmallIcon(R.drawable.icone)
                .setContentTitle("Filtre d'écran actif : " + niveau + " %")
                .setContentText("Touchez « Désactiver » pour retrouver l'écran normal")
                .setContentIntent(ouvrir)
                .addAction(new Notification.Action.Builder(null, "Désactiver", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void arreterTout() {
        actif = false;
        Reglages.setActif(this, false);
        if (voile != null) {
            windowManager.removeView(voile);
            voile = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (voile != null) {
            windowManager.removeView(voile);
            voile = null;
        }
        actif = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
