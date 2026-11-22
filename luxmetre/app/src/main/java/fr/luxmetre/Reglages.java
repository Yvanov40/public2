package fr.luxmetre;

import android.content.Context;
import android.content.SharedPreferences;

/** Mémorise le niveau du filtre d'écran entre deux ouvertures de l'appli. */
final class Reglages {
    private Reglages() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("reglages", Context.MODE_PRIVATE);
    }

    static int niveau(Context ctx) {
        return prefs(ctx).getInt("niveau", 40);
    }

    static void setNiveau(Context ctx, int niveau) {
        prefs(ctx).edit().putInt("niveau", niveau).apply();
    }

    static boolean actif(Context ctx) {
        return prefs(ctx).getBoolean("actif", false);
    }

    static void setActif(Context ctx, boolean actif) {
        prefs(ctx).edit().putBoolean("actif", actif).apply();
    }
}
