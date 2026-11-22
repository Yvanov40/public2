package fr.luxmetre;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Affiche la luminosité ambiante mesurée par le capteur de lumière, en lux. */
public class MainActivity extends Activity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor lightSensor;

    private TextView valeur;
    private TextView ambiance;
    private TextView extremes;

    private float min = Float.MAX_VALUE;
    private float max = 0f;
    private String infoCapteur = "";

    private TextView libelleFiltre;
    private Button boutonFiltre;
    private boolean activerApresPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout racine = new LinearLayout(this);
        racine.setOrientation(LinearLayout.VERTICAL);
        racine.setGravity(Gravity.CENTER);
        racine.setBackgroundColor(Color.rgb(0x14, 0x18, 0x20));
        int marge = dp(24);
        racine.setPadding(marge, marge, marge, marge);

        TextView titre = texte("Luminosité ambiante", 20, Color.rgb(0xB0, 0xB8, 0xC8));
        valeur = texte("—", 72, Color.rgb(0xFF, 0xC8, 0x3D));
        valeur.setTypeface(Typeface.DEFAULT_BOLD);
        TextView unite = texte("lux", 24, Color.rgb(0xFF, 0xC8, 0x3D));
        ambiance = texte("", 20, Color.WHITE);
        ambiance.setPadding(0, dp(24), 0, dp(8));
        extremes = texte("", 16, Color.rgb(0xB0, 0xB8, 0xC8));

        Button raz = new Button(this);
        raz.setText("Remettre min / max à zéro");
        raz.setOnClickListener(v -> {
            min = Float.MAX_VALUE;
            max = 0f;
            extremes.setText(infoCapteur);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(32);
        lp.gravity = Gravity.CENTER_HORIZONTAL;

        racine.addView(titre);
        racine.addView(valeur);
        racine.addView(unite);
        racine.addView(ambiance);
        racine.addView(extremes);
        racine.addView(raz, lp);
        ajouterFiltre(racine);

        ScrollView defilement = new ScrollView(this);
        defilement.setFillViewport(true);
        defilement.setBackgroundColor(Color.rgb(0x14, 0x18, 0x20));
        defilement.addView(racine);
        setContentView(defilement);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor == null) {
            valeur.setText("✕");
            unite.setText("");
            ambiance.setText("Cet appareil n'a pas de capteur de luminosité.");
            raz.setEnabled(false);
        } else {
            infoCapteur = "Capteur : " + lightSensor.getName()
                    + "\nprécision " + format(lightSensor.getResolution())
                    + " lux · max " + format(lightSensor.getMaximumRange()) + " lux";
            extremes.setText(infoCapteur);
        }
    }

    /** Curseur et bouton du filtre qui assombrit tout l'écran. */
    private void ajouterFiltre(LinearLayout racine) {
        TextView titre = texte("Assombrir l'écran", 20, Color.WHITE);
        titre.setPadding(0, dp(48), 0, dp(4));
        TextView aide = texte("Voile noir sur tout le téléphone, pour descendre\nsous la luminosité minimale. "
                + "Mettez d'abord la luminosité du téléphone au minimum.", 14, Color.rgb(0x80, 0x88, 0x98));
        libelleFiltre = texte("", 18, Color.rgb(0xFF, 0xC8, 0x3D));
        libelleFiltre.setPadding(0, dp(16), 0, 0);

        SeekBar curseur = new SeekBar(this);
        curseur.setMax(FiltreService.NIVEAU_MAX);
        curseur.setProgress(Reglages.niveau(this));
        curseur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int niveau, boolean parUtilisateur) {
                Reglages.setNiveau(MainActivity.this, niveau);
                majFiltre();
                if (parUtilisateur && FiltreService.actif) FiltreService.regler(MainActivity.this, niveau);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        LinearLayout.LayoutParams lpCurseur = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpCurseur.topMargin = dp(8);

        boutonFiltre = new Button(this);
        boutonFiltre.setOnClickListener(v -> {
            if (FiltreService.actif) {
                FiltreService.arreter(this);
                FiltreService.actif = false;
                majFiltre();
            } else {
                activerFiltre();
            }
        });
        LinearLayout.LayoutParams lpBouton = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpBouton.topMargin = dp(16);
        lpBouton.gravity = Gravity.CENTER_HORIZONTAL;

        racine.addView(titre);
        racine.addView(aide);
        racine.addView(libelleFiltre);
        racine.addView(curseur, lpCurseur);
        racine.addView(boutonFiltre, lpBouton);
        majFiltre();
    }

    private void activerFiltre() {
        if (!Settings.canDrawOverlays(this)) {
            activerApresPermission = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        FiltreService.regler(this, Reglages.niveau(this));
        FiltreService.actif = true;
        Reglages.setActif(this, true);
        majFiltre();
    }

    private void majFiltre() {
        if (libelleFiltre == null) return;
        libelleFiltre.setText("Filtre : " + Reglages.niveau(this) + " %");
        boutonFiltre.setText(FiltreService.actif ? "Désactiver le filtre" : "Activer le filtre");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activerApresPermission) {
            activerApresPermission = false;
            if (Settings.canDrawOverlays(this)) activerFiltre();
        }
        majFiltre();
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (lightSensor != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float lux = event.values[0];
        min = Math.min(min, lux);
        max = Math.max(max, lux);
        valeur.setText(format(lux));
        ambiance.setText(decrire(lux));
        extremes.setText("min " + format(min) + "   ·   max " + format(max) + "\n\n" + infoCapteur);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static String decrire(float lux) {
        if (lux < 1) return "Très faible lumière\n(nuit, on devine les formes)";
        if (lux < 10) return "Pénombre";
        if (lux < 50) return "Faible éclairage";
        if (lux < 200) return "Pièce peu éclairée";
        if (lux < 500) return "Éclairage intérieur";
        if (lux < 1000) return "Bureau / salle de classe bien éclairée";
        if (lux < 10000) return "Lumière du jour à l'ombre";
        if (lux < 30000) return "Plein jour";
        return "Plein soleil";
    }

    private static String format(float lux) {
        if (lux < 1) return String.format(Locale.FRANCE, "%.2f", lux);
        return lux < 10
                ? String.format(Locale.FRANCE, "%.1f", lux)
                : String.format(Locale.FRANCE, "%,.0f", lux);
    }

    private TextView texte(String s, float sp, int couleur) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(couleur);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
