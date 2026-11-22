package fr.minilauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Menus : appui long sur une appli ou un dossier, choix d'applis.
 * {@code changed} est appelé après une modification.
 */
final class Menus {

    private Menus() {
    }

    private static AlertDialog.Builder builder(Activity a) {
        return new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert);
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

    // ------------------------------------------------------------ appli

    static void appMenu(Activity a, ComponentName cn, String label, String folder, Runnable changed) {
        Store store = Store.get(a);
        String key = Store.key(cn);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(store.isPinned(key) ? "Retirer des favoris (★)" : "Épingler en favori (★)");
        actions.add(() -> {
            store.togglePin(key);
            changed.run();
        });
        labels.add(store.isOnHome(key) ? "Retirer de l'écran d'accueil" : "Ajouter à l'écran d'accueil");
        actions.add(() -> {
            store.toggleHomeApp(key);
            changed.run();
        });
        labels.add("Ajouter à un dossier…");
        actions.add(() -> chooseFolder(a, key, changed));
        if (folder != null) {
            labels.add("Retirer du dossier « " + folder + " »");
            actions.add(() -> {
                List<Store.Group> groups = store.groups();
                Store.Group g = Store.find(groups, folder);
                if (g != null) {
                    g.apps.remove(key);
                    store.setGroups(groups);
                }
                changed.run();
            });
        }
        labels.add("Infos de l'appli");
        actions.add(() -> Launcher.appInfo(a, cn));

        builder(a).setTitle(label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private static void chooseFolder(Activity a, String key, Runnable changed) {
        Store store = Store.get(a);
        List<Store.Group> groups = store.groups();
        List<String> names = new ArrayList<>();
        for (Store.Group g : groups) names.add((g.apps.contains(key) ? "✓ " : "") + g.name);
        names.add("＋ Nouveau dossier…");
        builder(a).setTitle("Ajouter au dossier")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == groups.size()) {
                        askName(a, "Nouveau dossier", store.freeFolderName("Dossier"), name -> {
                            List<String> one = new ArrayList<>();
                            one.add(key);
                            store.createFolder(name, one, -1, -1);
                            changed.run();
                        });
                    } else {
                        store.addToFolder(groups.get(which).name, key);
                        changed.run();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ dossier

    static void folderMenu(Activity a, String folder, List<AppRepository.AppEntry> apps, Runnable changed) {
        Store store = Store.get(a);
        boolean onHome = store.isFolderOnHome(folder);
        String[] items = {"Choisir les applis…", "Personnaliser…", "Renommer", onHome ? "Retirer de l'écran d'accueil"
                : "Mettre sur l'écran d'accueil", "Supprimer le dossier"};
        builder(a).setTitle(folder).setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    Store.Group g = Store.find(store.groups(), folder);
                    pickApps(a, "Applis du dossier", apps, g == null ? new ArrayList<>() : g.apps, keys -> {
                        store.setFolderApps(folder, keys);
                        changed.run();
                    });
                    break;
                case 1:
                    customizeFolder(a, folder, changed);
                    break;
                case 2:
                    askName(a, "Renommer le dossier", folder, name -> {
                        store.renameFolder(folder, name);
                        changed.run();
                    });
                    break;
                case 3:
                    store.toggleFolderOnHome(folder);
                    changed.run();
                    break;
                default:
                    builder(a).setTitle("Supprimer « " + folder + " » ?")
                            .setMessage("Les applis ne sont pas désinstallées.")
                            .setPositiveButton("Supprimer", (d2, w) -> {
                                store.deleteFolder(folder);
                                changed.run();
                            })
                            .setNegativeButton("Annuler", null)
                            .show();
            }
        }).show();
    }

    private static final String[] COLOR_NAMES = {"Orange", "Vert", "Rose", "Violet", "Bleu ciel", "Jaune",
            "Anis", "Corail", "Bleu", "Blanc", "Gris"};
    private static final int[] COLORS = {0xFFFFB86B, 0xFF7EE0A1, 0xFFFF8FA3, 0xFFC9A7FF, 0xFF6FD3FF,
            0xFFFFE07A, 0xFFB3E36B, 0xFFFF9E7A, 0xFF8FA6FF, 0xFFFFFFFF, 0xFF9AA0AA};

    /** Couleur, style d'icône et forme d'un dossier. */
    static void customizeFolder(Activity a, String folder, Runnable changed) {
        Store store = Store.get(a);
        String[] items = {"Couleur", "Style de l'icône", "Forme"};
        builder(a).setTitle("Personnaliser « " + folder + " »").setItems(items, (d, which) -> {
            if (which == 0) {
                builder(a).setTitle("Couleur").setItems(COLOR_NAMES, (d2, i) -> {
                    store.customizeFolder(folder, COLORS[i], -1, -1);
                    changed.run();
                }).show();
            } else if (which == 1) {
                builder(a).setTitle("Style de l'icône")
                        .setItems(new String[]{"Grille des 4 premières applis", "Première appli + nombre",
                                "Initiale du dossier"}, (d2, i) -> {
                            store.customizeFolder(folder, -1, i, -1);
                            changed.run();
                        }).show();
            } else {
                builder(a).setTitle("Forme")
                        .setItems(new String[]{"Carré arrondi", "Rond"}, (d2, i) -> {
                            store.customizeFolder(folder, -1, -1, i);
                            changed.run();
                        }).show();
            }
        }).show();
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
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label;
        builder(a).setTitle(title)
                .setItems(labels, (d, which) -> cb.onApp(apps.get(which)))
                .show();
    }

    static void pickApps(Activity a, String title, List<AppRepository.AppEntry> apps,
                         List<String> selected, AppsCallback cb) {
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        Set<String> sel = new HashSet<>(selected);
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i).label;
            checked[i] = sel.contains(Store.key(apps.get(i).component));
        }
        builder(a).setTitle(title)
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("OK", (d, w) -> {
                    List<String> keys = new ArrayList<>();
                    for (int i = 0; i < apps.size(); i++) {
                        if (checked[i]) keys.add(Store.key(apps.get(i).component));
                    }
                    cb.onApps(keys);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    static void askName(Activity a, String title, String initial, NameCallback ok) {
        EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelection(initial.length());
        FrameLayout box = new FrameLayout(a);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        AlertDialog dialog = builder(a).setTitle(title)
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) ok.onName(name);
                })
                .setNegativeButton("Annuler", null)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }
}
