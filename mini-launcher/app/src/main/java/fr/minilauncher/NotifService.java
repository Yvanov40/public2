package fr.minilauncher;

import android.app.Notification;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * Facultatif (« Accès aux notifications ») : compte les notifications de chaque appli pour
 * afficher des pastilles. Ne lit ni n'enregistre leur contenu.
 */
public final class NotifService extends NotificationListenerService {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onListenerConnected() {
        recount();
    }

    @Override
    public void onListenerDisconnected() {
        Badges.set(new HashMap<>(), false);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        recount();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        recount();
    }

    private void recount() {
        Map<String, Integer> counts = new HashMap<>();
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) {
                for (StatusBarNotification sbn : all) {
                    Notification n = sbn.getNotification();
                    // Ni les notifications permanentes (musique, service…), ni les résumés de groupe.
                    if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                    int c = n.number > 0 ? n.number : 1;
                    counts.merge(sbn.getPackageName(), c, Integer::sum);
                }
            }
        } catch (RuntimeException ignored) {
        }
        MAIN.post(() -> Badges.set(counts, true));
    }
}
