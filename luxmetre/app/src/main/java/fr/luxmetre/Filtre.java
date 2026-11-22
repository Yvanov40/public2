package fr.luxmetre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Pilote le filtre d'écran. Deux modes :
 * - complet (service d'accessibilité activé) : couvre aussi les barres système, jusqu'à 95 % ;
 * - simple (superposition) : ne couvre pas les barres système, 80 % au plus
 *   (au-delà, Android 12+ bloquerait les touchers).
 */
final class Filtre {
    private Filtre() { }

    static final int MAX_SIMPLE = 80;
    static final int MAX_COMPLET = 95;

    private static final String CANAL = "filtre";
    static final int NOTIF_ID = 1;

    static boolean complet() {
        return FiltreAccessibilite.instance != null;
    }

    static int max() {
        return complet() ? MAX_COMPLET : MAX_SIMPLE;
    }

    static void activer(Context ctx) {
        Reglages.setActif(ctx, true);
        appliquer(ctx);
    }

    /** Affiche le voile au niveau enregistré, avec le mode disponible. */
    static void appliquer(Context ctx) {
        if (!Reglages.actif(ctx)) return;
        FiltreAccessibilite acc = FiltreAccessibilite.instance;
        if (acc != null) {
            ctx.stopService(new Intent(ctx, FiltreService.class));
            int niveau = Math.min(Reglages.niveau(ctx), MAX_COMPLET);
            acc.afficher(niveau);
            notifier(ctx, niveau);
        } else {
            Intent i = new Intent(ctx, FiltreService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        }
    }

    static void arreter(Context ctx) {
        Reglages.setActif(ctx, false);
        FiltreAccessibilite acc = FiltreAccessibilite.instance;
        if (acc != null) acc.cacher();
        ctx.stopService(new Intent(ctx, FiltreService.class));
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
    }

    private static void notifier(Context ctx, int niveau) {
        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, notification(ctx, niveau));
    }

    static Notification notification(Context ctx, int niveau) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CANAL, "Filtre d'écran", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(ctx, CANAL);
        } else {
            b = new Notification.Builder(ctx);
        }
        PendingIntent ouvrir = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getBroadcast(ctx, 1,
                new Intent(ctx, ArretReceiver.class), PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.icone)
                .setContentTitle("Filtre d'écran actif : " + niveau + " %")
                .setContentText("Touchez « Désactiver » pour retrouver l'écran normal")
                .setContentIntent(ouvrir)
                .addAction(new Notification.Action.Builder(null, "Désactiver", stop).build())
                .setOngoing(true)
                .build();
    }
}
