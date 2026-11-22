package fr.luxmetre;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Service d'accessibilité : seul moyen, sur Android récent, de poser le voile aussi
 * sur la barre d'état et la barre de navigation. Il ne lit pas le contenu de l'écran.
 */
public class FiltreAccessibilite extends AccessibilityService {

    static volatile FiltreAccessibilite instance;

    private Voile voile;

    @Override
    protected void onServiceConnected() {
        instance = this;
        voile = new Voile(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        // Le voile complet prend le relais du voile ordinaire s'il était allumé.
        if (Reglages.actif(this)) Filtre.appliquer(this);
    }

    void afficher(int niveau) {
        voile.afficher(niveau);
    }

    void cacher() {
        if (voile != null) voile.cacher();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cacher();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cacher();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }
}
