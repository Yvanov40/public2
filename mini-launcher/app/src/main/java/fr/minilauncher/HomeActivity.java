package fr.minilauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Le launcher : fond d'écran, horloge, grille (applis, dossiers, widgets), alphabet sur les bords. */
public final class HomeActivity extends Activity
        implements HomeView.Host, AlphaStripView.Listener, FolderWheelView.Listener {

    private static final int HOST_ID = 1024;
    private static final int REQ_BIND = 1;
    private static final int REQ_CONFIGURE = 2;

    private HomeGrid grid;
    private AlphaStripView strip;
    private FolderWheelView folderWheel;
    private HomeView home;
    private AppRepository repository;
    private AppWidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    private LinearLayout clockBox;
    private TextClock clockTime, clockDate;

    private int pendingWidgetId = -1;
    private AppWidgetProviderInfo pendingInfo;

    private final AppRepository.Callback callback = new AppRepository.Callback() {
        @Override
        public void onAppsChanged(List<AppRepository.AppEntry> apps) {
            if (isDestroyed()) return;
            grid.setApps(apps);
            strip.setApps(apps);
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

        widgetHost = new AppWidgetHost(this, HOST_ID);
        widgetManager = AppWidgetManager.getInstance(this);

        grid = new HomeGrid(this, widgetHost);
        strip = new AlphaStripView(this);
        strip.setListener(this);
        folderWheel = new FolderWheelView(this);
        folderWheel.setListener(this);
        home = new HomeView(this, grid, strip, folderWheel, this);
        home.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        clockBox = clock();
        home.addView(clockBox, 0);
        home.setClock(clockBox, this::openAlarms);
        // La grille commence juste sous l'horloge.
        clockBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateGridTop());
        setContentView(home);

        repository = AppRepository.get(this);
        grid.setApps(repository.cached());
        strip.setApps(repository.cached());
        grid.reload();
    }

    // ------------------------------------------------------------ horloge

    private void updateGridTop() {
        float dp = getResources().getDisplayMetrics().density;
        grid.setTopOffset(clockBox.getVisibility() == View.VISIBLE ? clockBox.getBottom() + 12 * dp : 0);
    }

    /** Applique les réglages de l'horloge (taille, alignement, date, masquée). */
    private void applyClockSettings() {
        Store store = Store.get(this);
        clockBox.setVisibility(store.clock() ? View.VISIBLE : View.GONE);
        clockTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, store.clockSize());
        clockDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(13, store.clockSize() * 0.3f));
        clockDate.setVisibility(store.clockDate() ? View.VISIBLE : View.GONE);
        int align = store.clockAlign();
        int g = align == 1 ? Gravity.CENTER_HORIZONTAL : align == 2 ? Gravity.END : Gravity.START;
        clockBox.setGravity(g);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) clockBox.getLayoutParams();
        lp.gravity = Gravity.TOP | g;
        clockBox.setLayoutParams(lp);
        updateGridTop();
    }

    private void openAlarms() {
        try {
            startActivity(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private LinearLayout clock() {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (28 * dp), (int) (48 * dp), (int) (28 * dp), 0);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("h:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60);
        time.setShadowLayer(6 * dp, 0, dp, 0x66000000);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEE d MMM");
        date.setFormat12Hour("EEE d MMM");
        date.setTextColor(Color.WHITE);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        date.setShadowLayer(6 * dp, 0, dp, 0x66000000);
        box.addView(time);
        box.addView(date);
        clockTime = time;
        clockDate = date;
        box.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        return box;
    }

    // ------------------------------------------------------------ cycle de vie

    @Override
    protected void onStart() {
        super.onStart();
        try {
            widgetHost.startListening();
        } catch (RuntimeException ignored) {
        }
        applyClockSettings();
        grid.reload();
        strip.refresh();
        home.invalidate();
        repository.refresh(callback);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            widgetHost.stopListening();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeOverlays(); // bouton Accueil
    }

    @Override
    protected void onPause() {
        super.onPause();
        closeOverlays();
    }

    @Override
    public void onBackPressed() {
        closeOverlays();
    }

    private void closeOverlays() {
        strip.close();
        folderWheel.close();
    }

    private void refreshAll() {
        grid.reload();
        strip.refresh();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
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
        String[] items = {"Ajouter une appli", "Créer un dossier", "Ajouter un widget", "Fond d'écran",
                "Réglages du launcher"};
        dialog().setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    Menus.pickApp(this, "Ajouter à l'accueil", grid.sortedApps(), app -> {
                        Store store = Store.get(this);
                        String key = Store.key(app.component);
                        if (!store.isOnHome(key)) store.toggleHomeApp(key);
                        refreshAll();
                    });
                    break;
                case 1:
                    Menus.newFolder(this, grid.sortedApps(), this::refreshAll);
                    break;
                case 2:
                    pickWidget();
                    break;
                case 3:
                    try {
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Fond d'écran"));
                    } catch (ActivityNotFoundException ignored) {
                    }
                    break;
                default:
                    startActivity(new Intent(this, SettingsActivity.class));
            }
        }).show();
    }

    @Override
    public void onLaunchHomeApp(AppRepository.AppEntry app, Rect bounds) {
        Launcher.launch(this, app, bounds, home);
    }

    @Override
    public void onHomeItemMenu(Store.HomeItem item) {
        if (item.folder != null) {
            Menus.folderMenu(this, item.folder, grid.sortedApps(), this::refreshAll);
        } else if (item.widget) {
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(item.widgetId);
            boolean configurable = info != null && info.configure != null;
            String[] opts = configurable
                    ? new String[]{"Taille…", "Régler le widget", "Retirer de l'accueil"}
                    : new String[]{"Taille…", "Retirer de l'accueil"};
            dialog().setItems(opts, (d, which) -> {
                if (which == 0) {
                    chooseSize(item);
                } else if (configurable && which == 1) {
                    try {
                        widgetHost.startAppWidgetConfigureActivityForResult(this, item.widgetId, 0, 99, null);
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "Ce widget ne peut pas être réglé ici", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    grid.remove(item);
                }
            }).show();
        } else {
            AppRepository.AppEntry app = grid.appOf(item);
            if (app != null) {
                Menus.appMenu(this, app.component, app.label, null, this::refreshAll);
            } else {
                grid.remove(item);
            }
        }
    }

    private void chooseSize(Store.HomeItem item) {
        List<int[]> sizes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int h = 1; h <= Math.min(4, grid.rows()); h++) {
            for (int w = 1; w <= grid.cols(); w++) {
                sizes.add(new int[]{w, h});
                labels.add(w + " × " + h + (w == item.w && h == item.h ? "  (actuelle)" : ""));
            }
        }
        dialog().setTitle("Taille (colonnes × lignes)")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    int[] s = sizes.get(which);
                    if (!grid.resize(item, s[0], s[1])) {
                        Toast.makeText(this, "Pas assez de place pour cette taille", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    // ------------------------------------------------------------ widgets

    private void pickWidget() {
        PackageManager pm = getPackageManager();
        List<AppWidgetProviderInfo> providers = new ArrayList<>(widgetManager.getInstalledProviders());
        List<String> labels = new ArrayList<>();
        Collator collator = Collator.getInstance(Locale.getDefault());
        providers.sort((a, b) -> collator.compare(appLabel(pm, a) + a.loadLabel(pm), appLabel(pm, b) + b.loadLabel(pm)));
        for (AppWidgetProviderInfo p : providers) {
            int[] span = grid.spanFor(p);
            labels.add(appLabel(pm, p) + " — " + p.loadLabel(pm) + "  (" + span[0] + "×" + span[1] + ")");
        }
        if (providers.isEmpty()) {
            Toast.makeText(this, "Aucun widget disponible", Toast.LENGTH_SHORT).show();
            return;
        }
        dialog().setTitle("Ajouter un widget")
                .setItems(labels.toArray(new String[0]), (d, which) -> bindWidget(providers.get(which)))
                .show();
    }

    private static String appLabel(PackageManager pm, AppWidgetProviderInfo p) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(p.provider.getPackageName(), 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return p.provider.getPackageName();
        }
    }

    private void bindWidget(AppWidgetProviderInfo info) {
        int id = widgetHost.allocateAppWidgetId();
        pendingWidgetId = id;
        pendingInfo = info;
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.getProfile(), info.provider, null)) {
            configureWidget();
        } else {
            // Android demande à l'utilisateur d'autoriser le launcher à créer des widgets.
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
            try {
                startActivityForResult(intent, REQ_BIND);
            } catch (ActivityNotFoundException e) {
                cancelPendingWidget();
            }
        }
    }

    private void configureWidget() {
        if (pendingInfo.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, pendingWidgetId, 0, REQ_CONFIGURE, null);
                return;
            } catch (RuntimeException ignored) {
                // Écran de configuration inaccessible : on pose le widget tel quel.
            }
        }
        placePendingWidget();
    }

    private void placePendingWidget() {
        int[] span = grid.spanFor(pendingInfo);
        int id = pendingWidgetId;
        pendingWidgetId = -1;
        pendingInfo = null;
        if (!grid.addWidget(id, span[0], span[1])) {
            widgetHost.deleteAppWidgetId(id);
            Toast.makeText(this, "Plus de place sur l'accueil pour ce widget", Toast.LENGTH_LONG).show();
        }
    }

    private void cancelPendingWidget() {
        if (pendingWidgetId != -1) widgetHost.deleteAppWidgetId(pendingWidgetId);
        pendingWidgetId = -1;
        pendingInfo = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (pendingWidgetId == -1 || pendingInfo == null) return;
        if (resultCode != RESULT_OK) {
            cancelPendingWidget();
            return;
        }
        if (requestCode == REQ_BIND) configureWidget();
        else if (requestCode == REQ_CONFIGURE) placePendingWidget();
    }

    // ------------------------------------------------------------ alphabet

    @Override
    public void onStripLaunch(AppRepository.AppEntry app, Rect bounds) {
        Launcher.launch(this, app, bounds, home);
    }

    @Override
    public void onStripAppMenu(AppRepository.AppEntry app, String group) {
        Menus.appMenu(this, app.component, app.label, group, this::refreshAll);
    }

    @Override
    public void onStripClosed() {
        home.stripClosed();
    }

    // ------------------------------------------------------------ dossiers

    @Override
    public void onFolderLaunch(AppRepository.AppEntry app, Rect bounds) {
        Launcher.launch(this, app, bounds, home);
    }

    @Override
    public void onFolderAppMenu(AppRepository.AppEntry app, String folder) {
        folderWheel.close();
        Menus.appMenu(this, app.component, app.label, folder, this::refreshAll);
    }

    @Override
    public void onFolderClosed() {
        home.folderClosed();
    }
}
