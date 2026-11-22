package fr.minilauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Liste des applis installées, gardée en mémoire entre deux ouvertures. */
final class AppRepository {

    interface Callback {
        void onAppsChanged(List<AppEntry> apps);
    }

    static final class AppEntry {
        final String label;
        final String letter;
        final ComponentName component;
        final ResolveInfo info;
        volatile Drawable icon;
        volatile boolean diskIcon; // icône provisoire lue sur le disque (à remplacer par la vraie)

        AppEntry(String label, ComponentName component, ResolveInfo info, Drawable icon) {
            this.label = label;
            this.letter = letterOf(label);
            this.component = component;
            this.info = info;
            this.icon = icon;
        }
    }

    private static AppRepository instance;

    static synchronized AppRepository get(Context context) {
        if (instance == null) instance = new AppRepository(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private volatile List<AppEntry> apps = Collections.emptyList();

    private final Map<String, Drawable> diskIcons = new HashMap<>();
    private final java.util.Set<String> savedThisRun = new java.util.HashSet<>();

    private AppRepository(Context context) {
        this.context = context;
        loadFromDisk();
    }

    // ------------------------------------------------------------ copie sur le disque
    // Quand HyperOS arrête le launcher (« tout fermer »), il redémarre à froid : la liste et
    // les icônes gardées sur le disque permettent de réafficher l'accueil tout de suite.

    private java.io.File iconDir() {
        java.io.File d = new java.io.File(context.getFilesDir(), "icons");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String fileOf(String key) {
        return Integer.toHexString(key.hashCode()) + ".png";
    }

    private void loadFromDisk() {
        String json = context.getSharedPreferences("apps_cache", Context.MODE_PRIVATE).getString("list", null);
        if (json == null) return;
        List<AppEntry> list = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            java.io.File dir = iconDir();
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                ComponentName cn = ComponentName.unflattenFromString(o.getString("c"));
                if (cn == null) continue;
                Drawable icon = null;
                java.io.File f = new java.io.File(dir, fileOf(cn.flattenToShortString()));
                if (f.exists()) {
                    android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getPath());
                    if (b != null) {
                        icon = new android.graphics.drawable.BitmapDrawable(context.getResources(), b);
                        diskIcons.put(cn.flattenToShortString(), icon);
                    }
                }
                AppEntry e = new AppEntry(o.getString("l"), cn, null, icon);
                e.diskIcon = icon != null;
                list.add(e);
            }
        } catch (org.json.JSONException | RuntimeException ignored) {
        }
        apps = Collections.unmodifiableList(list);
    }

    private void saveToDisk(List<AppEntry> list) {
        org.json.JSONArray a = new org.json.JSONArray();
        java.io.File dir = iconDir();
        int size = (int) (56 * context.getResources().getDisplayMetrics().density);
        for (AppEntry e : list) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("l", e.label);
                o.put("c", e.component.flattenToString());
                a.put(o);
            } catch (org.json.JSONException ignored) {
            }
            String key = e.component.flattenToShortString();
            Drawable d = e.icon;
            if (d == null || e.diskIcon || savedThisRun.contains(key)) continue;
            try {
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(size, size,
                        android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c = new android.graphics.Canvas(b);
                // Une copie : l'icône affichée à l'écran n'est pas touchée depuis ce fil.
                Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : null;
                if (copy == null) continue;
                copy.setBounds(0, 0, size, size);
                copy.draw(c);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, fileOf(key)))) {
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                savedThisRun.add(key);
            } catch (java.io.IOException | RuntimeException ignored) {
            }
        }
        context.getSharedPreferences("apps_cache", Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply();
    }

    List<AppEntry> cached() {
        return apps;
    }

    /** Relit la liste en arrière-plan : d'abord les noms (rapide), puis les icônes. */
    void refresh(Callback callback) {
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(query, 0);
            List<AppEntry> list = new ArrayList<>(infos.size());
            String self = context.getPackageName();
            for (ResolveInfo ri : infos) {
                if (self.equals(ri.activityInfo.packageName)) continue;
                ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                CharSequence raw = ri.loadLabel(pm);
                String label = raw == null ? cn.getPackageName() : raw.toString().trim();
                Drawable icon;
                boolean fromDisk = false;
                synchronized (iconCache) {
                    icon = iconCache.get(cn.flattenToShortString());
                    if (icon == null) {
                        icon = diskIcons.get(cn.flattenToShortString());
                        fromDisk = icon != null;
                    }
                }
                AppEntry entry = new AppEntry(label, cn, ri, icon);
                entry.diskIcon = fromDisk;
                list.add(entry);
            }
            Collator collator = Collator.getInstance(Locale.getDefault());
            collator.setStrength(Collator.PRIMARY);
            list.sort((a, b) -> collator.compare(a.label, b.label));
            List<AppEntry> result = Collections.unmodifiableList(list);
            apps = result;
            post(callback, result);

            long lastPost = SystemClock.uptimeMillis();
            boolean pending = false;
            for (AppEntry e : list) {
                if (e.icon != null && !e.diskIcon) continue;
                Drawable d;
                try {
                    d = e.info.loadIcon(pm);
                } catch (RuntimeException ex) {
                    continue;
                }
                e.icon = d;
                e.diskIcon = false;
                synchronized (iconCache) {
                    iconCache.put(e.component.flattenToShortString(), d);
                }
                pending = true;
                if (SystemClock.uptimeMillis() - lastPost > 120) {
                    post(callback, result);
                    lastPost = SystemClock.uptimeMillis();
                    pending = false;
                }
            }
            if (pending) post(callback, result);
            saveToDisk(list);
        });
    }

    private void post(Callback callback, List<AppEntry> list) {
        main.post(() -> callback.onAppsChanged(list));
    }

    /** Première lettre sans accent (É → E), « # » pour les chiffres et symboles. */
    static String letterOf(String label) {
        if (label.isEmpty()) return "#";
        String first = Normalizer.normalize(label.substring(0, 1), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        if (first.isEmpty()) return "#";
        char c = first.charAt(0);
        return (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
    }
}
