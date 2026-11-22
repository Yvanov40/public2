package fr.minilauncher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.Toast;

import java.util.List;

/** Le launcher : fond d'écran, horloge, et la roue sur les bords. */
public final class HomeActivity extends Activity implements RadialView.Listener, HomeView.Host {

    private RadialView wheel;
    private HomeView home;
    private AppRepository repository;

    private final AppRepository.Callback callback = new AppRepository.Callback() {
        @Override
        public void onAppsChanged(List<AppRepository.AppEntry> apps) {
            if (!isDestroyed()) wheel.setApps(apps);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }

        wheel = new RadialView(this);
        wheel.setListener(this);
        wheel.dismissNow();
        home = new HomeView(this, wheel, this);
        home.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        home.addView(clock(), 0);
        setContentView(home);

        repository = AppRepository.get(this);
        wheel.setApps(repository.cached());
        BubbleService.sync(this);
    }

    private View clock() {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (28 * dp), (int) (56 * dp), (int) (28 * dp), 0);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        time.setShadowLayer(6 * dp, 0, dp, 0x66000000);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEE d MMM");
        date.setFormat12Hour("EEE d MMM");
        date.setTextColor(Color.WHITE);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        date.setShadowLayer(6 * dp, 0, dp, 0x66000000);
        box.addView(time);
        box.addView(date);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        box.setLayoutParams(lp);
        return box;
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.refresh(callback);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Bouton Accueil pendant que la roue est ouverte : on la referme.
        closeWheel();
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeWheel();
    }

    @Override
    public void onBackPressed() {
        if (home.isWheelActive() && !wheel.goBack()) wheel.close();
    }

    private void closeWheel() {
        wheel.dismissNow();
        home.wheelClosed();
    }

    // ------------------------------------------------------------ HomeView.Host

    @Override
    public void onShade(boolean controlCenter) {
        if (!Shade.open(this, controlCenter)) {
            Toast.makeText(this, "Le téléphone ne permet pas d'ouvrir ce volet", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onHomeLongPress() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ------------------------------------------------------------ RadialView.Listener

    @Override
    public void onLaunch(AppRepository.AppEntry app, Rect bounds) {
        Launcher.launch(this, app, bounds, home);
        closeWheel();
    }

    @Override
    public void onAppMenu(AppRepository.AppEntry app, String group) {
        Menus.appMenu(this, app.component, app.label, group, wheel::refresh, () -> {
        });
    }

    @Override
    public void onGroupMenu(String group) {
        Menus.groupMenu(this, group, wheel::refresh, () -> {
        });
    }

    @Override
    public void onMoved(float cx, float cy) {
    }

    @Override
    public void onClosed() {
        home.wheelClosed();
    }
}
