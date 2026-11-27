package fr.minilauncher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
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

import java.util.ArrayList;
import java.util.List;

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
    private View scrim;
    private android.app.WallpaperManager.OnColorsChangedListener colorsListener;
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
        // Presque transparent plutôt que transparent : certains systèmes (HyperOS) remplacent la
        // transparence totale par leur propre bandeau.
        window.setNavigationBarColor(0x01000000);
        window.setStatusBarColor(0x01000000);
        if (Build.VERSION.SDK_INT >= 29) {
            // Pas de bandeau clair ajouté par Android derrière les barres : le fond d'écran reste visible.
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
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
        scrim = new View(this);
        scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x80000000, 0x40000000, 0x00000000}));
        home.addView(scrim, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                (int) (150 * getResources().getDisplayMetrics().density), Gravity.TOP));
        if (Build.VERSION.SDK_INT >= 27) {
            colorsListener = (colors, which) -> applyAppearance();
            android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(colorsListener,
                    new android.os.Handler(android.os.Looper.getMainLooper()));
        }
        home.setClock(clockBox, this::openAlarms);
        // La grille commence juste sous l'horloge.
        clockBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateGridTop());
        setContentView(home);

        Badges.addListener(badgeListener);
        repository = AppRepository.get(this);
        grid.setApps(repository.cached());
        strip.setApps(repository.cached());
        grid.reload();
    }

    // ------------------------------------------------------------ lisibilité

    /** Le fond d'écran est-il clair (texte foncé conseillé) ? D'après les couleurs qu'Android en extrait. */
    private boolean wallpaperIsLight() {
        if (Build.VERSION.SDK_INT < 27) return false;
        try {
            android.app.WallpaperColors wc = android.app.WallpaperManager.getInstance(this)
                    .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
            if (wc == null) return false;
            if (Build.VERSION.SDK_INT >= 31) {
                return (wc.getColorHints() & android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
            }
            return wc.getPrimaryColor().luminance() > 0.6f;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Couleur des icônes de la barre d'état, de l'horloge et des noms d'applis ; voile en haut. */
    private void applyAppearance() {
        Store st = Store.get(this);
        boolean light = wallpaperIsLight();
        boolean withScrim = st.topScrim();
        int bar = st.barIcons();
        boolean darkIcons = bar == 2 || (bar == 0 && light && !withScrim);
        boolean darkNav = darkIcons && st.navKeys() == 0;
        int t = st.homeText();
        boolean darkText = t == 2 || (t == 0 && light);

        int layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 30) {
            home.setSystemUiVisibility(layout);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                int value = (darkIcons ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0)
                        | (darkNav ? android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0);
                c.setSystemBarsAppearance(value, mask);
            }
        } else {
            home.setSystemUiVisibility(layout | (darkIcons ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0)
                    | (darkNav ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        }

        float dp = getResources().getDisplayMetrics().density;
        int color = darkText ? 0xFF15171C : Color.WHITE;
        int shadow = darkText ? 0x55FFFFFF : 0x66000000;
        clockTime.setTextColor(color);
        clockTime.setShadowLayer(6 * dp, 0, dp, shadow);
        clockDate.setTextColor(color);
        clockDate.setShadowLayer(6 * dp, 0, dp, shadow);
        scrim.setVisibility(withScrim ? View.VISIBLE : View.GONE);
        if (HomeGrid.darkText != darkText) {
            HomeGrid.darkText = darkText;
            grid.reload();
        }
    }

    /** Nouveaux nombres de notifications : on redessine les pastilles. */
    private final Runnable badgeListener = () -> {
        if (isDestroyed()) return;
        grid.invalidateIcons();
        strip.invalidate();
        folderWheel.invalidate();
    };

    @Override
    protected void onDestroy() {
        Badges.removeListener(badgeListener);
        if (colorsListener != null && Build.VERSION.SDK_INT >= 27) {
            android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(colorsListener);
        }
        super.onDestroy();
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
        Store.get(this).pruneFolders();
        applyClockSettings();
        applyAppearance();
        KeepAliveService.sync(this);
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

    /** Cadre à poignées autour de l'élément : on l'étire pour changer sa taille. */
    private void startResize(Store.HomeItem item) {
        grid.startResize(item);
        Toast.makeText(this, "Étire les poignées ; touche à côté pour terminer", Toast.LENGTH_SHORT).show();
    }

    /** Appli maintenue dans la recherche : à poser sur l'accueil d'un toucher. */
    void placeOnHome(AppRepository.AppEntry app) {
        if (Store.get(this).homeLocked()) {
            Toast.makeText(this, "Accueil verrouillé", Toast.LENGTH_SHORT).show();
            return;
        }
        home.startPlacement(app);
        Toast.makeText(this, "Pose le doigt à l'endroit voulu, glisse si besoin, puis lâche",
                Toast.LENGTH_LONG).show();
    }

    private void closeOverlays() {
        home.cancelPlacement();
        grid.endResize();
        Sheet.dismissAll();
        strip.close();
        folderWheel.close();
    }

    private void refreshAll() {
        grid.reload();
        strip.refresh();
    }

    // ------------------------------------------------------------ HomeView.Host

    @Override
    public void onGesture(String gesture) {
        if (Actions.actionFor(this, gesture) == Actions.RECENTS) {
            home.openRecents(home.getWidth() / 2f, home.getHeight() * 0.55f, null);
            return;
        }
        Actions.runGesture(this, gesture);
    }

    private AppRepository.AppEntry appByKey(String key) {
        if (key == null) return null;
        for (AppRepository.AppEntry e : repository.cached()) if (Store.key(e.component).equals(key)) return e;
        return null;
    }

    @Override
    public void onFolderAppDrop(AppRepository.AppEntry app, String folder, float x, float y, boolean moved) {
        if (!moved) {
            // Maintenue puis lâchée sur place : le menu de l'appli, comme avant.
            Menus.appMenu(this, app.component, app.label, folder, this::refreshAll);
            return;
        }
        String message = grid.dropApp(Store.key(app.component), folder, x, y);
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        refreshAll();
    }

    @Override
    public void onDrawing(List<float[]> points) {
        android.gesture.Gesture g = Drawings.fromPoints(points);
        AppRepository.AppEntry app = appByKey(Drawings.recognize(this, g));
        if (app != null) {
            Launcher.launch(this, app, null, home);
            return;
        }
        // Tracé inconnu : on l'apprend tout de suite.
        Sheet.apps(this, "Ce tracé ouvrira…", grid.sortedApps(), chosen -> {
            Drawings.learn(this, g, Store.key(chosen.component));
            Toast.makeText(this, "Tracé appris : dessine-le pour ouvrir " + chosen.label
                    + ". Le refaire 2 ou 3 fois l'améliore.", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public List<AppRepository.AppEntry> recentApps() {
        List<AppRepository.AppEntry> out = new ArrayList<>();
        for (Object[] r : Recents.load(this, repository.cached())) out.add((AppRepository.AppEntry) r[0]);
        return out;
    }

    @Override
    public void onHomeLongPress() {
        Store st = Store.get(this);
        boolean locked = st.homeLocked();
        Runnable lockedToast = () -> Toast.makeText(this, "Accueil verrouillé : le déverrouiller d'abord",
                Toast.LENGTH_SHORT).show();
        List<Object[]> acts = new ArrayList<>();
        acts.add(new Object[]{"＋", "Appli", false, (Runnable) () -> {
            if (locked) {
                lockedToast.run();
                return;
            }
            Menus.pickApp(this, "Ajouter à l'accueil", grid.sortedApps(), app -> {
                String key = Store.key(app.component);
                if (!st.isOnHome(key)) st.toggleHomeApp(key);
                refreshAll();
            });
        }});
        acts.add(new Object[]{"▦", "Dossier", false, (Runnable) () -> {
            if (locked) lockedToast.run();
            else Menus.newFolder(this, grid.sortedApps(), this::refreshAll);
        }});
        acts.add(new Object[]{"▭", "Widget", false, (Runnable) () -> {
            if (locked) lockedToast.run();
            else pickWidget();
        }});
        acts.add(new Object[]{"◩", "Fond d'écran", false, (Runnable) () -> {
            try {
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Fond d'écran"));
            } catch (ActivityNotFoundException ignored) {
            }
        }});
        acts.add(new Object[]{locked ? "🔓" : "🔒", locked ? "Déverrouiller la disposition" : "Verrouiller la disposition",
                false, (Runnable) () -> {
            st.setFlag(Store.HOME_LOCKED, !locked);
            Toast.makeText(this, locked ? "Disposition déverrouillée"
                    : "Disposition verrouillée : plus rien ne bouge", Toast.LENGTH_SHORT).show();
        }});
        acts.add(new Object[]{"⏻", "Verrouiller l'écran", false, (Runnable) () -> Actions.lock(this)});
        acts.add(new Object[]{"⚙", "Réglages", false, (Runnable) () -> startActivity(new Intent(this, SettingsActivity.class))});
        Menus.tileSheet(this, getApplicationInfo().loadIcon(getPackageManager()), 0, "Mini Launcher",
                "Version " + Store.version(this) + (locked ? " · disposition verrouillée" : ""), acts);
    }

    @Override
    public void onLiftMenu(AppRepository.AppEntry app, String folder) {
        Menus.appMenu(this, app.component, app.label, folder, this::refreshAll);
    }

    @Override
    public void onLaunchHomeApp(AppRepository.AppEntry app, Rect bounds) {
        if (Store.isSub(app.component)) {
            int[] loc = new int[2];
            home.getLocationOnScreen(loc);
            home.openFolderSticky(app.label, bounds.exactCenterX() - loc[0], bounds.exactCenterY() - loc[1]);
            return;
        }
        Launcher.launch(this, app, bounds, home);
    }

    @Override
    public void onHomeItemMenu(Store.HomeItem item) {
        if (item.folder != null) {
            Menus.folderMenu(this, item.folder, grid.appsWithFolders(item.folder), this::refreshAll,
                    Store.get(this).homeLocked() ? null : () -> startResize(item), grid.folderVisibleCount(item));
        } else if (item.widget) {
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(item.widgetId);
            boolean configurable = info != null && info.configure != null;
            String[] opts = configurable
                    ? new String[]{"Taille…", "Régler le widget", "Retirer de l'accueil"}
                    : new String[]{"Taille…", "Retirer de l'accueil"};
            String title = info == null ? "Widget" : info.loadLabel(getPackageManager());
            Sheet.list(this, title, opts, which -> {
                if (which == 0) {
                    if (Store.get(this).homeLocked()) {
                        Toast.makeText(this, "Accueil verrouillé", Toast.LENGTH_SHORT).show();
                    } else {
                        startResize(item);
                    }
                } else if (configurable && which == 1) {
                    try {
                        widgetHost.startAppWidgetConfigureActivityForResult(this, item.widgetId, 0, 99, null);
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "Ce widget ne peut pas être réglé ici", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    grid.remove(item);
                }
            });
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
        List<Sheet.Item> items = new ArrayList<>();
        for (int h = 1; h <= Math.min(4, grid.rows()); h++) {
            for (int w = 1; w <= grid.cols(); w++) {
                sizes.add(new int[]{w, h});
                Sheet.Item it = new Sheet.Item(w + " × " + h);
                it.subtitle = w + (w > 1 ? " colonnes, " : " colonne, ") + h + (h > 1 ? " lignes" : " ligne");
                it.selected = w == item.w && h == item.h;
                items.add(it);
            }
        }
        Sheet.list(this, "Taille sur l'accueil", items, which -> {
            int[] s = sizes.get(which);
            if (!grid.resize(item, s[0], s[1])) {
                Toast.makeText(this, "Pas assez de place pour cette taille", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------ widgets

    private void pickWidget() {
        WidgetPicker.show(this, widgetManager.getInstalledProviders(), grid::spanFor, this::bindWidget);
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
        if (Store.isSub(app.component)) return;
        Launcher.launch(this, app, bounds, home);
    }

    @Override
    public void onFolderAppMenu(AppRepository.AppEntry app, String folder) {
        folderWheel.close();
        if (Store.isSub(app.component)) {
            Menus.folderMenu(this, app.label, grid.appsWithFolders(app.label), this::refreshAll, null, 0);
            return;
        }
        Menus.appMenu(this, app.component, app.label, folder, this::refreshAll);
    }

    @Override
    public void onFolderClosed() {
        home.folderClosed();
    }
}
