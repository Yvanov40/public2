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

    // Le voile appartient au processus, pas au service : la tuile du centre de contrôle
    // peut ainsi l'afficher tout de suite, avant même que le service ait démarré.
    private static WindowManager windowManager;
    private static View voile;
    private static WindowManager.LayoutParams params;

    /** Allume le voile (ou change son niveau) et garde l'appli en vie avec une notification. */
    static void regler(Context ctx, int niveau) {
        ctx = ctx.getApplicationContext();
        if (!Settings.canDrawOverlays(ctx)) return;
        Reglages.setNiveau(ctx, niveau);
        Reglages.setActif(ctx, true);
        actif = true;
        afficher(ctx, niveau);
        Intent i = new Intent(ctx, FiltreService.class).setAction(ACTION_REGLER).putExtra(EXTRA_NIVEAU, niveau);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (RuntimeException e) {
            // Démarrage refusé par Android : le voile reste affiché, sans notification.
        }
        majTuile(ctx);
    }

    static void arreter(Context ctx) {
        ctx = ctx.getApplicationContext();
        actif = false;
        Reglages.setActif(ctx, false);
        cacher();
        ctx.stopService(new Intent(ctx, FiltreService.class));
        majTuile(ctx);
    }

    private static void majTuile(Context ctx) {
        if (Build.VERSION.SDK_INT >= 24) {
            android.service.quicksettings.TileService.requestListeningState(ctx,
                    new android.content.ComponentName(ctx, FiltreTuile.class));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        int niveau = intent == null
                ? Reglages.niveau(this)
                : intent.getIntExtra(EXTRA_NIVEAU, Reglages.niveau(this));
        demarrerPremierPlan(niveau);
        if (ACTION_ARRETER.equals(action) || !Settings.canDrawOverlays(this) || !Reglages.actif(this)) {
            arreter(this);
            return START_NOT_STICKY;
        }
        afficher(getApplicationContext(), niveau);
        actif = true;
        return START_STICKY;
    }

    private static void afficher(Context ctx, int niveau) {
        float alpha = Math.max(0, Math.min(NIVEAU_MAX, niveau)) / 100f;
        if (voile == null) {
            windowManager = (WindowManager) ctx.getSystemService(WINDOW_SERVICE);
            voile = new View(ctx);
            voile.setBackgroundColor(Color.BLACK);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            int taille = 2 * Math.max(ctx.getResources().getDisplayMetrics().widthPixels,
                    ctx.getResources().getDisplayMetrics().heightPixels);
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

    private static void cacher() {
        if (voile != null) {
            windowManager.removeView(voile);
            voile = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
