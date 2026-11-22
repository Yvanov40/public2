package fr.minilauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Réglages, ouverts par un appui long sur l'écran d'accueil ou depuis le tiroir d'applis. */
public final class SettingsActivity extends Activity {

    private Store store;
    private Switch bubbleSwitch;
    private float dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        dp = getResources().getDisplayMetrics().density;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        col.setPadding(pad, (int) (40 * dp), pad, pad);

        col.addView(title("Mini Launcher"));

        col.addView(section("Launcher"));
        col.addView(button("Choisir l'écran d'accueil par défaut", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }));
        col.addView(note("Sur l'écran d'accueil : bandes à gauche et à droite = la roue "
                + "(poser le doigt puis glisser jusqu'à l'appli). Glisser vers le bas : à gauche les "
                + "notifications, à droite le centre de contrôle. Glisser vers le haut au milieu : la "
                + "roue. Appui long au milieu : ces réglages."));

        col.addView(section("Roue"));
        col.addView(toggle("Compléter les favoris avec les applis les plus utilisées",
                store.autoFavorites(), on -> store.setFlag("autoFav", on)));
        col.addView(toggle("Le centre rouvre la dernière appli",
                store.centerLast(), on -> store.setFlag("centerLast", on)));
        col.addView(toggle("Une seule appli pour une lettre : l'ouvrir directement",
                store.directSingle(), on -> store.setFlag("directSingle", on)));
        col.addView(note("Appui long sur une appli : épingler en favori, ranger dans un groupe. "
                + "Appui long au centre d'un groupe ouvert : le renommer ou le supprimer."));

        col.addView(section("Bulle flottante"));
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
        col.addView(bubbleSwitch);
        col.addView(note("Poser le doigt sur la bulle ouvre la roue : glisser jusqu'à l'appli et "
                + "lâcher. Maintenir la bulle sans bouger permet de la déplacer."));

        col.addView(section("Position"));
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
        BubbleService.sync(this);
    }

    private TextView title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTextColor(Color.WHITE);
        return t;
    }

    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(0xFF8FA6FF);
        t.setPadding(0, (int) (28 * dp), 0, (int) (6 * dp));
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(0xAAFFFFFF);
        t.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));
        return t;
    }

    interface Toggle {
        void set(boolean on);
    }

    private Switch toggle(String label, boolean value, Toggle onChange) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(Color.WHITE);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setChecked(value);
        s.setPadding(0, (int) (10 * dp), 0, (int) (10 * dp));
        s.setOnCheckedChangeListener((b, on) -> onChange.set(on));
        return s;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        return b;
    }
}
