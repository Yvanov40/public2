package fr.minilauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Relance la bulle après un redémarrage ou une mise à jour de l'appli. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BubbleService.sync(context);
    }
}
