package fr.minilauncher.badges;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Un seul écran : expliquer, et ouvrir le réglage « Accès aux notifications ». */
public final class MainActivity extends Activity {

    private TextView status, button;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float dp = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(0xFF0F1015);
        getWindow().setNavigationBarColor(0xFF0F1015);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF0F1015);
        int p = (int) (24 * dp);
        col.setPadding(p, (int) (48 * dp), p, p);

        TextView title = text("Pastilles", 30, 0xFFF2F3F7);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        col.addView(title);
        TextView sub = text("pour Mini Launcher", 15, 0xFF8C92A3);
        sub.setPadding(0, (int) (2 * dp), 0, (int) (24 * dp));
        col.addView(sub);

        col.addView(text("Cette petite appli compte les notifications de chaque appli et envoie "
                + "seulement ces nombres à Mini Launcher, qui affiche les pastilles. Le contenu des "
                + "notifications n'est ni lu ni gardé.", 15, 0xFFC9CDD6));

        status = text("", 16, 0xFFF2F3F7);
        status.setPadding(0, (int) (28 * dp), 0, (int) (14 * dp));
        col.addView(status);

        button = text("", 16, 0xFF111320);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(p, (int) (14 * dp), p, (int) (14 * dp));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF8FA6FF);
        g.setCornerRadius(26 * dp);
        button.setBackground(g);
        button.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        col.addView(button);

        TextView note = text("Si « Mini Launcher – Pastilles » est grisé dans la liste : Paramètres > "
                + "Applis > Mini Launcher – Pastilles > ⋮ > « Autoriser les paramètres restreints », "
                + "puis réessaie.", 13, 0xFF8C92A3);
        note.setPadding(0, (int) (18 * dp), 0, 0);
        col.addView(note);
        setContentView(col);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = granted();
        status.setText(on ? "Accès aux notifications : activé ✓" : "Accès aux notifications : désactivé");
        button.setText(on ? "Gérer l'accès" : "Activer l'accès aux notifications");
    }

    private boolean granted() {
        ComponentName me = new ComponentName(this, BadgeListener.class);
        if (Build.VERSION.SDK_INT >= 27) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) return nm.isNotificationListenerAccessGranted(me);
        }
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(me.flattenToString());
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }
}
