package fr.minilauncher;

import android.annotation.SuppressLint;
import android.content.Context;

import java.lang.reflect.Method;

/** Ouvre le volet des notifications ou le centre de contrôle (réglages rapides). */
final class Shade {

    private Shade() {
    }

    /** Renvoie false si le téléphone a refusé. */
    @SuppressLint("WrongConstant")
    static boolean open(Context context, boolean controlCenter) {
        try {
            Object service = context.getSystemService("statusbar");
            Method m = Class.forName("android.app.StatusBarManager")
                    .getMethod(controlCenter ? "expandSettingsPanel" : "expandNotificationsPanel");
            m.invoke(service);
            return true;
        } catch (Exception | LinkageError e) {
            return false;
        }
    }
}
