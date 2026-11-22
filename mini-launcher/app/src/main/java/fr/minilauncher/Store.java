package fr.minilauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tout ce que l'appli mémorise : favoris épinglés, dossiers, usage, accueil, réglages. */
final class Store {

    static final int[] GROUP_COLORS = {
            0xFFFFB86B, 0xFF7EE0A1, 0xFFFF8FA3, 0xFFC9A7FF,
            0xFF6FD3FF, 0xFFFFE07A, 0xFFB3E36B, 0xFFFF9E7A};

    static final class Group {
        String name;
        int color;
        int style;  // icône : 0 grille de 4, 1 première appli, 2 initiale
        int shape;  // 0 carré arrondi, 1 rond
        final List<String> apps = new ArrayList<>();

        Group(String name, int color) {
            this.name = name;
            this.color = color;
        }
    }

    private static Store instance;

    static synchronized Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;

    private Store(Context context) {
        prefs = context.getSharedPreferences("roue", Context.MODE_PRIVATE);
    }

    static String key(ComponentName cn) {
        return cn.flattenToString();
    }

    // ------------------------------------------------------------ réglages

    boolean autoFavorites() {
        return prefs.getBoolean("autoFav", true);
    }




    void setFlag(String name, boolean value) {
        prefs.edit().putBoolean(name, value).apply();
    }

    boolean flag(String name, boolean def) {
        return prefs.getBoolean(name, def);
    }

    /** « 3.9 (build 20) » : la version installée, à vérifier après une mise à jour. */
    static String version(android.content.Context c) {
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
            return p.versionName + " (build " + code + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    String text(String name) {
        return prefs.getString(name, null);
    }

    void setText(String name, String value) {
        prefs.edit().putString(name, value).apply();
    }

    int num(String name, int def) {
        return prefs.getInt(name, def);
    }

    void setNum(String name, int value) {
        prefs.edit().putInt(name, value).apply();
    }

    // Réglages de personnalisation (valeurs par défaut au même endroit).
    static final String CLOCK = "clock", CLOCK_SIZE = "clockSize", CLOCK_ALIGN = "clockAlign",
            CLOCK_DATE = "clockDate", COLS = "cols", ROWS = "rows", ICON = "iconSize",
            LABELS = "labels", BAND = "band", ACCENT = "accent", DIM = "dim", SPEED = "speed",
            HAPTIC = "haptic", MAXFAV = "maxFav",
            SIDE = "alphaSide", ALPHA_VISIBLE = "alphaVisible",
            STRIP_H = "stripHeight", STRIP_POS = "stripPos", ZOOM = "zoom",
            STRIP_BG = "stripBg", STRIP_ALPHA = "stripAlpha", STRIP_FG = "stripFg";

    static final String F_BG = "folderBg", F_BG_ALPHA = "folderBgAlpha", F_ICON = "folderIcon",
            F_RADIUS = "folderRadius", F_LABELS = "folderLabels", F_BORDER = "folderBorder",
            F_TILE_ALPHA = "folderTile";

    int folderBg() { return num(F_BG, 0x1B1D26); }           // fond du cercle (RVB)
    int folderBgAlpha() { return num(F_BG_ALPHA, 92); }      // %
    int folderIcon() { return num(F_ICON, 46); }             // dp
    int folderRadius() { return num(F_RADIUS, 100); }        // %
    boolean folderLabels() { return flag(F_LABELS, false); }
    boolean folderBorder() { return flag(F_BORDER, true); }
    int folderTile() { return num(F_TILE_ALPHA, 80); }       // opacité du fond de l'icône, %

    static final String CC_METHOD = "ccMethod";
    static final String BAR_ICONS = "barIcons", HOME_TEXT = "homeText", TOP_SCRIM = "topScrim";

    int barIcons() { return num(BAR_ICONS, 0); }             // 0 auto, 1 claires, 2 foncées
    int homeText() { return num(HOME_TEXT, 0); }             // 0 auto, 1 blanc, 2 noir
    boolean topScrim() { return flag(TOP_SCRIM, false); }    // voile sombre en haut

    int ccMethod() { return num(CC_METHOD, 0); }             // voir Shade.METHODS

    int stripHeight() { return num(STRIP_H, 55); }           // % de la hauteur utile
    int stripPos() { return num(STRIP_POS, 55); }            // centre, % de la hauteur utile
    int zoom() { return num(ZOOM, 2); }                      // 0 aucun, 1 léger, 2 normal, 3 fort
    int stripBg() { return num(STRIP_BG, 0x1B1D26); }        // couleur RVB de la zone
    int stripAlpha() { return num(STRIP_ALPHA, 88); }        // opacité de la zone, %
    int stripFg() { return num(STRIP_FG, 0xFFFFFFFF); }      // couleur des lettres

    int alphaSide() { return num(SIDE, 0); }                 // 0 les deux, 1 gauche, 2 droite
    boolean alphaVisible() { return flag(ALPHA_VISIBLE, true); }

    boolean clock() { return flag(CLOCK, true); }
    int clockSize() { return num(CLOCK_SIZE, 60); }          // sp
    int clockAlign() { return num(CLOCK_ALIGN, 0); }         // 0 gauche, 1 centre, 2 droite
    boolean clockDate() { return flag(CLOCK_DATE, true); }
    int cols() { return num(COLS, 4); }
    int rowsSetting() { return num(ROWS, 0); }               // 0 = automatique
    int iconSize() { return num(ICON, 52); }                 // dp
    boolean labels() { return flag(LABELS, true); }
    int band() { return num(BAND, 18); }                     // % de la largeur
    int accent() { return num(ACCENT, 0xFF8FA6FF); }
    int dimPercent() { return num(DIM, 65); }
    int speed() { return num(SPEED, 1); }                    // 0 aucune, 1 normale, 2 rapide, 3 lente
    boolean haptic() { return flag(HAPTIC, true); }
    int maxFav() { return num(MAXFAV, 8); }

    /** Multiplicateur de durée des animations. */
    float speedFactor() {
        switch (speed()) {
            case 0: return 0f;
            case 2: return 0.6f;
            case 3: return 1.6f;
            default: return 1f;
        }
    }

    // ------------------------------------------------------------ favoris

    List<String> pinned() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("pinned", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    boolean isPinned(String key) {
        return pinned().contains(key);
    }

    void togglePin(String key) {
        List<String> list = pinned();
        if (!list.remove(key)) list.add(key);
        prefs.edit().putString("pinned", new JSONArray(list).toString()).apply();
    }

    // ------------------------------------------------------------ usage

    /** Nombre de lancements par appli, du plus utilisé au moins utilisé. */
    List<Map.Entry<String, Integer>> usage() {
        Map<String, Integer> map = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString("counts", "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                map.put(k, o.getInt(k));
            }
        } catch (JSONException ignored) {
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return list;
    }

    void recordLaunch(String key) {
        try {
            JSONObject o = new JSONObject(prefs.getString("counts", "{}"));
            o.put(key, o.optInt(key, 0) + 1);
            prefs.edit().putString("counts", o.toString()).putString("last", key).apply();
        } catch (JSONException ignored) {
        }
        // Historique des lancements, du plus récent au plus ancien (pour les applis récentes).
        try {
            JSONArray old = new JSONArray(prefs.getString("recent", "[]"));
            JSONArray now = new JSONArray();
            JSONObject first = new JSONObject();
            first.put("k", key);
            first.put("t", System.currentTimeMillis());
            now.put(first);
            for (int i = 0; i < old.length() && now.length() < 20; i++) {
                JSONObject e = old.getJSONObject(i);
                if (!key.equals(e.optString("k"))) now.put(e);
            }
            prefs.edit().putString("recent", now.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** Applis lancées depuis le launcher : {clé, heure}, de la plus récente à la plus ancienne. */
    List<Object[]> recentLaunches() {
        List<Object[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.getJSONObject(i);
                out.add(new Object[]{e.getString("k"), e.getLong("t")});
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    String last() {
        return prefs.getString("last", null);
    }

    // ------------------------------------------------------------ groupes

    List<Group> groups() {
        List<Group> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("groups", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Group g = new Group(o.getString("name"), o.getInt("color"));
                g.style = o.optInt("style", 0);
                g.shape = o.optInt("shape", 0);
                JSONArray apps = o.getJSONArray("apps");
                for (int j = 0; j < apps.length(); j++) g.apps.add(apps.getString(j));
                out.add(g);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    void setGroups(List<Group> groups) {
        JSONArray a = new JSONArray();
        try {
            for (Group g : groups) {
                JSONObject o = new JSONObject();
                o.put("name", g.name);
                o.put("color", g.color);
                o.put("style", g.style);
                o.put("shape", g.shape);
                o.put("apps", new JSONArray(g.apps));
                a.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("groups", a.toString()).apply();
    }

    // ------------------------------------------------------------ écran d'accueil

    /** Une appli ou un widget posé sur l'écran d'accueil. col < 0 : à placer. */
    static final class HomeItem {
        boolean widget;
        String folder;   // dossier : nom du groupe
        String key;      // appli : composant
        int widgetId;    // widget : identifiant Android
        int col = -1, row = -1, w = 1, h = 1;
    }

    List<HomeItem> homeItems() {
        List<HomeItem> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("home", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                HomeItem it = new HomeItem();
                it.widget = o.optBoolean("widget");
                it.folder = o.has("folder") ? o.getString("folder") : null;
                it.key = o.optString("key", null);
                it.widgetId = o.optInt("id", -1);
                it.col = o.optInt("col", -1);
                it.row = o.optInt("row", -1);
                it.w = o.optInt("w", 1);
                it.h = o.optInt("h", 1);
                out.add(it);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    void setHomeItems(List<HomeItem> items) {
        JSONArray a = new JSONArray();
        try {
            for (HomeItem it : items) {
                JSONObject o = new JSONObject();
                o.put("widget", it.widget);
                if (it.folder != null) o.put("folder", it.folder);
                if (it.key != null) o.put("key", it.key);
                o.put("id", it.widgetId);
                o.put("col", it.col);
                o.put("row", it.row);
                o.put("w", it.w);
                o.put("h", it.h);
                a.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("home", a.toString()).apply();
    }

    boolean isOnHome(String key) {
        for (HomeItem it : homeItems()) if (!it.widget && it.folder == null && key.equals(it.key)) return true;
        return false;
    }

    /** Ajoute une appli à l'accueil (placée à la première case libre) ou l'en retire. */
    void toggleHomeApp(String key) {
        List<HomeItem> items = homeItems();
        for (HomeItem it : items) {
            if (!it.widget && it.folder == null && key.equals(it.key)) {
                items.remove(it);
                setHomeItems(items);
                return;
            }
        }
        HomeItem it = new HomeItem();
        it.key = key;
        items.add(it);
        setHomeItems(items);
    }

    // ------------------------------------------------------------ dossiers (= groupes)

    /** Nom libre : « Dossier », « Dossier 2 », … */
    String freeFolderName(String base) {
        List<Group> gs = groups();
        if (find(gs, base) == null) return base;
        for (int i = 2; ; i++) if (find(gs, base + " " + i) == null) return base + " " + i;
    }

    /** Crée un dossier avec ces applis et le pose sur l'accueil (à la première place libre). */
    void createFolder(String name, List<String> apps, int col, int row) {
        List<Group> gs = groups();
        Group g = find(gs, name);
        if (g == null) {
            g = new Group(name, GROUP_COLORS[gs.size() % GROUP_COLORS.length]);
            gs.add(g);
        }
        for (String k : apps) if (!g.apps.contains(k)) g.apps.add(k);
        setGroups(gs);
        List<HomeItem> items = homeItems();
        for (HomeItem it : items) if (name.equals(it.folder)) return;
        HomeItem it = new HomeItem();
        it.folder = name;
        it.col = col;
        it.row = row;
        items.add(it);
        setHomeItems(items);
    }

    /** Personnalisation d'un dossier ; -1 = inchangé. */
    void customizeFolder(String name, int color, int style, int shape) {
        List<Group> gs = groups();
        Group g = find(gs, name);
        if (g == null) return;
        if (color != -1) g.color = color;
        if (style != -1) g.style = style;
        if (shape != -1) g.shape = shape;
        setGroups(gs);
    }

    void addToFolder(String name, String key) {
        List<Group> gs = groups();
        Group g = find(gs, name);
        if (g != null && !g.apps.contains(key)) {
            g.apps.add(key);
            setGroups(gs);
        }
    }

    void setFolderApps(String name, List<String> apps) {
        List<Group> gs = groups();
        Group g = find(gs, name);
        if (g == null) return;
        g.apps.clear();
        g.apps.addAll(apps);
        setGroups(gs);
    }

    void renameFolder(String from, String to) {
        List<Group> gs = groups();
        Group g = find(gs, from);
        if (g == null || find(gs, to) != null) return;
        g.name = to;
        setGroups(gs);
        List<HomeItem> items = homeItems();
        for (HomeItem it : items) if (from.equals(it.folder)) it.folder = to;
        setHomeItems(items);
    }

    void deleteFolder(String name) {
        List<Group> gs = groups();
        Group g = find(gs, name);
        if (g != null) gs.remove(g);
        setGroups(gs);
        List<HomeItem> items = homeItems();
        for (int i = items.size() - 1; i >= 0; i--) if (name.equals(items.get(i).folder)) items.remove(i);
        setHomeItems(items);
    }

    boolean isFolderOnHome(String name) {
        for (HomeItem it : homeItems()) if (name.equals(it.folder)) return true;
        return false;
    }

    void toggleFolderOnHome(String name) {
        List<HomeItem> items = homeItems();
        for (HomeItem it : items) {
            if (name.equals(it.folder)) {
                items.remove(it);
                setHomeItems(items);
                return;
            }
        }
        HomeItem it = new HomeItem();
        it.folder = name;
        items.add(it);
        setHomeItems(items);
    }

    static Group find(List<Group> groups, String name) {
        for (Group g : groups) if (g.name.equals(name)) return g;
        return null;
    }
}
