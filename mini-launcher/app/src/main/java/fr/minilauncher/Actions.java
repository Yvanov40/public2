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
            TORCH = 5, CAMERA = 6, SEARCH = 7, SETTINGS = 8, RECENTS = 9, APP_SEARCH = 10, PREVIOUS = 11,
            PLAY_PAUSE = 12, NEXT_TRACK = 13, PREV_TRACK = 14, VOLUME = 15, RINGER = 16, DND = 17,
            ROTATION = 18, AUTO_BRIGHTNESS = 19, WIFI_PANEL = 20, NFC_PANEL = 21, BLUETOOTH = 22,
            AIRPLANE = 23, BATTERY_SAVER = 24;

    static final String[] NAMES = {
            "Rien", "Verrouiller l'écran", "Notifications", "Centre de contrôle", "Ouvrir une appli…",
            "Lampe torche", "Appareil photo", "Recherche web", "Réglages du launcher", "Applis récentes",
            "Recherche d'applis (clavier)", "Appli d'avant (bascule)",
            "Musique : lecture / pause", "Musique : titre suivant", "Musique : titre précédent",
            "Volume (afficher le réglage)", "Son → vibreur → silencieux", "Ne pas déranger (activer / couper)",
            "Rotation auto (activer / couper)", "Luminosité auto (activer / couper)", "Wi-Fi / Internet (panneau)",
            "NFC (panneau)", "Bluetooth (réglages)", "Mode avion (réglages)", "Économie de batterie (réglages)"};

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
            case PLAY_PAUSE:
                mediaKey(a, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            case NEXT_TRACK:
                mediaKey(a, android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case PREV_TRACK:
                mediaKey(a, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case VOLUME: {
                android.media.AudioManager am = a.getSystemService(android.media.AudioManager.class);
                if (am != null) am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.ADJUST_SAME, android.media.AudioManager.FLAG_SHOW_UI);
                break;
            }
            case RINGER:
                cycleRinger(a);
                break;
            case DND:
                toggleDnd(a);
                break;
            case ROTATION:
                toggleSystem(a, android.provider.Settings.System.ACCELEROMETER_ROTATION, "Rotation auto");
                break;
            case AUTO_BRIGHTNESS:
                toggleSystem(a, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, "Luminosité auto");
                break;
            case WIFI_PANEL:
                if (!start(a, new Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)))
                    start(a, new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                break;
            case NFC_PANEL:
                if (!start(a, new Intent(android.provider.Settings.Panel.ACTION_NFC)))
                    start(a, new Intent(android.provider.Settings.ACTION_NFC_SETTINGS));
                break;
            case BLUETOOTH:
                start(a, new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
                break;
            case AIRPLANE:
                start(a, new Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS));
                break;
            case BATTERY_SAVER:
                start(a, new Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS));
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

    /** Touche média envoyée à l'appli qui joue (ou a joué en dernier). */
    private static void mediaKey(Context c, int code) {
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        if (am == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, code, 0));
    }

    /** Le droit « Ne pas déranger » est nécessaire pour passer en silencieux et pour Ne pas déranger. */
    private static boolean dndAccess(Activity a) {
        android.app.NotificationManager nm = a.getSystemService(android.app.NotificationManager.class);
        if (nm != null && nm.isNotificationPolicyAccessGranted()) return true;
        toast(a, "Autorise Mini Launcher dans « Accès Ne pas déranger », puis refais le geste");
        start(a, new Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        return false;
    }

    private static void cycleRinger(Activity a) {
        android.media.AudioManager am = a.getSystemService(android.media.AudioManager.class);
        if (am == null) return;
        int mode = am.getRingerMode();
        int next = mode == android.media.AudioManager.RINGER_MODE_NORMAL ? android.media.AudioManager.RINGER_MODE_VIBRATE
                : mode == android.media.AudioManager.RINGER_MODE_VIBRATE ? android.media.AudioManager.RINGER_MODE_SILENT
                : android.media.AudioManager.RINGER_MODE_NORMAL;
        // Quitter ou entrer en silencieux demande l'accès Ne pas déranger.
        if ((next == android.media.AudioManager.RINGER_MODE_SILENT || mode == android.media.AudioManager.RINGER_MODE_SILENT)
                && !dndAccess(a)) return;
        try {
            am.setRingerMode(next);
            toast(a, next == android.media.AudioManager.RINGER_MODE_NORMAL ? "Sonnerie"
                    : next == android.media.AudioManager.RINGER_MODE_VIBRATE ? "Vibreur" : "Silencieux");
        } catch (SecurityException e) {
            dndAccess(a);
        }
    }

    private static void toggleDnd(Activity a) {
        if (!dndAccess(a)) return;
        android.app.NotificationManager nm = a.getSystemService(android.app.NotificationManager.class);
        boolean on = nm.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                : android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        toast(a, on ? "Ne pas déranger coupé" : "Ne pas déranger activé");
    }

    /** Réglage système 0/1 (rotation auto, luminosité auto) : demande « Modifier les paramètres système ». */
    private static void toggleSystem(Activity a, String name, String label) {
        if (!android.provider.Settings.System.canWrite(a)) {
            toast(a, "Autorise « Modifier les paramètres système » pour Mini Launcher, puis refais le geste");
            start(a, new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + a.getPackageName())));
            return;
        }
        android.content.ContentResolver cr = a.getContentResolver();
        boolean on = android.provider.Settings.System.getInt(cr, name, 0) == 1;
        android.provider.Settings.System.putInt(cr, name, on ? 0 : 1);
        toast(a, label + (on ? " coupée" : " activée"));
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
