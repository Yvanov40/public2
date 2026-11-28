package fr.liseuse;

import android.app.Activity;

/**
 * Version Pro.
 * - Édition Play Store : achat intégré Google Play (classe AchatsPlay, dans src-play, compilée par Gradle).
 * - Édition complète (APK construit avec construire.sh) : tout est débloqué.
 */
public abstract class Achats {

    /**
     * Android 9+ : la fenêtre peut dessiner derrière la caméra logée dans l'écran (encoche, poinçon).
     * Sans cela, en plein écran, Android laisse une bande noire en haut.
     */
    public static void bordsEcran(android.view.Window w) {
        if (android.os.Build.VERSION.SDK_INT < 28) return;
        try {
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            android.view.WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode").setInt(lp, 1); // SHORT_EDGES
            w.setAttributes(lp);
        } catch (Throwable ignore) {
        }
    }

    /** Hauteur de la zone de la caméra en haut de l'écran (pixels), 0 s'il n'y en a pas. */
    public static int encocheHaut(android.view.View v) {
        if (android.os.Build.VERSION.SDK_INT < 28 || v == null) return 0;
        try {
            android.view.WindowInsets ins = v.getRootWindowInsets();
            if (ins == null) return 0;
            Object c = android.view.WindowInsets.class.getMethod("getDisplayCutout").invoke(ins);
            if (c == null) return 0;
            return (Integer) c.getClass().getMethod("getSafeInsetTop").invoke(c);
        } catch (Throwable t) {
            return 0;
        }
    }


    /**
     * Les boutons de volume servent-ils au son ? Oui si une appli joue du son
     * (musique, podcast, vidéo…) ou si un appel / une sonnerie est en cours.
     */
    public static boolean volumeOccupe(android.content.Context c) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) c.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am == null) return false;
            if (am.isMusicActive()) return true;
            int m = am.getMode();
            return m == android.media.AudioManager.MODE_IN_CALL || m == android.media.AudioManager.MODE_IN_COMMUNICATION
                    || m == android.media.AudioManager.MODE_RINGTONE;
        } catch (Throwable t) {
            return false;
        }
    }


    /** Identifiant du produit dans la Play Console (achat unique, « produit intégré »). */
    public static final String PRODUIT_PRO = "liseuse_pro";

    public interface Ecouteur {
        /** pro : débloqué ; prix : prix affiché (peut être vide) ; message : information à montrer (ou null). */
        void changement(boolean pro, String prix, String message);
    }

    public static Achats creer(Activity a, Ecouteur e) {
        try {
            Class<?> c = Class.forName("fr.liseuse.AchatsPlay");
            return (Achats) c.getConstructor(Activity.class, Ecouteur.class).newInstance(a, e);
        } catch (Throwable t) {
            return new Complet();
        }
    }

    public abstract boolean estPro();

    public abstract String prix();

    /** "play" (gratuit + Pro) ou "complete" (tout débloqué). */
    public abstract String edition();

    public abstract void acheter();

    public abstract void restaurer();

    public abstract void fermer();

    /** Édition complète : pas d'achat, tout est débloqué. */
    static final class Complet extends Achats {
        @Override
        public boolean estPro() {
            return true;
        }

        @Override
        public String prix() {
            return "";
        }

        @Override
        public String edition() {
            return "complete";
        }

        @Override
        public void acheter() {
        }

        @Override
        public void restaurer() {
        }

        @Override
        public void fermer() {
        }
    }
}
