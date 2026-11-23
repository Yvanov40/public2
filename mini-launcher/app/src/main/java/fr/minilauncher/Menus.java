package fr.minilauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * Menus : appui long sur une appli ou un dossier, choix d'applis. Tous s'affichent dans un
 * panneau qui monte du bas ({@link Sheet}).
 * {@code changed} est appelé après une modification.
 */
final class Menus {

    private Menus() {
    }

    interface AppCallback {
        void onApp(AppRepository.AppEntry app);
    }

    interface AppsCallback {
        void onApps(List<String> keys);
    }

    interface NameCallback {
        void onName(String name);
    }

    /** Construction d'un menu : libellés et actions côte à côte. */
    private static final class Builder {
        final List<Sheet.Item> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        Sheet.Item add(String label, Runnable action) {
            Sheet.Item it = new Sheet.Item(label);
            items.add(it);
            actions.add(action);
            return it;
        }

        void show(Activity a, String title) {
            Sheet.list(a, title, items, i -> actions.get(i).run());
        }
    }

    // ------------------------------------------------------------ appli

    /**
     * Menu d'une appli : en-tête (icône, nom), puis de grosses tuiles — favori, retirer (de
     * l'accueil ou du dossier, selon le cas), infos, désinstaller. Poser sur l'accueil ou dans
     * un dossier se fait par glisser-déposer.
     */
    static void appMenu(Activity a, ComponentName cn, String label, String folder, Runnable changed) {
        Store store = Store.get(a);
        String key = Store.key(cn);
        Sheet s = new Sheet(a, null);

        // En-tête : icône et nom
        android.widget.LinearLayout head = new android.widget.LinearLayout(a);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(s.px(22), s.px(2), s.px(22), s.px(14));
        android.widget.ImageView icon = new android.widget.ImageView(a);
        icon.setImageDrawable(iconOf(a, cn));
        android.widget.LinearLayout.LayoutParams il = new android.widget.LinearLayout.LayoutParams(s.px(52), s.px(52));
        il.rightMargin = s.px(16);
        head.addView(icon, il);
        android.widget.LinearLayout texts = new android.widget.LinearLayout(a);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView name = s.text(label, 19, Sheet.TEXT);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        int n = store.usageOf(key);
        String where = folder != null ? "Dans « " + folder + " »" : store.isOnHome(key) ? "Sur l'accueil" : null;
        String sub = (n > 0 ? "Ouverte " + n + " fois" : "Pas encore ouverte d'ici")
                + (where != null ? " · " + where : "") + (store.isPinned(key) ? " · ★" : "");
        texts.addView(s.text(sub, 13, Sheet.MUTED));
        head.addView(texts, new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        s.content.addView(head);

        // Raccourcis de l'appli (« Nouveau message », « Itinéraire »…)
        List<android.content.pm.ShortcutInfo> shortcuts = Shortcuts.of(a, cn);
        for (int i = 0; i < shortcuts.size(); i++) {
            android.content.pm.ShortcutInfo info = shortcuts.get(i);
            Sheet.Item it = new Sheet.Item(Shortcuts.label(info).toString());
            it.icon = Shortcuts.icon(a, info);
            android.view.View r = s.row(it);
            r.setOnClickListener(v -> {
                s.dialog.dismiss();
                Shortcuts.start(a, info, null);
            });
            s.content.addView(r);
        }
        if (!shortcuts.isEmpty()) {
            android.view.View space = new android.view.View(a);
            s.content.addView(space, new android.widget.LinearLayout.LayoutParams(1, s.px(10)));
        }

        // Tuiles d'action
        List<Object[]> acts = new ArrayList<>();
        boolean pinned = store.isPinned(key);
        acts.add(new Object[]{pinned ? "★" : "☆", pinned ? "Retirer des favoris" : "Favori", false, (Runnable) () -> {
            store.togglePin(key);
            changed.run();
        }});
        if (folder != null) {
            acts.add(new Object[]{"↩", "Retirer du dossier", false, (Runnable) () -> {
                List<Store.Group> groups = store.groups();
                Store.Group g = Store.find(groups, folder);
                if (g != null) {
                    g.apps.remove(key);
                    store.setGroups(groups);
                }
                changed.run();
            }});
        } else if (store.isOnHome(key)) {
            acts.add(new Object[]{"↩", "Retirer de l'accueil", false, (Runnable) () -> {
                store.toggleHomeApp(key);
                changed.run();
            }});
        }
        acts.add(new Object[]{"i", "Infos", false, (Runnable) () -> Launcher.appInfo(a, cn)});
        acts.add(new Object[]{"✕", "Désinstaller", true, (Runnable) () -> {
            try {
                a.startActivity(new android.content.Intent(android.content.Intent.ACTION_DELETE,
                        android.net.Uri.parse("package:" + cn.getPackageName())));
            } catch (RuntimeException ignored) {
            }
        }});

        addTiles(s, a, acts);
        s.show();
    }

    private static android.view.View tile(Sheet s, Activity a, String glyph, String label, boolean danger, Runnable action) {
        int color = danger ? Sheet.DANGER : s.accent;
        android.widget.LinearLayout t = new android.widget.LinearLayout(a);
        t.setOrientation(android.widget.LinearLayout.VERTICAL);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(s.px(6), s.px(10), s.px(6), s.px(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Sheet.FIELD);
        bg.setCornerRadius(s.px(18));
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(s.px(18));
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Sheet.RIPPLE), bg, mask));
        android.widget.TextView g = s.text(glyph, 18, color);
        g.setGravity(android.view.Gravity.CENTER);
        g.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        android.graphics.drawable.GradientDrawable gb = new android.graphics.drawable.GradientDrawable();
        gb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gb.setColor((color & 0x00FFFFFF) | 0x26000000);
        g.setBackground(gb);
        t.addView(g, new android.widget.LinearLayout.LayoutParams(s.px(38), s.px(38)));
        android.widget.TextView l = s.text(label, 13, danger ? Sheet.DANGER : Sheet.TEXT);
        l.setGravity(android.view.Gravity.CENTER);
        l.setMaxLines(2);
        l.setPadding(0, s.px(6), 0, 0);
        t.addView(l);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, s.px(96), 1f);
        lp.setMargins(s.px(4), 0, s.px(4), 0);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> {
            s.dialog.dismiss();
            action.run();
        });
        return t;
    }

    static Drawable iconOf(Activity a, ComponentName cn) {
        try {
            return a.getPackageManager().getActivityIcon(cn);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ dossier

    /**
     * Menu d'un dossier, en tuiles : applis, personnaliser, taille sur l'accueil, renommer,
     * supprimer. {@code resize} ouvre le choix de la taille (null : pas de tuile Taille).
     */
    static void folderMenu(Activity a, String folder, List<AppRepository.AppEntry> apps, Runnable changed,
                           Runnable resize) {
        Store store = Store.get(a);
        Store.Group g0 = Store.find(store.groups(), folder);
        int color = g0 == null ? Store.get(a).accent() : g0.color;
        int n = g0 == null ? 0 : g0.apps.size();
        List<Object[]> acts = new ArrayList<>();
        acts.add(new Object[]{"＋", "Applis", false, (Runnable) () -> {
            Store.Group g = Store.find(store.groups(), folder);
            pickApps(a, "Applis du dossier", apps, g == null ? new ArrayList<>() : g.apps, keys -> {
                store.setFolderApps(folder, keys);
                changed.run();
            });
        }});
        acts.add(new Object[]{"◐", "Personnaliser", false, (Runnable) () -> customizeFolder(a, folder, changed)});
        if (resize != null) acts.add(new Object[]{"⤢", "Taille", false, resize});
        acts.add(new Object[]{"✎", "Renommer", false, (Runnable) () -> askName(a, "Renommer le dossier", folder, name -> {
            store.renameFolder(folder, name);
            changed.run();
        })});
        acts.add(new Object[]{"✕", "Supprimer", true, (Runnable) () -> {
            Builder c = new Builder();
            c.add("Supprimer", () -> {
                store.deleteFolder(folder);
                changed.run();
            }).danger = true;
            c.add("Annuler", () -> {
            });
            c.show(a, "Supprimer « " + folder + " » ? Les applis restent installées.");
        }});
        tileSheet(a, null, color, folder, n + (n > 1 ? " applis" : " appli"), acts);
    }

    /**
     * Panneau en tuiles : en-tête (icône ou pastille de couleur, titre, sous-titre) puis des
     * tuiles {glyphe, libellé, rouge ?, action}, trois par ligne.
     */
    static void tileSheet(Activity a, Drawable icon, int dot, String title, String subtitle, List<Object[]> acts) {
        Sheet s = new Sheet(a, null);
        android.widget.LinearLayout head = new android.widget.LinearLayout(a);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(s.px(22), s.px(2), s.px(22), s.px(14));
        android.view.View lead;
        if (icon != null) {
            android.widget.ImageView iv = new android.widget.ImageView(a);
            iv.setImageDrawable(icon);
            lead = iv;
        } else {
            lead = new android.view.View(a);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(s.px(14));
            gd.setColor(dot);
            lead.setBackground(gd);
        }
        android.widget.LinearLayout.LayoutParams ll = new android.widget.LinearLayout.LayoutParams(s.px(48), s.px(48));
        ll.rightMargin = s.px(16);
        head.addView(lead, ll);
        android.widget.LinearLayout texts = new android.widget.LinearLayout(a);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView t = s.text(title, 19, Sheet.TEXT);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t);
        if (subtitle != null) texts.addView(s.text(subtitle, 13, Sheet.MUTED));
        head.addView(texts, new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        s.content.addView(head);
        addTiles(s, a, acts);
        s.show();
    }

    private static void addTiles(Sheet s, Activity a, List<Object[]> acts) {
        int cols = acts.size() <= 4 ? acts.size() : 3;
        android.widget.LinearLayout row = null;
        for (int i = 0; i < acts.size(); i++) {
            if (i % cols == 0) {
                row = new android.widget.LinearLayout(a);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setPadding(s.px(16), 0, s.px(16), s.px(8));
                s.content.addView(row);
            }
            Object[] act = acts.get(i);
            row.addView(tile(s, a, (String) act[0], (String) act[1], (Boolean) act[2], (Runnable) act[3]));
        }
        // Cases vides : la dernière ligne garde la largeur des tuiles.
        if (row != null) {
            for (int k = acts.size() % cols; k != 0 && k < cols; k++) {
                android.view.View v = new android.view.View(a);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, s.px(96), 1f);
                lp.setMargins(s.px(4), 0, s.px(4), 0);
                row.addView(v, lp);
            }
        }
    }

    private static final String[] COLOR_NAMES = {"Orange", "Vert", "Rose", "Violet", "Bleu ciel", "Jaune",
            "Anis", "Corail", "Bleu", "Blanc", "Gris"};
    private static final int[] COLORS = {0xFFFFB86B, 0xFF7EE0A1, 0xFFFF8FA3, 0xFFC9A7FF, 0xFF6FD3FF,
            0xFFFFE07A, 0xFFB3E36B, 0xFFFF9E7A, 0xFF8FA6FF, 0xFFFFFFFF, 0xFF9AA0AA};

    /** Couleur, style d'icône et forme d'un dossier. */
    static void customizeFolder(Activity a, String folder, Runnable changed) {
        Store store = Store.get(a);
        Store.Group g = Store.find(store.groups(), folder);
        if (g == null) return;
        String[] styles = {"Grille des 4 premières applis", "Première appli + nombre", "Initiale du dossier",
                "Invisible (case vide, même fonctionnement)"};
        String[] shapes = {"Carré arrondi", "Rond"};
        Builder b = new Builder();
        Sheet.Item color = b.add("Couleur", () -> Sheet.colors(a, "Couleur", COLOR_NAMES, COLORS, g.color, i -> {
            store.customizeFolder(folder, COLORS[i], -1, -1);
            changed.run();
        }));
        color.swatch = g.color;
        b.add("Style de l'icône", () -> choose(a, "Style de l'icône", styles, g.style, i -> {
            store.customizeFolder(folder, -1, i, -1);
            changed.run();
        })).subtitle = styles[Math.max(0, Math.min(3, g.style))];
        String[] sizes = {"Très petites", "Petites", "Moyennes", "Grandes", "Très grandes"};
        b.add("Taille des icônes", () -> choose(a, "Taille des icônes dans le dossier", sizes, g.iconSize, i -> {
            store.setFolderIconSize(folder, i);
            changed.run();
        })).subtitle = sizes[Math.max(0, Math.min(4, g.iconSize))] + " (cercle, dossier agrandi, icônes directes)";
        String[] modes = {"Ouvre le cercle", "Icônes directes (toucher une mini-icône l'ouvre)"};
        b.add("Toucher le dossier", () -> choose(a, "Toucher le dossier", modes, g.direct ? 1 : 0, i -> {
            store.setFolderDirect(folder, i == 1);
            changed.run();
        })).subtitle = modes[g.direct ? 1 : 0];
        b.add("Forme", () -> choose(a, "Forme", shapes, g.shape, i -> {
            store.customizeFolder(folder, -1, -1, i);
            changed.run();
        })).subtitle = shapes[Math.max(0, Math.min(1, g.shape))];
        b.show(a, "Personnaliser « " + folder + " »");
    }

    /** Liste à choix unique, l'option actuelle cochée. */
    static void choose(Activity a, String title, String[] options, int current, Sheet.IntCallback cb) {
        List<Sheet.Item> items = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            Sheet.Item it = new Sheet.Item(options[i]);
            it.selected = i == current;
            items.add(it);
        }
        Sheet.list(a, title, items, cb);
    }

    /** Nouveau dossier : un nom, puis les applis à cocher. */
    static void newFolder(Activity a, List<AppRepository.AppEntry> apps, Runnable changed) {
        Store store = Store.get(a);
        askName(a, "Nouveau dossier", store.freeFolderName("Dossier"), name ->
                pickApps(a, "Applis de « " + name + " »", apps, new ArrayList<>(), keys -> {
                    store.createFolder(name, keys, -1, -1);
                    changed.run();
                }));
    }

    // ------------------------------------------------------------ choix d'applis

    static void pickApp(Activity a, String title, List<AppRepository.AppEntry> apps, AppCallback cb) {
        Sheet.apps(a, title, apps, cb);
    }

    static void pickApps(Activity a, String title, List<AppRepository.AppEntry> apps,
                         List<String> selected, AppsCallback cb) {
        Sheet.pickApps(a, title, apps, selected, cb);
    }

    static void askName(Activity a, String title, String initial, NameCallback ok) {
        Sheet.askName(a, title, initial, ok);
    }
}
