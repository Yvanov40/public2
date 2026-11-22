package fr.minilauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * Ouvre le volet des notifications ou le centre de contrôle (réglages rapides), par l'appel
 * direct à Android. Sur HyperOS, le centre de contrôle s'ouvre parfois à moitié par ce biais :
 * plusieurs façons d'enchaîner les appels sont proposées dans les réglages.
 */
final class Shade {

    static final String[] METHODS = {
            "Appel direct",
            "Appel direct relancé",
            "Fermer puis ouvrir",
            "Notifications puis centre de contrôle",
            "Désactivé (glisser depuis la barre d'état)"};
    static final int DISABLED = 4;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Shade() {
    }

    /** Renvoie false si le téléphone a refusé l'appel (ou si la méthode est désactivée). */
    static boolean open(Context context, boolean controlCenter) {
        if (!controlCenter) return call(context, "expandNotificationsPanel");
        Context app = context.getApplicationContext();
        switch (Store.get(context).ccMethod()) {
            case 1:
                if (!call(app, "expandSettingsPanel")) return false;
                MAIN.postDelayed(() -> call(app, "expandSettingsPanel"), 300);
                return true;
            case 2:
                call(app, "collapsePanels");
                MAIN.postDelayed(() -> call(app, "expandSettingsPanel"), 180);
                return true;
            case 3:
                if (!call(app, "expandNotificationsPanel")) return false;
                MAIN.postDelayed(() -> call(app, "expandSettingsPanel"), 350);
                return true;
            case DISABLED:
                return true; // rien : HyperOS gère le geste depuis la barre d'état
            default:
                return call(app, "expandSettingsPanel");
        }
    }

    @SuppressLint("WrongConstant")
    private static boolean call(Context context, String method) {
        try {
            Object service = context.getSystemService("statusbar");
            Method m = Class.forName("android.app.StatusBarManager").getMethod(method);
            m.invoke(service);
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }
}
