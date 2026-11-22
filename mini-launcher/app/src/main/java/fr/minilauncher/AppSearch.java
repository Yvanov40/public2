package fr.minilauncher;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recherche d'applis au clavier : le clavier est déjà sorti, les résultats suivent chaque
 * lettre, Entrée ouvre la première appli trouvée.
 */
final class AppSearch {

    private static final int MAX = 8;

    private AppSearch() {
    }

    static void show(Activity a, List<AppRepository.AppEntry> apps) {
        Store store = Store.get(a);
        Map<String, Integer> usage = new HashMap<>();
        for (Map.Entry<String, Integer> u : store.usage()) usage.put(u.getKey(), u.getValue());

        Sheet s = new Sheet(a, null);
        EditText q = s.addSearch("Rechercher une appli");
        q.setImeOptions(EditorInfo.IME_ACTION_GO);
        List<AppRepository.AppEntry> shown = new ArrayList<>();

        Runnable update = () -> {
            s.content.removeAllViews();
            shown.clear();
            String query = Sheet.fold(q.getText().toString().trim());
            if (query.isEmpty()) {
                for (Object[] r : Recents.load(a, apps)) {
                    if (shown.size() >= MAX) break;
                    shown.add((AppRepository.AppEntry) r[0]);
                }
            } else {
                shown.addAll(rank(apps, query, usage));
            }
            for (int i = 0; i < shown.size(); i++) {
                AppRepository.AppEntry app = shown.get(i);
                if (i > 0) s.addDivider();
                Sheet.Item it = new Sheet.Item(app.label);
                it.icon = app.icon;
                if (i == 0 && !query.isEmpty()) {
                    it.selected = true;           // ouverte par Entrée
                    it.subtitle = "Entrée pour ouvrir";
                }
                View row = s.row(it);
                row.setOnClickListener(v -> open(a, s, app));
                s.content.addView(row);
            }
            if (!query.isEmpty() && shown.isEmpty()) {
                Sheet.Item web = new Sheet.Item("Rechercher « " + q.getText().toString().trim() + " » sur le web");
                web.subtitle = "Aucune appli ne correspond";
                View row = s.row(web);
                row.setOnClickListener(v -> web(a, s, q.getText().toString().trim()));
                s.content.addView(row);
            }
        };

        q.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int st, int n, int af) {
            }

            @Override
            public void onTextChanged(CharSequence c, int st, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                update.run();
            }
        });
        q.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN);
            if (!enter) return false;
            String text = q.getText().toString().trim();
            if (!shown.isEmpty() && !text.isEmpty()) open(a, s, shown.get(0));
            else if (!text.isEmpty()) web(a, s, text);
            return true;
        });

        update.run();
        s.show();
        if (s.dialog.getWindow() != null) {
            s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        q.requestFocus();
    }

    /**
     * Classement : le nom commence par la recherche, puis un mot du nom, puis les initiales
     * (« gm » → Google Maps), puis le nom la contient. À égalité : les plus utilisées d'abord.
     */
    static List<AppRepository.AppEntry> rank(List<AppRepository.AppEntry> apps, String query,
                                             Map<String, Integer> usage) {
        List<Object[]> scored = new ArrayList<>();
        for (AppRepository.AppEntry e : apps) {
            String label = Sheet.fold(e.label);
            int score;
            if (label.startsWith(query)) score = 0;
            else if (wordStarts(label, query)) score = 1;
            else if (initials(label).startsWith(query)) score = 2;
            else if (label.contains(query)) score = 3;
            else continue;
            Integer u = usage.get(Store.key(e.component));
            scored.add(new Object[]{e, score, u == null ? 0 : u});
        }
        scored.sort((x, y) -> {
            int c = Integer.compare((Integer) x[1], (Integer) y[1]);
            if (c != 0) return c;
            return Integer.compare((Integer) y[2], (Integer) x[2]);
        });
        List<AppRepository.AppEntry> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX, scored.size()); i++) out.add((AppRepository.AppEntry) scored.get(i)[0]);
        return out;
    }

    private static boolean wordStarts(String label, String q) {
        for (String w : label.split("[\\s\\-_.:]+")) if (w.startsWith(q)) return true;
        return false;
    }

    private static String initials(String label) {
        StringBuilder b = new StringBuilder();
        for (String w : label.split("[\\s\\-_.:]+")) if (!w.isEmpty()) b.append(w.charAt(0));
        return b.toString();
    }

    private static void open(Activity a, Sheet s, AppRepository.AppEntry app) {
        s.dialog.dismiss();
        Launcher.launch(a, app, null, null);
    }

    private static void web(Activity a, Sheet s, String text) {
        s.dialog.dismiss();
        try {
            a.startActivity(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text));
        } catch (ActivityNotFoundException e) {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(text))));
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }
}
