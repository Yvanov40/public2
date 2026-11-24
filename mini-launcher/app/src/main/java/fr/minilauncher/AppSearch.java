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

        boolean grid = store.searchGrid();
        int pos = store.searchPos();
        Sheet s = new Sheet(a, null, pos);
        EditText q = s.addSearch("⌕   Rechercher une appli");
        int max = grid ? (pos == Sheet.CENTER ? 8 : 12) : MAX;
        // Une seule appli trouvée : elle s'ouvre après une courte pause dans la frappe.
        Runnable[] autoOpen = {null};
        q.setImeOptions(EditorInfo.IME_ACTION_GO);
        List<AppRepository.AppEntry> shown = new ArrayList<>();

        Runnable update = () -> {
            s.content.removeAllViews();
            shown.clear();
            String query = Sheet.fold(q.getText().toString().trim());
            if (query.isEmpty()) {
                for (Object[] r : Recents.load(a, apps)) {
                    if (shown.size() >= max) break;
                    shown.add((AppRepository.AppEntry) r[0]);
                }
            } else {
                List<AppRepository.AppEntry> found = rank(apps, query, usage);
                shown.addAll(found.subList(0, Math.min(max, found.size())));
                if (autoOpen[0] != null) q.removeCallbacks(autoOpen[0]);
                if (found.size() == 1 && store.searchAutoOpen()) {
                    AppRepository.AppEntry only = found.get(0);
                    String typed = q.getText().toString();
                    autoOpen[0] = () -> {
                        if (s.dialog.isShowing() && q.getText().toString().equals(typed)) open(a, s, only);
                    };
                    q.postDelayed(autoOpen[0], 450);
                }
            }
            if (grid) {
                grid(a, s, shown, !query.isEmpty());
            } else for (int i = 0; i < shown.size(); i++) {
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
                row.setOnLongClickListener(v -> place(a, s, app));
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
        closeWithKeyboard(s);
    }

    /** Le clavier redescend (Retour, flèche du clavier, glissé) : la recherche se ferme aussi. */
    private static void closeWithKeyboard(Sheet s) {
        if (s.dialog.getWindow() == null) return;
        View decor = s.dialog.getWindow().getDecorView();
        boolean[] seen = {false};
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            decor.setOnApplyWindowInsetsListener((v, insets) -> {
                boolean visible = insets.isVisible(android.view.WindowInsets.Type.ime());
                if (visible) seen[0] = true;
                else if (seen[0] && s.dialog.isShowing()) s.dialog.dismiss();
                return v.onApplyWindowInsets(insets);
            });
        } else {
            int screen = decor.getResources().getDisplayMetrics().heightPixels;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                android.graphics.Rect r = new android.graphics.Rect();
                decor.getWindowVisibleDisplayFrame(r);
                boolean visible = screen - r.bottom > screen * 0.15f;
                if (visible) seen[0] = true;
                else if (seen[0] && s.dialog.isShowing()) s.dialog.dismiss();
            });
        }
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
        for (int i = 0; i < Math.min(12, scored.size()); i++) out.add((AppRepository.AppEntry) scored.get(i)[0]);
        return out;
    }

    /** Grille d'icônes (4 par ligne), la première mise en avant quand on a tapé quelque chose. */
    private static void grid(Activity a, Sheet s, List<AppRepository.AppEntry> apps, boolean highlightFirst) {
        int cols = 4;
        android.widget.LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % cols == 0) {
                row = new android.widget.LinearLayout(a);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setPadding(s.px(10), 0, s.px(10), 0);
                s.content.addView(row);
            }
            AppRepository.AppEntry app = apps.get(i);
            android.widget.LinearLayout cell = new android.widget.LinearLayout(a);
            cell.setOrientation(android.widget.LinearLayout.VERTICAL);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.setPadding(s.px(2), s.px(8), s.px(2), s.px(8));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(s.px(18));
            bg.setColor(i == 0 && highlightFirst ? ((s.accent & 0x00FFFFFF) | 0x33000000) : 0);
            android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
            mask.setCornerRadius(s.px(18));
            mask.setColor(0xFFFFFFFF);
            cell.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Sheet.RIPPLE), bg, mask));
            android.widget.ImageView icon = new android.widget.ImageView(a);
            icon.setImageDrawable(Sheet.copy(app.icon));
            cell.addView(icon, new android.widget.LinearLayout.LayoutParams(s.px(40), s.px(40)));
            android.widget.TextView name = s.text(app.label, 11, Sheet.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(android.view.Gravity.CENTER);
            name.setPadding(0, s.px(6), 0, 0);
            cell.addView(name, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
            cell.setOnClickListener(v -> open(a, s, app));
            cell.setOnLongClickListener(v -> place(a, s, app));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(s.px(2), s.px(2), s.px(2), s.px(2));
            row.addView(cell, lp);
        }
        // Cases vides pour garder la largeur des colonnes sur la dernière ligne.
        if (row != null) {
            for (int k = apps.size() % cols; k != 0 && k < cols; k++) {
                row.addView(new View(a), new android.widget.LinearLayout.LayoutParams(0, 1, 1f));
            }
        }
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

    /** Maintenue : la recherche se ferme et l'appli attend d'être posée sur l'accueil. */
    private static boolean place(Activity a, Sheet s, AppRepository.AppEntry app) {
        if (!(a instanceof HomeActivity)) return false;
        s.dialog.dismiss();
        ((HomeActivity) a).placeOnHome(app);
        return true;
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
