package fr.liseuse;

import android.app.Activity;

/**
 * Version Pro.
 * - Édition Play Store : achat intégré Google Play (classe AchatsPlay, dans src-play, compilée par Gradle).
 * - Édition complète (APK construit avec construire.sh) : tout est débloqué.
 */
public abstract class Achats {

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
