package fr.minilauncher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.List;

/**
 * Écran transparent ouvert par l'icône : la roue des lettres s'affiche autour de l'icône
 * touchée, puis les applis de la lettre choisie.
 */
public final class RadialActivity extends Activity implements RadialView.Listener {

    private RadialView view;
    private AppRepository repository;

    private final AppRepository.Callback callback = new AppRepository.Callback() {
        @Override
        public void onAppsChanged(List<AppRepository.AppEntry> apps) {
            if (!isDestroyed()) view.setApps(apps);
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

        view = new RadialView(this);
        view.setListener(this);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(view);

        repository = AppRepository.get(this);
        view.setApps(repository.cached());
        view.open(getIntent().getSourceBounds());
    }

    @Override
    protected void onStart() {
        super.onStart();
        repository.refresh(callback);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        view.open(intent.getSourceBounds());
    }

    @Override
    protected void onStop() {
        super.onStop();
        // On ne garde jamais la roue ouverte en arrière-plan.
        if (!isChangingConfigurations()) finish();
    }

    @Override
    public void onBackPressed() {
        if (!view.goBack()) view.close();
    }

    @Override
    public void onLaunch(AppRepository.AppEntry app, Rect bounds) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        intent.setSourceBounds(bounds);
        try {
            ActivityOptions options = ActivityOptions.makeScaleUpAnimation(
                    view, bounds.left, bounds.top, bounds.width(), bounds.height());
            startActivity(intent, options.toBundle());
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Impossible d'ouvrir " + app.label, Toast.LENGTH_SHORT).show();
            return;
        }
        finishQuietly();
    }

    @Override
    public void onAppInfo(AppRepository.AppEntry app) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.component.getPackageName(), null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            return;
        }
        finishQuietly();
    }

    @Override
    public void onClosed() {
        finishQuietly();
    }

    private void finishQuietly() {
        finish();
        overridePendingTransition(0, 0);
    }
}
