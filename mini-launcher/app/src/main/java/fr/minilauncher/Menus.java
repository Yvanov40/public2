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

    static void appMenu(Activity a, ComponentName cn, String label, String folder, Runnable changed) {
        Store store = Store.get(a);
        String key = Store.key(cn);
        Builder b = new Builder();
        b.add(store.isPinned(key) ? "Retirer des favoris" : "Épingler en favori ★", () -> {
            store.togglePin(key);
            changed.run();
        });
        b.add(store.isOnHome(key) ? "Retirer de l'écran d'accueil" : "Ajouter à l'écran d'accueil", () -> {
            store.toggleHomeApp(key);
            changed.run();
        });
        b.add("Ajouter à un dossier…", () -> chooseFolder(a, key, changed));
        if (folder != null) {
            b.add("Retirer du dossier « " + folder + " »", () -> {
                List<Store.Group> groups = store.groups();
                Store.Group g = Store.find(groups, folder);
                if (g != null) {
                    g.apps.remove(key);
                    store.setGroups(groups);
                }
                changed.run();
            });
        }
        b.add("Infos de l'appli", () -> Launcher.appInfo(a, cn));
        b.show(a, label);
    }

    static Drawable iconOf(Activity a, ComponentName cn) {
        try {
            return a.getPackageManager().getActivityIcon(cn);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static void chooseFolder(Activity a, String key, Runnable changed) {
        Store store = Store.get(a);
        List<Store.Group> groups = store.groups();
        Builder b = new Builder();
        for (Store.Group g : groups) {
            Sheet.Item it = b.add(g.name, () -> {
                store.addToFolder(g.name, key);
                changed.run();
            });
            it.swatch = g.color;
            it.selected = g.apps.contains(key);
        }
        b.add("＋  Nouveau dossier…", () -> askName(a, "Nouveau dossier", store.freeFolderName("Dossier"), name -> {
            List<String> one = new ArrayList<>();
            one.add(key);
            store.createFolder(name, one, -1, -1);
            changed.run();
        }));
        b.show(a, "Ajouter au dossier");
    }

    // ------------------------------------------------------------ dossier

    static void folderMenu(Activity a, String folder, List<AppRepository.AppEntry> apps, Runnable changed) {
        Store store = Store.get(a);
        Builder b = new Builder();
        b.add("Choisir les applis…", () -> {
            Store.Group g = Store.find(store.groups(), folder);
            pickApps(a, "Applis du dossier", apps, g == null ? new ArrayList<>() : g.apps, keys -> {
                store.setFolderApps(folder, keys);
                changed.run();
            });
        });
        b.add("Personnaliser…", () -> customizeFolder(a, folder, changed));
        b.add("Renommer", () -> askName(a, "Renommer le dossier", folder, name -> {
            store.renameFolder(folder, name);
            changed.run();
        }));
        b.add(store.isFolderOnHome(folder) ? "Retirer de l'écran d'accueil" : "Mettre sur l'écran d'accueil", () -> {
            store.toggleFolderOnHome(folder);
            changed.run();
        });
        b.add("Supprimer le dossier", () -> {
            Builder c = new Builder();
            c.add("Supprimer", () -> {
                store.deleteFolder(folder);
                changed.run();
            }).danger = true;
            c.add("Annuler", () -> {
            });
            c.show(a, "Supprimer « " + folder + " » ? Les applis restent installées.");
        }).danger = true;
        b.show(a, folder);
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
