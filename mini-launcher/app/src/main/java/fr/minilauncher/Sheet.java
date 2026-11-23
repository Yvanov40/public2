package fr.minilauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Panneau qui monte du bas de l'écran, dans le style des réglages : titre, liste de lignes
 * (avec icône, pastille de couleur, coche, ou en rouge), recherche facultative.
 * Remplace les listes Android par défaut.
 */
final class Sheet {

    // Couleurs du thème choisi (Réglages > Panneaux), mises à jour à chaque ouverture.
    static int CARD = 0xFF1A1C23;
    static int TEXT = 0xFFF2F3F7;
    static int MUTED = 0xFF8C92A3;
    static int DIVIDER = 0xFF272A34;
    static int FIELD = 0xFF242730;   // champ de saisie, cartes
    static int HANDLE = 0xFF3A3D48;  // poignée, fond de case
    static int SUBTLE = 0xFF5C6273;  // chevrons, contours
    static int RIPPLE = 0x22FFFFFF;
    static final int DANGER = 0xFFFF8F8F;

    static final String[] THEMES = {"Sombre", "Noir", "Anthracite", "Bleu nuit", "Clair"};
    private static final int[][] PALETTES = {
            // fond, texte, secondaire, séparateur, champ, poignée, discret
            {0x1A1C23, 0xFFF2F3F7, 0xFF8C92A3, 0xFF272A34, 0xFF242730, 0xFF3A3D48, 0xFF5C6273},
            {0x000000, 0xFFF2F3F7, 0xFF8C92A3, 0xFF1E1F24, 0xFF16171B, 0xFF33353D, 0xFF5C6273},
            {0x2A2C33, 0xFFF2F3F7, 0xFF9AA0B0, 0xFF383B45, 0xFF34373F, 0xFF4A4D58, 0xFF6A7080},
            {0x141B33, 0xFFEEF1FA, 0xFF8E98B8, 0xFF222B48, 0xFF1E2641, 0xFF34406A, 0xFF5A668E},
            {0xF6F7FA, 0xFF15171C, 0xFF6B7080, 0xFFE2E4EA, 0xFFE9EBF0, 0xFFC9CCD5, 0xFF9AA0AE}};

    static void applyTheme(Store st) {
        int[] p = PALETTES[Math.max(0, Math.min(PALETTES.length - 1, st.panelTheme()))];
        int alpha = (int) (255 * Math.max(50, Math.min(100, st.panelAlpha())) / 100f);
        CARD = (alpha << 24) | p[0];
        TEXT = p[1];
        MUTED = p[2];
        DIVIDER = p[3];
        FIELD = p[4];
        HANDLE = p[5];
        SUBTLE = p[6];
        RIPPLE = st.panelTheme() == 4 ? 0x1A000000 : 0x22FFFFFF;
    }

    /** Panneaux ouverts : le bouton Accueil les ferme tous. */
    private static final List<java.lang.ref.WeakReference<Sheet>> OPEN = new ArrayList<>();

    static void dismissAll() {
        for (java.lang.ref.WeakReference<Sheet> r : new ArrayList<>(OPEN)) {
            Sheet s = r.get();
            if (s != null && s.dialog.isShowing()) {
                try {
                    s.dialog.dismiss();
                } catch (RuntimeException ignored) {
                }
            }
        }
        OPEN.clear();
    }

    interface IntCallback {
        void on(int index);
    }

    /** Une ligne de la liste. */
    static final class Item {
        final String label;
        String subtitle;
        Drawable icon;
        int swatch;          // 0 = pas de pastille
        boolean danger, checked, selected;
        Runnable action;

        Item(String label) {
            this.label = label;
        }
    }

    final Activity activity;
    final Dialog dialog;
    final float dp;
    final int accent;
    final LinearLayout panel;
    final LinearLayout content;
    private final ScrollView scroll;

    /** Défilement limité en hauteur : le panneau ne dépasse jamais ~80 % de l'écran. */
    private static final class MaxScroll extends ScrollView {
        int max;

        MaxScroll(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
        }
    }

    final boolean top; // panneau en haut de l'écran (recherche) au lieu d'en bas

    Sheet(Activity a, String title) {
        this(a, title, false);
    }

    Sheet(Activity a, String title, boolean top) {
        activity = a;
        this.top = top;
        dp = a.getResources().getDisplayMetrics().density;
        Store st = Store.get(a);
        applyTheme(st);
        accent = st.accent();
        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        float r = st.panelRadius() * dp;
        bg.setCornerRadii(top ? new float[]{0, 0, 0, 0, r, r, r, r} : new float[]{r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setPadding(0, top ? px(44) : px(10), 0, px(16));

        if (!top) {
            // Poignée
            View handle = new View(a);
            GradientDrawable hb = new GradientDrawable();
            hb.setColor(HANDLE);
            hb.setCornerRadius(px(3));
            handle.setBackground(hb);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(px(38), px(5));
            hl.gravity = Gravity.CENTER_HORIZONTAL;
            hl.bottomMargin = px(10);
            panel.addView(handle, hl);
        }

        if (title != null) {
            TextView t = text(title, 19, TEXT);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(px(22), px(4), px(22), px(10));
            panel.addView(t);
        }

        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        MaxScroll ms = new MaxScroll(a);
        ms.max = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.80f) - px(150);
        scroll = ms;
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    int px(float v) {
        return (int) (v * dp);
    }

    TextView text(String s, float sp, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static void ripple(View v) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), null, mask));
    }

    /** Champ de recherche en haut du panneau. */
    EditText addSearch(String hint) {
        EditText e = new EditText(activity);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(FIELD);
        g.setCornerRadius(px(14));
        e.setBackground(g);
        e.setPadding(px(16), px(12), px(16), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px(18), 0, px(18), px(10));
        panel.addView(e, panel.indexOfChild(scroll), lp);
        return e;
    }

    /** Une ligne standard. */
    View row(Item it) {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(56));
        r.setPadding(px(22), px(8), px(20), px(8));
        if (it.icon != null) {
            ImageView iv = new ImageView(activity);
            iv.setImageDrawable(it.icon);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(36), px(36));
            lp.rightMargin = px(16);
            r.addView(iv, lp);
        } else if (it.swatch != 0) {
            View dot = new View(activity);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(it.swatch);
            g.setStroke(px(1), 0x33FFFFFF);
            dot.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(26), px(26));
            lp.rightMargin = px(16);
            r.addView(dot, lp);
        }
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(it.label, 16, it.danger ? DANGER : it.selected ? accent : TEXT);
        texts.addView(t);
        if (it.subtitle != null) {
            TextView s = text(it.subtitle, 13, MUTED);
            s.setPadding(0, px(2), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (it.selected) {
            TextView mark = text("✓", 18, accent);
            r.addView(mark);
        }
        ripple(r);
        return r;
    }

    /** Coche ronde pour les choix multiples. */
    View checkRow(Item it) {
        LinearLayout r = (LinearLayout) row(it);
        View box = new View(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(24), px(24));
        lp.leftMargin = px(12);
        r.addView(box, lp);
        Runnable paint = () -> {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if (it.checked) {
                g.setColor(accent);
            } else {
                g.setColor(Color.TRANSPARENT);
                g.setStroke(px(2), SUBTLE);
            }
            box.setBackground(g);
        };
        paint.run();
        r.setOnClickListener(v -> {
            it.checked = !it.checked;
            paint.run();
        });
        return r;
    }

    void addDivider() {
        View line = new View(activity);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, px(0.7f)));
        lp.leftMargin = px(22);
        content.addView(line, lp);
    }

    /** Boutons en bas (Annuler / OK). */
    void addButtons(String ok, Runnable onOk) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.END);
        bar.setPadding(px(16), px(10), px(16), 0);
        bar.addView(button("Annuler", MUTED, false, v -> dialog.dismiss()));
        bar.addView(button(ok, 0xFF111320, true, v -> {
            dialog.dismiss();
            onOk.run();
        }));
        panel.addView(bar);
    }

    private TextView button(String label, int color, boolean filled, View.OnClickListener l) {
        TextView b = text(label, 15, color);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(px(20), px(11), px(20), px(11));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(22));
        g.setColor(filled ? accent : Color.TRANSPARENT);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(px(22));
        mask.setColor(Color.WHITE);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, mask));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = px(8);
        b.setLayoutParams(lp);
        return b;
    }

    void show() {
        FrameLayout root = new FrameLayout(activity);
        root.addView(panel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, top ? Gravity.TOP : Gravity.BOTTOM));
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(top ? Gravity.TOP : Gravity.BOTTOM);
            w.setWindowAnimations(top ? android.R.style.Animation_Dialog : android.R.style.Animation_InputMethod);
            if (top) {
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
                w.setStatusBarColor(Color.TRANSPARENT);
            }
            w.setDimAmount(0.55f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        OPEN.add(new java.lang.ref.WeakReference<>(this));
        dialog.setOnDismissListener(d -> {
            for (int i = OPEN.size() - 1; i >= 0; i--) if (OPEN.get(i).get() == this) OPEN.remove(i);
        });
        dialog.show();
    }

    // ------------------------------------------------------------ raccourcis

    /** Liste simple : un toucher choisit et ferme. */
    static void list(Activity a, String title, String[] labels, IntCallback cb) {
        List<Item> items = new ArrayList<>();
        for (String l : labels) items.add(new Item(l));
        list(a, title, items, cb);
    }

    static void list(Activity a, String title, List<Item> items, IntCallback cb) {
        Sheet s = new Sheet(a, title);
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) s.addDivider();
            View r = s.row(items.get(i));
            int index = i;
            r.setOnClickListener(v -> {
                s.dialog.dismiss();
                cb.on(index);
            });
            s.content.addView(r);
        }
        s.show();
    }

    /** Liste avec pastilles de couleur. */
    static void colors(Activity a, String title, String[] names, int[] colors, int current, IntCallback cb) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Item it = new Item(names[i]);
            it.swatch = 0xFF000000 | colors[i];
            it.selected = (0xFFFFFF & colors[i]) == (0xFFFFFF & current);
            items.add(it);
        }
        list(a, title, items, cb);
    }

    /** Liste d'applis avec icônes et recherche. */
    static void apps(Activity a, String title, List<AppRepository.AppEntry> apps, Menus.AppCallback cb) {
        Sheet s = new Sheet(a, title);
        EditText search = s.addSearch("Rechercher");
        List<View> rows = new ArrayList<>();
        for (AppRepository.AppEntry e : apps) {
            Item it = new Item(e.label);
            it.icon = e.icon;
            View r = s.row(it);
            r.setTag(fold(e.label));
            r.setOnClickListener(v -> {
                s.dialog.dismiss();
                cb.onApp(e);
            });
            rows.add(r);
            s.content.addView(r);
        }
        filterOn(search, rows);
        s.show();
    }

    /** Choix multiple d'applis (cases rondes), avec recherche. */
    static void pickApps(Activity a, String title, List<AppRepository.AppEntry> apps, List<String> selected,
                         Menus.AppsCallback cb) {
        Sheet s = new Sheet(a, title);
        EditText search = s.addSearch("Rechercher");
        List<Item> items = new ArrayList<>();
        List<View> rows = new ArrayList<>();
        for (AppRepository.AppEntry e : apps) {
            Item it = new Item(e.label);
            it.icon = e.icon;
            it.checked = selected.contains(Store.key(e.component));
            items.add(it);
            View r = s.checkRow(it);
            r.setTag(fold(e.label));
            rows.add(r);
            s.content.addView(r);
        }
        filterOn(search, rows);
        s.addButtons("OK", () -> {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < apps.size(); i++) if (items.get(i).checked) keys.add(Store.key(apps.get(i).component));
            cb.onApps(keys);
        });
        s.show();
    }

    /** Saisie d'un nom. */
    static void askName(Activity a, String title, String initial, Menus.NameCallback ok) {
        Sheet s = new Sheet(a, title);
        EditText input = new EditText(a);
        input.setText(initial);
        input.setSelection(initial.length());
        input.setTextColor(TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        GradientDrawable g = new GradientDrawable();
        g.setColor(FIELD);
        g.setCornerRadius(s.px(14));
        input.setBackground(g);
        input.setPadding(s.px(16), s.px(14), s.px(16), s.px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(s.px(18), s.px(4), s.px(18), s.px(4));
        s.content.addView(input, lp);
        s.addButtons("OK", () -> {
            String name = input.getText().toString().trim();
            if (!name.isEmpty()) ok.onName(name);
        });
        s.show();
        if (s.dialog.getWindow() != null) {
            s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        input.requestFocus();
    }

    /** Filtre les lignes (étiquette = texte sans accents en minuscules) selon la recherche. */
    static void filterOn(EditText search, List<View> rows) {
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = fold(s.toString());
                for (View r : rows) {
                    Object tag = r.getTag();
                    r.setVisibility(q.isEmpty() || (tag instanceof String && ((String) tag).contains(q))
                            ? View.VISIBLE : View.GONE);
                }
            }
        });
    }

    static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
