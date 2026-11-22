package fr.minilauncher.badges;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Le launcher vient de s'ouvrir : on lui renvoie les nombres actuels. */
public final class RequestReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BadgeListener l = BadgeListener.instance;
        if (l != null) l.send();
    }
}
