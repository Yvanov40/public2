package fr.minilauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Réglages, ouverts par un appui long sur l'écran d'accueil ou depuis le tiroir d'applis. */
public final class SettingsActivity extends Activity {

    private static final int[] ACCENTS = {
            0xFF8FA6FF, 0xFF6FD3FF, 0xFF7EE0A1, 0xFFB3E36B,
            0xFFFFE07A, 0xFFFFB86B, 0xFFFF8FA3, 0xFFC9A7FF, 0xFFFFFFFF};
    private static final String[] ACCENT_NAMES = {
            "Bleu", "Turquoise", "Vert", "Anis", "Jaune", "Orange", "Rose", "Violet", "Blanc"};

    private static final String[] ZONE_NAMES = {"Sombre", "Noir", "Gris", "Blanc", "Bleu nuit", "Vert forêt", "Bordeaux"};
    private static final int[] ZONE_COLORS = {0x1B1D26, 0x000000, 0x505560, 0xF2F2F2, 0x182040, 0x183424, 0x3A1420};
    private static final String[] LETTER_NAMES = {"Blanc", "Noir", "Gris clair", "Bleu", "Jaune", "Rose"};
    private static final int[] LETTER_COLORS = {0xFFFFFFFF, 0xFF111111, 0xFFBFC4CC, 0xFF8FA6FF, 0xFFFFE07A, 0xFFFF8FA3};

    private Store store;
    private float dp;
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        dp = getResources().getDisplayMetrics().density;

        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        col.setPadding(pad, (int) (40 * dp), pad, pad);

        col.addView(title("Mini Launcher"));

        // ---------------------------------------------------------- launcher
        section("Launcher");
        col.addView(button("Choisir l'écran d'accueil par défaut", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }));
        note("Alphabet sur les bords : poser le doigt sur un bord et le glisser de haut en bas ; les "
                + "applis de la lettre apparaissent à côté. Glisser vers l'intérieur jusqu'à l'appli et "
                + "lâcher. En haut ★ les favoris, en bas les dossiers.");
        choice("Côté de l'alphabet", new String[]{"Les deux", "Gauche", "Droite"}, store.alphaSide(),
                i -> store.setNum(Store.SIDE, i));
        toggle("Lettres toujours visibles sur le bord", store.alphaVisible(),
                on -> store.setFlag(Store.ALPHA_VISIBLE, on));
        slider("Hauteur de l'alphabet", 25, 100, store.stripHeight(), "%d %%",
                v -> store.setNum(Store.STRIP_H, v));
        slider("Position verticale (0 = en haut, 100 = en bas)", 0, 100, store.stripPos(), "%d",
                v -> store.setNum(Store.STRIP_POS, v));
        choice("Effet loupe sur les lettres", new String[]{"Aucun", "Léger", "Normal", "Fort"}, store.zoom(),
                i -> store.setNum(Store.ZOOM, i));
        choiceColor("Couleur de la zone des lettres", ZONE_NAMES, ZONE_COLORS, store.stripBg(),
                c -> store.setNum(Store.STRIP_BG, c));
        slider("Opacité de la zone", 0, 100, store.stripAlpha(), "%d %%", v -> store.setNum(Store.STRIP_ALPHA, v));
        choiceColor("Couleur des lettres", LETTER_NAMES, LETTER_COLORS, store.stripFg(),
                c -> store.setNum(Store.STRIP_FG, c));
        slider("Largeur de la zone tactile des bords", 8, 40, store.band(), "%d %%", v -> store.setNum(Store.BAND, v));
        note("Glisser vers le bas au milieu de l'écran : à gauche les notifications, à droite le "
                + "centre de contrôle. Appui long sur une zone vide : ajouter une appli, un dossier ou "
                + "un widget.");

        // ---------------------------------------------------------- horloge
        section("Horloge");
        toggle("Afficher l'horloge", store.clock(), on -> store.setFlag(Store.CLOCK, on));
        note("Masque-la pour mettre un widget horloge à la place. La toucher ouvre les alarmes.");
        slider("Taille", 30, 110, store.clockSize(), "%d", v -> store.setNum(Store.CLOCK_SIZE, v));
        choice("Position", new String[]{"À gauche", "Au centre", "À droite"}, store.clockAlign(),
                i -> store.setNum(Store.CLOCK_ALIGN, i));
        toggle("Afficher la date", store.clockDate(), on -> store.setFlag(Store.CLOCK_DATE, on));

        // ---------------------------------------------------------- grille
        section("Grille de l'accueil");
        slider("Colonnes", 3, 6, store.cols(), "%d", v -> store.setNum(Store.COLS, v));
        slider("Lignes (0 = automatique)", 0, 9, store.rowsSetting(), "%d", v -> store.setNum(Store.ROWS, v));
        slider("Taille des icônes", 36, 72, store.iconSize(), "%d dp", v -> store.setNum(Store.ICON, v));
        toggle("Afficher le nom des applis", store.labels(), on -> store.setFlag(Store.LABELS, on));

        // ---------------------------------------------------------- favoris et dossiers
        section("Favoris (★) et dossiers");
        toggle("Compléter les favoris avec les applis les plus utilisées",
                store.autoFavorites(), on -> store.setFlag("autoFav", on));
        slider("Nombre de favoris", 0, 8, store.maxFav(), "%d", v -> store.setNum(Store.MAXFAV, v));
        note("Appui long sur une appli : épingler en favori, ajouter à un dossier, poser sur "
                + "l'accueil. Sur l'accueil, lâcher une appli sur une autre crée un dossier. Poser le "
                + "doigt sur un dossier l'ouvre en cercle : glisser jusqu'à l'appli et lâcher.");

        // ---------------------------------------------------------- dossiers
        section("Dossiers");
        note("Pour un dossier en particulier (couleur, style, forme) : maintenir le dossier puis "
                + "lâcher sur place > Personnaliser.");
        choiceColor("Fond du cercle", ZONE_NAMES, ZONE_COLORS, store.folderBg(),
                c -> store.setNum(Store.F_BG, c));
        slider("Opacité du fond du cercle", 0, 100, store.folderBgAlpha(), "%d %%",
                v -> store.setNum(Store.F_BG_ALPHA, v));
        slider("Taille des icônes dans le cercle", 34, 64, store.folderIcon(), "%d dp",
                v -> store.setNum(Store.F_ICON, v));
        slider("Taille du cercle", 70, 140, store.folderRadius(), "%d %%",
                v -> store.setNum(Store.F_RADIUS, v));
        toggle("Noms des applis dans le cercle", store.folderLabels(), on -> store.setFlag(Store.F_LABELS, on));
        toggle("Bordure de couleur sur l'icône", store.folderBorder(), on -> store.setFlag(Store.F_BORDER, on));
        slider("Opacité du fond de l'icône", 0, 100, store.folderTile(), "%d %%",
                v -> store.setNum(Store.F_TILE_ALPHA, v));

        // ---------------------------------------------------------- effets
        section("Effets");
        accentChoice();
        slider("Assombrissement du fond", 0, 90, store.dimPercent(), "%d %%", v -> store.setNum(Store.DIM, v));
        choice("Animations", new String[]{"Aucune", "Normales", "Rapides", "Lentes"}, store.speed(),
                i -> store.setNum(Store.SPEED, i));
        toggle("Vibrations", store.haptic(), on -> store.setFlag(Store.HAPTIC, on));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    // ------------------------------------------------------------ éléments

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTextColor(Color.WHITE);
        return t;
    }

    private void section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(0xFF8FA6FF);
        t.setPadding(0, (int) (28 * dp), 0, (int) (6 * dp));
        col.addView(t);
    }

    private void note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(0xAAFFFFFF);
        t.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        col.addView(t);
    }

    interface Toggle {
        void set(boolean on);
    }

    interface IntSetter {
        void set(int v);
    }

    private Switch toggle(String label, boolean value, Toggle onChange) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Color.WHITE);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(value);
        s.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        s.setOnCheckedChangeListener((b, on) -> onChange.set(on));
        col.addView(s);
        return s;
    }

    private void slider(String label, int min, int max, int value, String format, IntSetter onChange) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, (int) (10 * dp), 0, 0);
        t.setText(label + " : " + String.format(format, value));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                t.setText(label + " : " + String.format(format, p + min));
                if (fromUser) onChange.set(p + min);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        col.addView(t);
        col.addView(bar);
    }

    private void choice(String label, String[] options, int index, IntSetter onChange) {
        Button b = button(label + " : " + options[Math.max(0, Math.min(options.length - 1, index))], null);
        b.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setItems(options, (d, which) -> {
                    onChange.set(which);
                    b.setText(label + " : " + options[which]);
                }).show());
        col.addView(b);
    }

    private void choiceColor(String label, String[] names, int[] colors, int current, IntSetter onChange) {
        Button b = button(label, null);
        updateSwatch(b, 0xFF000000 | current);
        b.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(label)
                .setItems(names, (d, which) -> {
                    onChange.set(colors[which]);
                    updateSwatch(b, 0xFF000000 | colors[which]);
                }).show());
        col.addView(b);
    }

    private void accentChoice() {
        Button b = button("Couleur d'accent", null);
        updateSwatch(b, store.accent());
        b.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Couleur d'accent")
                .setItems(ACCENT_NAMES, (d, which) -> {
                    store.setNum(Store.ACCENT, ACCENTS[which]);
                    updateSwatch(b, ACCENTS[which]);
                }).show());
        col.addView(b);
    }

    private void updateSwatch(Button b, int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        int size = (int) (18 * dp);
        dot.setSize(size, size);
        b.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, dot, null);
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        if (l != null) b.setOnClickListener(l);
        return b;
    }
}
