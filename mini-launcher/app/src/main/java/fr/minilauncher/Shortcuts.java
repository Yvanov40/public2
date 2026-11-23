package fr.minilauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Raccourcis d'applis (« Nouveau message », « Itinéraire »…). Android ne les donne qu'à
 * l'écran d'accueil par défaut : Mini Launcher doit l'être.
 */
final class Shortcuts {

    private Shortcuts() {
    }

    static List<ShortcutInfo> of(Context c, ComponentName cn) {
        List<ShortcutInfo> out = new ArrayList<>();
        LauncherApps la = c.getSystemService(LauncherApps.class);
        if (la == null) return out;
        try {
            if (!la.hasShortcutHostPermission()) return out;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery()
                    .setPackage(cn.getPackageName())
                    .setActivity(cn)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> list = la.getShortcuts(q, Process.myUserHandle());
            if (list != null) {
                for (ShortcutInfo s : list) if (s.isEnabled()) out.add(s);
            }
        } catch (SecurityException | IllegalStateException ignored) {
            // Pas écran d'accueil par défaut, ou profil verrouillé.
        }
        out.sort((a, b) -> Integer.compare(a.getRank(), b.getRank()));
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    static Drawable icon(Context c, ShortcutInfo s) {
        LauncherApps la = c.getSystemService(LauncherApps.class);
        try {
            return la == null ? null : la.getShortcutIconDrawable(s, c.getResources().getDisplayMetrics().densityDpi);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void start(Context c, ShortcutInfo s, Rect bounds) {
        LauncherApps la = c.getSystemService(LauncherApps.class);
        try {
            if (la != null) la.startShortcut(s, bounds, null);
        } catch (RuntimeException e) {
            Toast.makeText(c, "Raccourci indisponible", Toast.LENGTH_SHORT).show();
        }
    }

    static CharSequence label(ShortcutInfo s) {
        CharSequence l = s.getShortLabel();
        if (l == null || l.length() == 0) l = s.getLongLabel();
        return l == null ? "" : l;
    }
}
