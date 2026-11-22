package fr.luxmetre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Bouton « Désactiver » de la notification. */
public class ArretReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Filtre.arreter(ctx);
    }
}
