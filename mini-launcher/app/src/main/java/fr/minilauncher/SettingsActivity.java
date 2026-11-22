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
    private Button shadeButton;
    private Switch bubbleSwitch;
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
        choice("Bords de l'écran", new String[]{"Alphabet vertical", "Roue"}, store.edgeMode(),
                i -> store.setNum(Store.EDGE, i));
        note("Alphabet vertical : poser le doigt sur un bord et le glisser de haut en bas ; les "
                + "applis de la lettre apparaissent à côté. Glisser vers l'intérieur jusqu'à l'appli et "
                + "lâcher. En haut ★ les favoris, en bas les groupes. Roue : la roue s'ouvre sous le doigt.");
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
                + "centre de contrôle. Appui long sur une zone vide : ajouter une appli ou un widget.");
        shadeButton = button("", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                Toast.makeText(this, "Active « Mini Launcher – volets »", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException ignored) {
            }
        });
        col.addView(shadeButton);
        note("Si le volet s'ouvre mal (écran à moitié vide), active ce service : Android ouvre alors "
                + "les notifications et le centre de contrôle par sa méthode officielle. S'il est grisé "
                + "(appli installée hors Play Store) : Paramètres > Applis > Mini Launcher > ⋮ > "
                + "« Autoriser les paramètres restreints », puis réessaie.");

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

        // ---------------------------------------------------------- roue
        section("Roue");
        toggle("Compléter les favoris avec les applis les plus utilisées",
                store.autoFavorites(), on -> store.setFlag("autoFav", on));
        slider("Nombre de favoris", 0, 8, store.maxFav(), "%d", v -> store.setNum(Store.MAXFAV, v));
        toggle("Le centre rouvre la dernière appli",
                store.centerLast(), on -> store.setFlag("centerLast", on));
        toggle("Une seule appli pour une lettre : l'ouvrir directement",
                store.directSingle(), on -> store.setFlag("directSingle", on));
        note("Appui long sur une appli : épingler en favori, ranger dans un groupe, poser sur "
                + "l'accueil. Appui long au centre d'un groupe ouvert : le renommer ou le supprimer.");

        // ---------------------------------------------------------- effets
        section("Effets");
        accentChoice();
        slider("Taille de la roue", 80, 130, store.wheelScale(), "%d %%", v -> store.setNum(Store.WHEEL, v));
        slider("Assombrissement du fond", 0, 90, store.dimPercent(), "%d %%", v -> store.setNum(Store.DIM, v));
        choice("Animations", new String[]{"Aucune", "Normales", "Rapides", "Lentes"}, store.speed(),
                i -> store.setNum(Store.SPEED, i));
        slider("Temps d'arrêt pour ouvrir une lettre", 150, 800, store.dwell(), "%d ms",
                v -> store.setNum(Store.DWELL, v));
        toggle("Vibrations", store.haptic(), on -> store.setFlag(Store.HAPTIC, on));

        // ---------------------------------------------------------- bulle
        section("Bulle flottante");
        bubbleSwitch = toggle("Afficher la bulle par-dessus les applis", store.bubble(), on -> {
            store.setFlag("bubble", on);
            if (on && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Autorise « Afficher par-dessus les autres applis »",
                        Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
            if (on && Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
            BubbleService.sync(this);
        });
        note("Poser le doigt sur la bulle ouvre la roue : glisser jusqu'à l'appli et lâcher. "
                + "Maintenir la bulle sans bouger permet de la déplacer.");

        section("Position");
        col.addView(button("Remettre la roue de l'icône au milieu", v -> {
            store.clearPosition();
            Toast.makeText(this, "Position effacée", Toast.LENGTH_SHORT).show();
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Retour de l'écran d'autorisation : on démarre la bulle si c'est maintenant possible.
        if (store.bubble() && !Settings.canDrawOverlays(this)) bubbleSwitch.setChecked(false);
        shadeButton.setText(ShadeService.isRunning()
                ? "Ouverture des volets : méthode officielle activée ✓"
                : "Activer l'ouverture fiable des volets (accessibilité)");
        BubbleService.sync(this);
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
        Button b = button("Couleur de la roue", null);
        updateSwatch(b, store.accent());
        b.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Couleur de la roue")
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
