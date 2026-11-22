package fr.luxmetre;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * Mode simple du filtre : voile par superposition sur les autres applis.
 * Ne couvre pas la barre d'état ni la barre de navigation (Android l'interdit).
 */
public class FiltreService extends Service {

    private Voile voile;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int niveau = Math.min(Reglages.niveau(this), Filtre.MAX_SIMPLE);
        Notification n = Filtre.notification(this, niveau);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Filtre.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Filtre.NOTIF_ID, n);
        }
        if (!Reglages.actif(this) || !Settings.canDrawOverlays(this) || Filtre.complet()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (voile == null) {
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
            voile = new Voile(this, type);
        }
        voile.afficher(niveau);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (voile != null) voile.cacher();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
