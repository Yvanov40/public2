package fr.luxmetre;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tuile « Assombrir » du centre de contrôle : allume ou éteint le filtre d'écran. */
@TargetApi(24)
public class FiltreTuile extends TileService {

    @Override
    public void onStartListening() {
        maj();
    }

    @Override
    public void onClick() {
        if (FiltreService.actif) {
            FiltreService.arreter(this);
        } else if (Settings.canDrawOverlays(this)) {
            FiltreService.regler(this, Reglages.niveau(this));
        } else {
            ouvrirAppli();
            return;
        }
        maj();
    }

    /** Sans l'autorisation de superposition, on ouvre l'appli pour la donner. */
    private void ouvrirAppli() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void maj() {
        Tile tuile = getQsTile();
        if (tuile == null) return;
        boolean actif = FiltreService.actif;
        tuile.setState(actif ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tuile.setLabel("Assombrir");
        if (Build.VERSION.SDK_INT >= 29) {
            tuile.setSubtitle(actif ? Reglages.niveau(this) + " %" : "Désactivé");
        }
        tuile.updateTile();
    }
}
