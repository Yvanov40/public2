package fr.minilauncher.badges;

import android.app.Notification;
import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;

/**
 * Compte les notifications de chaque appli et envoie seulement ces nombres au launcher.
 * Le contenu des notifications n'est ni lu ni gardé.
 */
public final class BadgeListener extends NotificationListenerService {

    static volatile BadgeListener instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        send();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        send();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        send();
    }

    void send() {
        ArrayList<String> packages = new ArrayList<>();
        ArrayList<Integer> counts = new ArrayList<>();
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) {
                for (StatusBarNotification sbn : all) {
                    Notification n = sbn.getNotification();
                    // Ni les notifications permanentes (musique, service…), ni les résumés de groupe.
                    if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                    int c = n.number > 0 ? n.number : 1;
                    int i = packages.indexOf(sbn.getPackageName());
                    if (i < 0) {
                        packages.add(sbn.getPackageName());
                        counts.add(c);
                    } else {
                        counts.set(i, counts.get(i) + c);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        Intent intent = new Intent("fr.minilauncher.BADGES")
                .setPackage("fr.minilauncher")
                .putStringArrayListExtra("packages", packages)
                .putIntegerArrayListExtra("counts", counts);
        sendBroadcast(intent);
    }
}
