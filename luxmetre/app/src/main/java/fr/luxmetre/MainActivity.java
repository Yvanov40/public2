package fr.luxmetre;

import android.app.Activity;
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
            extremes.setText("");
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
        setContentView(racine);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor == null) {
            valeur.setText("✕");
            unite.setText("");
            ambiance.setText("Cet appareil n'a pas de capteur de luminosité.");
            raz.setEnabled(false);
        } else {
            extremes.setText("Capteur : " + lightSensor.getName());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        extremes.setText("min " + format(min) + "   ·   max " + format(max));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static String decrire(float lux) {
        if (lux < 1) return "Obscurité";
        if (lux < 50) return "Très sombre";
        if (lux < 200) return "Pénombre (pièce peu éclairée)";
        if (lux < 500) return "Éclairage intérieur";
        if (lux < 1000) return "Bureau / salle de classe bien éclairée";
        if (lux < 10000) return "Lumière du jour à l'ombre";
        if (lux < 30000) return "Plein jour";
        return "Plein soleil";
    }

    private static String format(float lux) {
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
