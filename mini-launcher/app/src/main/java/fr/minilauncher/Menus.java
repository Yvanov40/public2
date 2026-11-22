package fr.minilauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Menus de l'appui long : épingler, groupes, infos.
 * {@code changed} est appelé après une modification ; {@code done} quand le menu est fini
 * (avec ou sans modification).
 */
final class Menus {

    private Menus() {
    }

    private static AlertDialog.Builder builder(Activity a) {
        return new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    static void appMenu(Activity a, ComponentName cn, String label, String group,
                        Runnable changed, Runnable done) {
        Store store = Store.get(a);
        String key = Store.key(cn);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        labels.add(store.isPinned(key) ? "Retirer des favoris" : "Épingler en favori");
        actions.add(() -> {
            store.togglePin(key);
            changed.run();
            done.run();
        });
        labels.add("Ajouter à un groupe…");
        actions.add(() -> chooseGroup(a, key, changed, done));
        if (group != null) {
            labels.add("Retirer du groupe « " + group + " »");
            actions.add(() -> {
                List<Store.Group> groups = store.groups();
                Store.Group g = Store.find(groups, group);
                if (g != null) {
                    g.apps.remove(key);
                    if (g.apps.isEmpty()) groups.remove(g);
                    store.setGroups(groups);
                }
                changed.run();
                done.run();
            });
        }
        labels.add("Infos de l'appli");
        actions.add(() -> {
            Launcher.appInfo(a, cn);
            done.run();
        });

        builder(a).setTitle(label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setOnCancelListener(d -> done.run())
                .show();
    }

    private static void chooseGroup(Activity a, String key, Runnable changed, Runnable done) {
        Store store = Store.get(a);
        List<Store.Group> groups = store.groups();
        List<String> names = new ArrayList<>();
        for (Store.Group g : groups) names.add((g.apps.contains(key) ? "✓ " : "") + g.name);
        names.add("＋ Nouveau groupe…");
        builder(a).setTitle("Ajouter au groupe")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == groups.size()) {
                        askName(a, "Nouveau groupe", "", name -> {
                            List<Store.Group> gs = store.groups();
                            Store.Group g = Store.find(gs, name);
                            if (g == null) {
                                g = new Store.Group(name, Store.GROUP_COLORS[gs.size() % Store.GROUP_COLORS.length]);
                                gs.add(g);
                            }
                            if (!g.apps.contains(key)) g.apps.add(key);
                            store.setGroups(gs);
                            changed.run();
                        }, done);
                    } else {
                        Store.Group g = groups.get(which);
                        if (!g.apps.contains(key)) g.apps.add(key);
                        store.setGroups(groups);
                        changed.run();
                        done.run();
                    }
                })
                .setOnCancelListener(d -> done.run())
                .show();
    }

    static void groupMenu(Activity a, String group, Runnable changed, Runnable done) {
        Store store = Store.get(a);
        builder(a).setTitle(group)
                .setItems(new String[]{"Renommer", "Supprimer le groupe"}, (d, which) -> {
                    if (which == 0) {
                        askName(a, "Renommer le groupe", group, name -> {
                            List<Store.Group> gs = store.groups();
                            Store.Group g = Store.find(gs, group);
                            if (g != null && Store.find(gs, name) == null) {
                                g.name = name;
                                store.setGroups(gs);
                            }
                            changed.run();
                        }, done);
                    } else {
                        List<Store.Group> gs = store.groups();
                        Store.Group g = Store.find(gs, group);
                        if (g != null) gs.remove(g);
                        store.setGroups(gs);
                        changed.run();
                        done.run();
                    }
                })
                .setOnCancelListener(d -> done.run())
                .show();
    }

    interface NameCallback {
        void onName(String name);
    }

    private static void askName(Activity a, String title, String initial, NameCallback ok, Runnable done) {
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
                    done.run();
                })
                .setNegativeButton("Annuler", (d, w) -> done.run())
                .setOnCancelListener(d -> done.run())
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }
}
