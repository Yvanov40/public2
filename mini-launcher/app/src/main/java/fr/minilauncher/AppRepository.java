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

    private AppRepository(Context context) {
        this.context = context;
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
                synchronized (iconCache) {
                    icon = iconCache.get(cn.flattenToShortString());
                }
                list.add(new AppEntry(label, cn, ri, icon));
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
                if (e.icon != null) continue;
                Drawable d;
                try {
                    d = e.info.loadIcon(pm);
                } catch (RuntimeException ex) {
                    continue;
                }
                e.icon = d;
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
