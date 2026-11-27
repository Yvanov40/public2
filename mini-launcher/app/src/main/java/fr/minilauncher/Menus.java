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

        // En-tête compact : icône, nom, infos
        android.widget.LinearLayout head = new android.widget.LinearLayout(a);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(s.px(20), 0, s.px(20), s.px(8));
        android.widget.ImageView icon = new android.widget.ImageView(a);
        icon.setImageDrawable(iconOf(a, cn));
        android.widget.LinearLayout.LayoutParams il = new android.widget.LinearLayout.LayoutParams(s.px(40), s.px(40));
        il.rightMargin = s.px(14);
        head.addView(icon, il);
        android.widget.LinearLayout texts = new android.widget.LinearLayout(a);
        texts.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.TextView name = s.text(label, 17, Sheet.TEXT);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        int n = store.usageOf(key);
        String where = folder != null ? "Dans « " + folder + " »" : store.isOnHome(key) ? "Sur l'accueil" : null;
        String sub = (n > 0 ? "Ouverte " + n + " fois" : "Pas encore ouverte d'ici")
                + (where != null ? " · " + where : "") + (store.isPinned(key) ? " · ★" : "");
        android.widget.TextView subView = s.text(sub, 12, Sheet.MUTED);
        subView.setSingleLine(true);
        subView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(subView);
        head.addView(texts, new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        s.content.addView(head);

        // Raccourcis de l'appli (« Nouveau message », « Itinéraire »…), en lignes serrées
        List<android.content.pm.ShortcutInfo> shortcuts = Shortcuts.of(a, cn);
        for (int i = 0; i < shortcuts.size(); i++) {
            android.content.pm.ShortcutInfo info = shortcuts.get(i);
            android.view.View r = compactRow(s, a, Shortcuts.icon(a, info), Shortcuts.label(info).toString());
            r.setOnClickListener(v -> {
                s.dialog.dismiss();
                Shortcuts.start(a, info, null);
            });
            s.content.addView(r);
        }

        // Applis liées : un mini-dossier propre à cette appli
        linkedApps(s, a, key, label, changed);

        // Tuiles d'action
        List<Object[]> acts = new ArrayList<>();
        boolean pinned = store.isPinned(key);
        acts.add(new Object[]{pinned ? "★" : "☆", pinned ? "Défavoriser" : "Favori", false, (Runnable) () -> {
            store.togglePin(key);
            changed.run();
        }});
        if (folder != null) {
            acts.add(new Object[]{"↩", "Sortir", false, (Runnable) () -> {
                List<Store.Group> groups = store.groups();
                Store.Group g = Store.find(groups, folder);
                if (g != null) {
                    g.apps.remove(key);
                    store.setGroups(groups);
                }
                changed.run();
            }});
        } else if (store.isOnHome(key)) {
            acts.add(new Object[]{"↩", "Retirer", false, (Runnable) () -> {
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

        addTiles(s, a, acts, true);
        s.show();
    }

    /** Ligne serrée : petite icône + texte. */
    private static android.view.View compactRow(Sheet s, Activity a, Drawable d, String text) {
        android.widget.LinearLayout r = new android.widget.LinearLayout(a);
        r.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(s.px(44));
        r.setPadding(s.px(22), s.px(4), s.px(20), s.px(4));
        android.widget.ImageView iv = new android.widget.ImageView(a);
        if (d != null) iv.setImageDrawable(Sheet.copy(d));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(s.px(28), s.px(28));
        lp.rightMargin = s.px(16);
        r.addView(iv, lp);
        android.widget.TextView t = s.text(text, 15, Sheet.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t, new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        r.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Sheet.RIPPLE), null, mask));
        return r;
    }

    /**
     * Rangée d'applis liées sous les raccourcis (icône + nom, défilement horizontal), avec une
     * case « + » pour choisir lesquelles. Toucher une appli l'ouvre.
     */
    /** Les applis liées encore installées, dans l'ordre choisi. */
    static List<AppRepository.AppEntry> linkedEntries(android.content.Context c, String key) {
        List<AppRepository.AppEntry> all = AppRepository.get(c).cached();
        List<AppRepository.AppEntry> linked = new ArrayList<>();
        for (String k : Store.get(c).linked(key)) {
            for (AppRepository.AppEntry e : all) {
                if (Store.key(e.component).equals(k)) {
                    linked.add(e);
                    break;
                }
            }
        }
        return linked;
    }

    private static void linkedApps(Sheet s, Activity a, String key, String label, Runnable changed) {
        Store store = Store.get(a);
        List<AppRepository.AppEntry> all = AppRepository.get(a).cached();
        List<AppRepository.AppEntry> linked = linkedEntries(a, key);
        android.widget.TextView title = s.text(linked.isEmpty() ? "APPLIS LIÉES" : "APPLIS LIÉES · " + linked.size(), 11, Sheet.MUTED);
        title.setLetterSpacing(0.08f);
        title.setPadding(s.px(22), s.px(8), s.px(22), s.px(2));
        s.content.addView(title);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setPadding(s.px(14), 0, s.px(14), s.px(6));
        hs.addView(row);
        for (AppRepository.AppEntry e : linked) {
            android.view.View cell = linkedCell(s, a, Sheet.copy(e.icon), null, e.label);
            cell.setOnClickListener(v -> {
                s.dialog.dismiss();
                Launcher.launch(a, e, null, null);
            });
            row.addView(cell);
        }
        android.view.View add = linkedCell(s, a, null, linked.isEmpty() ? "＋" : "✎",
                linked.isEmpty() ? "Ajouter" : "Modifier");
        add.setOnClickListener(v -> {
            s.dialog.dismiss();
            List<AppRepository.AppEntry> choices = new ArrayList<>();
            for (AppRepository.AppEntry e : all) if (!Store.key(e.component).equals(key)) choices.add(e);
            pickApps(a, "Applis liées à « " + label + " »", choices, store.linked(key), keys -> {
                store.setLinked(key, keys);
                changed.run();
            });
        });
        row.addView(add);
        if (!linked.isEmpty()) {
            int st = Math.max(0, Math.min(3, store.linkedStyle(key)));
            String[] glyphs = {"◎", "▮", "▦", "☰"};
            android.view.View format = linkedCell(s, a, null, glyphs[st], "Format");
            format.setOnClickListener(v -> {
                s.dialog.dismiss();
                choose(a, "Glisser depuis « " + label + " »", FolderWheelView.STYLES, st,
                        i -> store.setNum("linkedStyle_" + key, i));
            });
            row.addView(format);
        }
        s.content.addView(hs);
        if (!linked.isEmpty()) {
            android.widget.TextView hint = s.text("Sur l'accueil : appuie sur l'icône et glisse pour les ouvrir", 11, Sheet.MUTED);
            hint.setPadding(s.px(22), 0, s.px(22), s.px(6));
            s.content.addView(hint);
        }
    }

    private static android.view.View linkedCell(Sheet s, Activity a, Drawable icon, String glyph, String text) {
        android.widget.LinearLayout c = new android.widget.LinearLayout(a);
        c.setOrientation(android.widget.LinearLayout.VERTICAL);
        c.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        c.setPadding(s.px(4), s.px(6), s.px(4), s.px(6));
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(s.px(14));
        c.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Sheet.RIPPLE), null, mask));
        if (icon != null) {
            android.widget.ImageView iv = new android.widget.ImageView(a);
            iv.setImageDrawable(icon);
            c.addView(iv, new android.widget.LinearLayout.LayoutParams(s.px(40), s.px(40)));
        } else {
            android.widget.TextView g = s.text(glyph, 18, s.accent);
            g.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable gb = new android.graphics.drawable.GradientDrawable();
            gb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gb.setColor(Sheet.FIELD);
            g.setBackground(gb);
            c.addView(g, new android.widget.LinearLayout.LayoutParams(s.px(40), s.px(40)));
        }
        android.widget.TextView t = s.text(text, 11, Sheet.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, s.px(4), 0, 0);
        c.addView(t, new android.widget.LinearLayout.LayoutParams(s.px(64),
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private static android.view.View tile(Sheet s, Activity a, String glyph, String label, boolean danger, Runnable action) {
        return tile(s, a, glyph, label, danger, action, false);
    }

    private static android.view.View tile(Sheet s, Activity a, String glyph, String label, boolean danger,
                                          Runnable action, boolean compact) {
        int color = danger ? Sheet.DANGER : s.accent;
        android.widget.LinearLayout t = new android.widget.LinearLayout(a);
        t.setOrientation(android.widget.LinearLayout.VERTICAL);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(s.px(4), s.px(compact ? 6 : 10), s.px(4), s.px(compact ? 6 : 10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Sheet.FIELD);
        bg.setCornerRadius(s.px(18));
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(s.px(18));
        t.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Sheet.RIPPLE), bg, mask));
        android.widget.TextView g = s.text(glyph, compact ? 15 : 18, color);
        g.setGravity(android.view.Gravity.CENTER);
        g.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        android.graphics.drawable.GradientDrawable gb = new android.graphics.drawable.GradientDrawable();
        gb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gb.setColor((color & 0x00FFFFFF) | 0x26000000);
        g.setBackground(gb);
        t.addView(g, new android.widget.LinearLayout.LayoutParams(s.px(compact ? 30 : 38), s.px(compact ? 30 : 38)));
        android.widget.TextView l = s.text(label, compact ? 12 : 13, danger ? Sheet.DANGER : Sheet.TEXT);
        l.setGravity(android.view.Gravity.CENTER);
        l.setMaxLines(compact ? 1 : 2);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.setPadding(0, s.px(compact ? 3 : 6), 0, 0);
        t.addView(l);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0,
                s.px(compact ? 66 : 96), 1f);
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
                           Runnable resize, int visible) {
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
        acts.add(new Object[]{"⇅", "Organiser", false, (Runnable) () -> organizeFolder(a, folder, apps, visible, changed)});
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
     * Organiser un dossier : ses applis en grille, dans leur ordre. Toucher une appli puis la
     * place voulue l'y déplace. Les {@code visible} premières (encadrées) se voient sur
     * l'icône du dossier ; l'ordre est aussi celui du cercle (en haut, puis sens horaire).
     */
    static void organizeFolder(Activity a, String folder, List<AppRepository.AppEntry> all, int visible,
                               Runnable changed) {
        Store store = Store.get(a);
        Sheet s = new Sheet(a, "Organiser « " + folder + " »");
        android.widget.TextView hint = s.text("", 13, Sheet.MUTED);
        hint.setPadding(s.px(22), 0, s.px(22), s.px(10));
        s.content.addView(hint);
        android.widget.LinearLayout gridBox = new android.widget.LinearLayout(a);
        gridBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        s.content.addView(gridBox);
        int[] selected = {-1};

        Runnable[] render = {null};
        render[0] = () -> {
            gridBox.removeAllViews();
            List<AppRepository.AppEntry> apps = orderedApps(store, folder, all);
            hint.setText(selected[0] < 0
                    ? "Touche une appli, puis la place où la mettre. "
                    + (visible > 0 ? "Encadrées : les " + visible + " visibles sur l'accueil." : "")
                    + " Ordre du cercle : en haut, puis sens horaire."
                    : "Touche maintenant la place de « " + apps.get(selected[0]).label + " ».");
            int cols = 4;
            android.widget.LinearLayout row = null;
            for (int i = 0; i < apps.size(); i++) {
                if (i % cols == 0) {
                    row = new android.widget.LinearLayout(a);
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    row.setPadding(s.px(12), 0, s.px(12), s.px(6));
                    gridBox.addView(row);
                }
                AppRepository.AppEntry app = apps.get(i);
                int index = i;
                android.widget.LinearLayout cell = new android.widget.LinearLayout(a);
                cell.setOrientation(android.widget.LinearLayout.VERTICAL);
                cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                cell.setPadding(s.px(2), s.px(8), s.px(2), s.px(8));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(s.px(16));
                if (index == selected[0]) {
                    bg.setColor((s.accent & 0x00FFFFFF) | 0x55000000);
                } else if (index < visible) {
                    bg.setColor((s.accent & 0x00FFFFFF) | 0x14000000);
                    bg.setStroke(s.px(1.5f), (s.accent & 0x00FFFFFF) | 0xAA000000);
                } else {
                    bg.setColor(0);
                }
                cell.setBackground(bg);
                android.widget.ImageView icon = new android.widget.ImageView(a);
                icon.setImageDrawable(Sheet.copy(app.icon));
                cell.addView(icon, new android.widget.LinearLayout.LayoutParams(s.px(40), s.px(40)));
                android.widget.TextView name = s.text((index + 1) + ". " + app.label, 11, Sheet.TEXT);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(android.view.Gravity.CENTER);
                name.setPadding(0, s.px(5), 0, 0);
                cell.addView(name, new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                cell.setOnClickListener(v -> {
                    if (selected[0] < 0) {
                        selected[0] = index;                    // 1er toucher : on prend l'appli
                    } else if (selected[0] == index) {
                        selected[0] = -1;                       // retoucher : on la repose
                    } else {
                        List<AppRepository.AppEntry> cur = orderedApps(store, folder, all);
                        store.moveInFolder(folder, Store.key(cur.get(selected[0]).component), index);
                        selected[0] = -1;
                        changed.run();
                    }
                    render[0].run();
                });
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(s.px(3), s.px(3), s.px(3), s.px(3));
                row.addView(cell, lp);
            }
            if (row != null) {
                for (int k = apps.size() % cols; k != 0 && k < cols; k++) {
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0, 1, 1f);
                    lp.setMargins(s.px(3), 0, s.px(3), 0);
                    row.addView(new android.view.View(a), lp);
                }
            }
        };
        render[0].run();
        s.show();
    }

    /** Les applis du dossier, dans l'ordre enregistré. */
    private static List<AppRepository.AppEntry> orderedApps(Store store, String folder, List<AppRepository.AppEntry> all) {
        List<AppRepository.AppEntry> out = new ArrayList<>();
        Store.Group g = Store.find(store.groups(), folder);
        if (g == null) return out;
        java.util.Map<String, AppRepository.AppEntry> byKey = new java.util.HashMap<>();
        for (AppRepository.AppEntry e : all) byKey.put(Store.key(e.component), e);
        for (String k : g.apps) {
            AppRepository.AppEntry e = byKey.get(k);
            if (e != null) out.add(e);
        }
        return out;
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
        addTiles(s, a, acts, false);
    }

    private static void addTiles(Sheet s, Activity a, List<Object[]> acts, boolean compact) {
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
            row.addView(tile(s, a, (String) act[0], (String) act[1], (Boolean) act[2], (Runnable) act[3], compact));
        }
        // Cases vides : la dernière ligne garde la largeur des tuiles.
        if (row != null) {
            for (int k = acts.size() % cols; k != 0 && k < cols; k++) {
                android.view.View v = new android.view.View(a);
                android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0,
                        s.px(compact ? 66 : 96), 1f);
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
        b.add("Ouverture", () -> choose(a, "Ouverture du dossier", FolderWheelView.STYLES, g.openStyle, i -> {
            store.setFolderOpenStyle(folder, i);
            changed.run();
        })).subtitle = FolderWheelView.STYLES[Math.max(0, Math.min(3, g.openStyle))];
        String[] yesNo = {"Oui", "Non (seul le nom de l'appli survolée s'affiche)"};
        b.add("Afficher le nom", () -> choose(a, "Afficher le nom du dossier", yesNo, g.showName ? 0 : 1, i -> {
            store.setFolderShowName(folder, i == 0);
            changed.run();
        })).subtitle = g.showName ? "Oui" : "Non";
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
