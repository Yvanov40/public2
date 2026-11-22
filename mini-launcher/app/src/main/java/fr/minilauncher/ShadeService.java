package fr.minilauncher;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Service d'accessibilité facultatif : il ne lit rien à l'écran, il sert seulement à ouvrir
 * les notifications et le centre de contrôle par la méthode officielle d'Android, plus
 * fiable sur HyperOS que l'appel direct.
 */
public final class ShadeService extends AccessibilityService {

    private static ShadeService instance;

    static boolean isRunning() {
        return instance != null;
    }

    static boolean open(boolean controlCenter) {
        ShadeService s = instance;
        if (s == null) return false;
        return s.performGlobalAction(controlCenter
                ? GLOBAL_ACTION_QUICK_SETTINGS : GLOBAL_ACTION_NOTIFICATIONS);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
