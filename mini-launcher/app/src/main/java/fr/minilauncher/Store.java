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

/** Tout ce que l'appli mémorise : favoris épinglés, groupes, usage, réglages, position. */
final class Store {

    static final int[] GROUP_COLORS = {
            0xFFFFB86B, 0xFF7EE0A1, 0xFFFF8FA3, 0xFFC9A7FF,
            0xFF6FD3FF, 0xFFFFE07A, 0xFFB3E36B, 0xFFFF9E7A};

    static final class Group {
        String name;
        int color;
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

    boolean centerLast() {
        return prefs.getBoolean("centerLast", true);
    }

    boolean directSingle() {
        return prefs.getBoolean("directSingle", true);
    }

    boolean bubble() {
        return prefs.getBoolean("bubble", false);
    }

    void setFlag(String name, boolean value) {
        prefs.edit().putBoolean(name, value).apply();
    }

    boolean flag(String name, boolean def) {
        return prefs.getBoolean(name, def);
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
            DWELL = "dwell", HAPTIC = "haptic", WHEEL = "wheelScale", MAXFAV = "maxFav";

    boolean clock() { return flag(CLOCK, true); }
    int clockSize() { return num(CLOCK_SIZE, 60); }          // sp
    int clockAlign() { return num(CLOCK_ALIGN, 0); }         // 0 gauche, 1 centre, 2 droite
    boolean clockDate() { return flag(CLOCK_DATE, true); }
    int cols() { return num(COLS, 4); }
    int rowsSetting() { return num(ROWS, 0); }               // 0 = automatique
    int iconSize() { return num(ICON, 52); }                 // dp
    boolean labels() { return flag(LABELS, true); }
    int band() { return num(BAND, 25); }                     // % de la largeur
    int accent() { return num(ACCENT, 0xFF8FA6FF); }
    int dimPercent() { return num(DIM, 65); }
    int speed() { return num(SPEED, 1); }                    // 0 aucune, 1 normale, 2 rapide, 3 lente
    int dwell() { return num(DWELL, 380); }                  // ms
    boolean haptic() { return flag(HAPTIC, true); }
    int wheelScale() { return num(WHEEL, 100); }             // %
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

    // ------------------------------------------------------------ position de la roue

    /** {x, y} en fraction de l'écran, ou null si jamais réglée. */
    float[] position() {
        if (!prefs.contains("x")) return null;
        return new float[]{prefs.getFloat("x", 0.5f), prefs.getFloat("y", 0.62f)};
    }

    void setPosition(float x, float y) {
        prefs.edit().putFloat("x", x).putFloat("y", y).apply();
    }

    void clearPosition() {
        prefs.edit().remove("x").remove("y").apply();
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
        for (HomeItem it : homeItems()) if (!it.widget && key.equals(it.key)) return true;
        return false;
    }

    /** Ajoute une appli à l'accueil (placée à la première case libre) ou l'en retire. */
    void toggleHomeApp(String key) {
        List<HomeItem> items = homeItems();
        for (HomeItem it : items) {
            if (!it.widget && key.equals(it.key)) {
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

    static Group find(List<Group> groups, String name) {
        for (Group g : groups) if (g.name.equals(name)) return g;
        return null;
    }
}
