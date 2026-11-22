package fr.minilauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Facultatif : garde le processus du launcher en vie au premier plan, pour qu'HyperOS ne
 * l'arrête pas quand on ferme toutes les applis (sinon l'accueil redémarre entièrement).
 * Android impose une notification ; elle est réglée au plus discret.
 */
public final class KeepAliveService extends Service {

    private static final String CHANNEL = "actif";

    /** Démarre ou arrête le service selon le réglage. */
    static void sync(Context c) {
        Intent i = new Intent(c, KeepAliveService.class);
        try {
            if (Store.get(c).keepAlive()) c.startForegroundService(i);
            else c.stopService(i);
        } catch (RuntimeException ignored) {
            // Démarrage refusé en arrière-plan : on réessaiera à la prochaine ouverture de l'accueil.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Launcher actif", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Mini Launcher reste actif")
                .setContentText("Pour ne pas redémarrer quand on ferme toutes les applis")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
