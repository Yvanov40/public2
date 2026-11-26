package fr.minilauncher;

import android.app.Activity;
import android.app.SearchManager;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

/** Les actions que l'on peut associer aux gestes de l'accueil. */
final class Actions {

    static final int NONE = 0, LOCK = 1, NOTIFICATIONS = 2, CONTROL_CENTER = 3, OPEN_APP = 4,
            TORCH = 5, CAMERA = 6, SEARCH = 7, SETTINGS = 8, RECENTS = 9, APP_SEARCH = 10, PREVIOUS = 11;

    static final String[] NAMES = {
            "Rien", "Verrouiller l'écran", "Notifications", "Centre de contrôle", "Ouvrir une appli…",
            "Lampe torche", "Appareil photo", "Recherche web", "Réglages du launcher", "Applis récentes",
            "Recherche d'applis (clavier)", "Appli d'avant (bascule)"};

    /** Gestes réglables : clé d'enregistrement, nom affiché, action par défaut. */
    static final String[] GESTURES = {"gDouble", "gUp", "gDownLeft", "gDownRight", "gLeft", "gRight"};
    static final String[] GESTURE_NAMES = {
            "Double tap dans le vide", "Glisser vers le haut", "Glisser vers le bas (moitié gauche)",
            "Glisser vers le bas (moitié droite)", "Glisser vers la gauche", "Glisser vers la droite"};
    static final int[] DEFAULTS = {LOCK, RECENTS, NOTIFICATIONS, CONTROL_CENTER, APP_SEARCH, PREVIOUS};

    private static boolean torchOn;
    private static boolean torchListening;

    private Actions() {
    }

    static int actionFor(Context c, String gesture) {
        for (int i = 0; i < GESTURES.length; i++) {
            if (GESTURES[i].equals(gesture)) {
                int v = Store.get(c).num(gesture, DEFAULTS[i]);
                return v >= 0 && v < NAMES.length ? v : NONE; // action retirée depuis
            }
        }
        return NONE;
    }

    /** Lance l'action associée au geste. */
    static void runGesture(Activity a, String gesture) {
        int action = actionFor(a, gesture);
        String app = Store.get(a).text(gesture + "App");
        run(a, action, app);
    }

    static void run(Activity a, int action, String appKey) {
        switch (action) {
            case LOCK:
                lock(a);
                break;
            case NOTIFICATIONS:
                if (!Shade.open(a, false)) toast(a, "Le téléphone ne permet pas d'ouvrir ce volet");
                break;
            case CONTROL_CENTER:
                if (!Shade.open(a, true)) toast(a, "Le téléphone ne permet pas d'ouvrir ce volet");
                break;
            case OPEN_APP:
                openApp(a, appKey);
                break;
            case TORCH:
                toggleTorch(a);
                break;
            case CAMERA:
                start(a, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                break;
            case SEARCH:
                Intent search = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "");
                if (!start(a, search)) start(a, new Intent(Intent.ACTION_ASSIST));
                break;
            case SETTINGS:
                start(a, new Intent(a, SettingsActivity.class));
                break;
            case RECENTS:
                Recents.show(a, AppRepository.get(a).cached(), () -> {
                });
                break;
            case APP_SEARCH:
                AppSearch.show(a, AppRepository.get(a).cached());
                break;
            case PREVIOUS:
                previous(a);
                break;
            default:
                break;
        }
    }

    /**
     * Bascule : rouvre l'avant-dernière appli utilisée (la dernière est celle qu'on vient de
     * quitter), comme Alt+Tab. Deux glissés suffisent pour aller et venir entre deux applis.
     */
    private static void previous(Activity a) {
        java.util.List<Object[]> recent = Recents.load(a, AppRepository.get(a).cached());
        if (recent.size() < 2) {
            if (recent.size() == 1) Launcher.launch(a, (AppRepository.AppEntry) recent.get(0)[0], null, null);
            else toast(a, "Pas encore d'appli d'avant");
            return;
        }
        Launcher.launch(a, (AppRepository.AppEntry) recent.get(1)[0], null, null);
    }

    // ------------------------------------------------------------ verrouillage

    static ComponentName admin(Context c) {
        return new ComponentName(c, LockAdmin.class);
    }

    static boolean lockAllowed(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(admin(c));
    }

    static void lock(Activity a) {
        DevicePolicyManager dpm = a.getSystemService(DevicePolicyManager.class);
        if (dpm != null && dpm.isAdminActive(admin(a))) {
            dpm.lockNow();
            return;
        }
        // Première fois : Android demande l'autorisation de verrouiller l'écran.
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(a))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Permet de verrouiller l'écran par un geste sur l'accueil. "
                                + "Mini Launcher n'utilise que ce droit.");
        start(a, i);
    }

    /** À faire avant de désinstaller l'appli. */
    static void removeLock(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        if (dpm != null && dpm.isAdminActive(admin(c))) dpm.removeActiveAdmin(admin(c));
    }

    // ------------------------------------------------------------ autres actions

    private static void openApp(Activity a, String key) {
        ComponentName cn = key == null ? null : ComponentName.unflattenFromString(key);
        if (cn == null) {
            toast(a, "Choisis l'appli dans les réglages des gestes");
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (start(a, i)) Store.get(a).recordLaunch(key);
    }

    private static void toggleTorch(Context c) {
        CameraManager cm = c.getSystemService(CameraManager.class);
        if (cm == null) return;
        try {
            if (!torchListening) {
                torchListening = true;
                cm.registerTorchCallback(new CameraManager.TorchCallback() {
                    @Override
                    public void onTorchModeChanged(String id, boolean enabled) {
                        torchOn = enabled;
                    }
                }, new Handler(Looper.getMainLooper()));
            }
            for (String id : cm.getCameraIdList()) {
                Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(flash)) {
                    cm.setTorchMode(id, !torchOn);
                    torchOn = !torchOn;
                    return;
                }
            }
            toast(c, "Pas de lampe sur ce téléphone");
        } catch (CameraAccessException | RuntimeException e) {
            toast(c, "Lampe indisponible pour le moment");
        }
    }

    private static boolean start(Context c, Intent i) {
        try {
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
    }
}
