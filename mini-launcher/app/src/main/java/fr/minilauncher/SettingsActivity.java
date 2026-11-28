package fr.minilauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Réglages, en cartes : une carte par thème, une ligne par réglage (nom à gauche, valeur à
 * droite), les explications en petit sous la carte.
 */
public final class SettingsActivity extends Activity {

    private static final int BG = 0xFF0F1015;
    private static final int CARD = 0xFF1A1C23;
    private static final int DIVIDER = 0xFF272A34;
    private static final int TEXT = 0xFFF2F3F7;
    private static final int MUTED = 0xFF8C92A3;
    private static final int DANGER = 0xFFFF8F8F;

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
    private int accent;
    private LinearLayout page;     // la colonne de toute la page
    private LinearLayout card;     // la carte en cours
    private LinearLayout footer;   // les explications sous la carte en cours
    private LinearLayout badgeApp;
    private LinearLayout usageAccess;

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    /** Libellés d'état (accès accordés ou non), à jour au retour des écrans d'autorisation. */
    private void refreshStatus() {
        if (badgeApp != null) {
            TextView t = (TextView) ((LinearLayout) badgeApp.getChildAt(0)).getChildAt(0);
            t.setText(Badges.accessGranted(this) ? "Accès aux notifications : activé ✓"
                    : "Autoriser l'accès aux notifications");
        }
        // Retour de l'écran d'autorisation : le libellé suit l'état réel.
        if (usageAccess != null) {
            TextView t = (TextView) ((LinearLayout) usageAccess.getChildAt(0)).getChildAt(0);
            t.setText(Recents.hasUsageAccess(this) ? "Accès aux données d'utilisation : activé ✓"
                    : "Autoriser l'accès aux données d'utilisation");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = Store.get(this);
        AppRepository.get(this).refresh(apps -> {
        }); // pour le choix d'une appli dans les gestes
        dp = getResources().getDisplayMetrics().density;
        accent = store.accent();

        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        w.setBackgroundDrawable(new ColorDrawable(BG));

        int open = savedInstanceState == null ? -1 : savedInstanceState.getInt("page", -1);
        String wanted = getIntent().getStringExtra(EXTRA_PAGE);
        if (savedInstanceState == null && wanted != null) {
            Tile[] ts = tiles();
            for (int i = 0; i < ts.length; i++) if (ts[i].title.equals(wanted)) open = i;
        }
        if (open >= 0 && open < tiles().length) showPage(open);
        else showHome();
    }

    // ------------------------------------------------------------ tuiles

    private static final class Tile {
        final String icon, title, summary;
        final Runnable build;

        Tile(String icon, String title, String summary, Runnable build) {
            this.icon = icon;
            this.title = title;
            this.summary = summary;
            this.build = build;
        }
    }

    private int currentPage = -1;

    /** Page à ouvrir directement (titre de sa tuile), ex. « Mise à jour » depuis l'accueil. */
    static final String EXTRA_PAGE = "page_title";

    private Tile[] tiles() {
        return new Tile[]{
                new Tile("◫", "Modules", "Activer ou couper chaque fonction", this::pageModules),
                new Tile("⌂", "Accueil", "Grille, horloge, garder actif", this::pageAccueil),
                new Tile("A", "Alphabet", "Bords, loupe, couleurs", this::pageAlphabet),
                new Tile("☝", "Gestes", "Double tap, glissés, volets", this::pageGestes),
                new Tile("★", "Favoris et dossiers", "Favoris, cercle des dossiers", this::pageFavoris),
                new Tile("●", "Pastilles", "Notifications sur les icônes", this::pagePastilles),
                new Tile("◐", "Apparence", "Lisibilité, couleur, effets", this::pageApparence),
                new Tile("⌕", "Panneaux et recherche", "Thème, position, grille", this::pagePanneaux),
                new Tile("↓", "Mise à jour", "Télécharger et installer la dernière version", this::pageMiseAJour),
        };
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", currentPage);
    }

    @Override
    public void onBackPressed() {
        if (currentPage >= 0) showHome();
        else super.onBackPressed();
    }

    private void newPage() {
        badgeApp = null;
        usageAccess = null;
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(16), px(28), px(16), px(32));
    }

    private void showContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(page);
        setContentView(scroll);
    }

    /** L'écran principal : une grille de tuiles, deux par ligne. */
    private void showHome() {
        currentPage = -1;
        newPage();
        header();
        Tile[] ts = tiles();
        LinearLayout row = null;
        for (int i = 0; i < ts.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rl.topMargin = px(12);
                page.addView(row, rl);
            }
            int index = i;
            View tile = tile(ts[i], v -> showPage(index));
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, px(132), 1f);
            if (i % 2 == 0) tl.rightMargin = px(6);
            else tl.leftMargin = px(6);
            row.addView(tile, tl);
        }
        if (ts.length % 2 == 1 && row != null) {
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, px(132), 1f);
            tl.leftMargin = px(6);
            row.addView(new View(this), tl);
        }
        versionFooter();
        showContent();
    }

    private View tile(Tile t, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(16), px(16), px(14), px(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(px(22));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(px(22));
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg, mask));
        c.setOnClickListener(l);

        TextView icon = text(t.icon, 20, accent);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        GradientDrawable ib = new GradientDrawable();
        ib.setShape(GradientDrawable.OVAL);
        ib.setColor((accent & 0x00FFFFFF) | 0x26000000);
        icon.setBackground(ib);
        c.addView(icon, new LinearLayout.LayoutParams(px(42), px(42)));

        View spacer = new View(this);
        c.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView title = text(t.title, 16, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(title);
        TextView sum = text(t.summary, 12, MUTED);
        sum.setMaxLines(2);
        sum.setEllipsize(TextUtils.TruncateAt.END);
        sum.setPadding(0, px(2), 0, 0);
        c.addView(sum);
        return c;
    }

    /** Une page : le retour vers les tuiles, le titre, puis les cartes du thème. */
    private void showPage(int index) {
        Tile t = tiles()[index];
        currentPage = index;
        newPage();
        TextView back = text("‹  Réglages", 16, accent);
        back.setPadding(px(6), px(8), px(16), px(8));
        clickable(back, v -> showHome());
        page.addView(back, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView title = text(t.title, 28, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(px(6), px(4), 0, px(4));
        page.addView(title);
        t.build.run();
        showContent();
        refreshStatus();
    }

    // ------------------------------------------------------------ pages

    private void pageModules() {
        section("Pour aller vite");
        toggle("Lettres dessinées", "Dessiner une lettre dans le vide ouvre son appli", store.drawOn(),
                on -> store.setFlag(Store.DRAW_ON, on));
        toggle("Lettres qui apprennent", "Lâcher sur une lettre de l'alphabet ouvre son appli préférée",
                store.alphaLearn(), on -> store.setFlag(Store.ALPHA_LEARN, on));
        toggle("Alphabet sur les bords", "L'index A–Z des deux côtés de l'écran", store.alphaOn(),
                on -> store.setFlag(Store.ALPHA_ON, on));
        note("Lettres dessinées coupées : les glissés partent tout de suite, sans attendre le lever "
                + "du doigt. Appui long dans le vide : menu de l'accueil.");
        section("Autres");
        toggle("Pastilles de notification", "Réglages dans la tuile Pastilles", store.badges(),
                on -> store.setFlag(Store.BADGES, on));
        toggle("Garder le launcher actif", "Évite le redémarrage après « tout fermer »", store.keepAlive(), on -> {
            store.setFlag(Store.KEEP_ALIVE, on);
            KeepAliveService.sync(this);
        });
        note("Les gestes (double tap, glissés) se règlent un par un dans la tuile Gestes ; « Rien » "
                + "désactive un geste.");
    }

    private void pageAccueil() {
        // ---------------------------------------------------------- accueil
        section("Écran d'accueil");
        action("Définir comme écran d'accueil", "Choisir Mini Launcher par défaut", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
        toggle("Garder le launcher actif", "Évite qu'il redémarre quand on ferme toutes les applis",
                store.keepAlive(), on -> {
                    store.setFlag(Store.KEEP_ALIVE, on);
                    if (on && android.os.Build.VERSION.SDK_INT >= 33) {
                        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
                    }
                    KeepAliveService.sync(this);
                });
        note("Appui long sur une zone vide de l'accueil : ajouter une appli, un dossier, un widget, "
                + "changer le fond d'écran. « Garder le launcher actif » ajoute une notification "
                + "discrète, imposée par Android. Aide aussi : Paramètres > Applis > Mini Launcher > "
                + "Démarrage automatique, et Économiseur de batterie > Aucune restriction.");

        // ---------------------------------------------------------- horloge
        section("Horloge");
        toggle("Afficher l'horloge", "La toucher ouvre les alarmes", store.clock(),
                on -> store.setFlag(Store.CLOCK, on));
        slider("Taille", 30, 110, store.clockSize(), "%d", v -> store.setNum(Store.CLOCK_SIZE, v));
        choice("Position", new String[]{"À gauche", "Au centre", "À droite"}, store.clockAlign(),
                i -> store.setNum(Store.CLOCK_ALIGN, i));
        toggle("Afficher la date", null, store.clockDate(), on -> store.setFlag(Store.CLOCK_DATE, on));
        note("Masque l'horloge pour mettre un widget horloge à la place.");

        // ---------------------------------------------------------- grille
        section("Grille");
        slider("Colonnes", 3, 6, store.cols(), "%d", v -> store.setNum(Store.COLS, v));
        slider("Lignes", 0, 9, store.rowsSetting(), "%d", v -> store.setNum(Store.ROWS, v));
        slider("Taille des icônes", 36, 72, store.iconSize(), "%d dp", v -> store.setNum(Store.ICON, v));
        toggle("Nom des applis", null, store.labels(), on -> store.setFlag(Store.LABELS, on));
        slider("Opacité des icônes", 10, 100, store.iconAlpha(), "%d %%", v -> store.setNum(Store.ICON_ALPHA, v));
        slider("Opacité des widgets", 10, 100, store.widgetAlpha(), "%d %%", v -> store.setNum(Store.WIDGET_ALPHA, v));
        note("Lignes à 0 : automatique, selon la taille de l'écran.");
    }

    private void pageAlphabet() {
        // ---------------------------------------------------------- alphabet
        section("Alphabet sur les bords");
        choice("Côté", new String[]{"Les deux", "Gauche", "Droite"}, store.alphaSide(),
                i -> store.setNum(Store.SIDE, i));
        toggle("Toujours visible", "Sinon, seulement au toucher", store.alphaVisible(),
                on -> store.setFlag(Store.ALPHA_VISIBLE, on));
        slider("Hauteur", 25, 100, store.stripHeight(), "%d %%", v -> store.setNum(Store.STRIP_H, v));
        slider("Position verticale", 0, 100, store.stripPos(), "%d %%", v -> store.setNum(Store.STRIP_POS, v));
        choice("Effet loupe", new String[]{"Aucun", "Léger", "Normal", "Fort"}, store.zoom(),
                i -> store.setNum(Store.ZOOM, i));
        colorChoice("Couleur de la zone", ZONE_NAMES, ZONE_COLORS, store.stripBg(),
                c -> store.setNum(Store.STRIP_BG, c));
        slider("Opacité de la zone", 0, 100, store.stripAlpha(), "%d %%", v -> store.setNum(Store.STRIP_ALPHA, v));
        colorChoice("Couleur des lettres", LETTER_NAMES, LETTER_COLORS, store.stripFg(),
                c -> store.setNum(Store.STRIP_FG, c));
        slider("Zone tactile", 8, 40, store.band(), "%d %%", v -> store.setNum(Store.BAND, v));
        note("Poser le doigt sur un bord et le glisser de haut en bas : les applis de la lettre "
                + "apparaissent à côté. Glisser vers l'intérieur jusqu'à l'appli et lâcher. "
                + "En haut ★ les favoris.");
    }

    private void pageGestes() {
        // ---------------------------------------------------------- gestes
        section("Gestes");
        for (int i = 0; i < Actions.GESTURES.length; i++) gestureChoice(i);
        usageAccess = row("", "Applis récentes : toutes celles utilisées, pas seulement celles ouvertes "
                + "depuis le launcher", accent);
        usageAccess.addView(chevron());
        clickable(usageAccess, v -> {
            Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) i.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        });
        addRow(usageAccess);
        note("Les gestes marchent dans les zones vides du milieu de l'écran. Glisser vers le haut "
                + "ouvre les applis récentes en roue : continuer jusqu'à l'appli et lâcher.");

        section("Lettres dessinées");
        drawingRows();
        note("Dessiner une lettre (ou n'importe quel tracé) dans le vide de l'accueil ouvre l'appli "
                + "associée. Un tracé inconnu demande à quelle appli l'associer ; le refaire 2 ou 3 "
                + "fois améliore la reconnaissance. Les lignes droites restent des gestes.");

        section("Centre de contrôle");
        choice("Méthode d'ouverture", Shade.METHODS, store.ccMethod(), i -> store.setNum(Store.CC_METHOD, i));
        action("Essayer", null, v -> {
            if (store.ccMethod() == Shade.DISABLED) {
                Toast.makeText(this, "Désactivé : glisse depuis la barre d'état, en haut à droite",
                        Toast.LENGTH_LONG).show();
            } else if (!Shade.open(this, true)) {
                Toast.makeText(this, "Le téléphone refuse cet appel", Toast.LENGTH_SHORT).show();
            }
        });
        note("S'il s'ouvre à moitié, essaie les autres méthodes et garde celle qui marche. Sur "
                + "HyperOS, le style « ancienne version » du centre de contrôle aide aussi.");

        section("Verrouillage");
        danger("Retirer le droit de verrouillage", "À faire avant de désinstaller l'appli", v -> {
            Actions.removeLock(this);
            Toast.makeText(this, "Droit de verrouillage retiré", Toast.LENGTH_SHORT).show();
        });
        note("« Verrouiller l'écran » utilise le droit administrateur de l'appareil, limité au "
                + "verrouillage. Ensuite, certains téléphones demandent le code au lieu de l'empreinte.");
    }

    private void pageFavoris() {
        // ---------------------------------------------------------- favoris
        section("Favoris ★");
        toggle("Compléter automatiquement", "Avec les applis les plus utilisées", store.autoFavorites(),
                on -> store.setFlag("autoFav", on));
        slider("Nombre de favoris", 0, 8, store.maxFav(), "%d", v -> store.setNum(Store.MAXFAV, v));
        note("Appui long sur une appli : l'épingler en favori, l'ajouter à un dossier ou la poser "
                + "sur l'accueil.");

        // ---------------------------------------------------------- dossiers
        section("Dossiers");
        colorChoice("Fond du cercle", ZONE_NAMES, ZONE_COLORS, store.folderBg(), c -> store.setNum(Store.F_BG, c));
        slider("Opacité du cercle", 0, 100, store.folderBgAlpha(), "%d %%", v -> store.setNum(Store.F_BG_ALPHA, v));
        slider("Taille des icônes", 34, 64, store.folderIcon(), "%d dp", v -> store.setNum(Store.F_ICON, v));
        slider("Taille du cercle", 70, 140, store.folderRadius(), "%d %%", v -> store.setNum(Store.F_RADIUS, v));
        toggle("Noms dans le cercle", null, store.folderLabels(), on -> store.setFlag(Store.F_LABELS, on));
        toggle("Bordure de couleur", "Sur l'icône du dossier", store.folderBorder(),
                on -> store.setFlag(Store.F_BORDER, on));
        slider("Opacité de l'icône", 0, 100, store.folderTile(), "%d %%", v -> store.setNum(Store.F_TILE_ALPHA, v));
        note("Lâcher une appli sur une autre crée un dossier. Pour un dossier précis (couleur, "
                + "style, forme) : le maintenir puis lâcher sur place > Personnaliser.");
    }

    private void pagePastilles() {
        // ---------------------------------------------------------- pastilles
        section("Pastilles de notification");
        badgeApp = row("", "Pour savoir quelles applis ont des notifications", accent);
        badgeApp.addView(chevron());
        clickable(badgeApp, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
            Toast.makeText(this, "Active « Mini Launcher »", Toast.LENGTH_LONG).show();
        });
        addRow(badgeApp);
        toggle("Afficher les pastilles", null, store.badges(), on -> store.setFlag(Store.BADGES, on));
        choice("Style", new String[]{"Point", "Nombre"}, store.badgeStyle(), i -> store.setNum(Store.BADGE_STYLE, i));
        int[] badgeSwatches = Badges.COLORS.clone();
        badgeSwatches[badgeSwatches.length - 1] = accent;
        colorChoice("Couleur", Badges.COLOR_NAMES, badgeSwatches,
                store.badgeColor() == 0 ? accent : store.badgeColor(),
                c -> store.setNum(Store.BADGE_COLOR, c == accent ? 0 : (0xFF000000 | c)));
        note("L'accès aux notifications sert seulement à compter les notifications de chaque appli ; "
                + "leur contenu n'est ni lu ni gardé. S'il est grisé : Paramètres > Applis > Mini "
                + "Launcher > ⋮ > « Autoriser les paramètres restreints ». Avec cet accès, Play Protect "
                + "bloque l'installation des mises à jour : couper son analyse le temps de l'installation.");
    }

    private void pageApparence() {
        // ---------------------------------------------------------- lisibilité
        section("Lisibilité");
        choice("Icônes de la barre d'état", new String[]{"Automatique", "Claires", "Foncées"}, store.barIcons(),
                i -> store.setNum(Store.BAR_ICONS, i));
        choice("Touches du bas", new String[]{"Comme la barre d'état", "Toujours claires"}, store.navKeys(),
                i -> store.setNum(Store.NAV_KEYS, i));
        choice("Texte de l'accueil", new String[]{"Automatique", "Blanc", "Noir"}, store.homeText(),
                i -> store.setNum(Store.HOME_TEXT, i));
        toggle("Voile sombre en haut", "Garde les icônes claires lisibles sur tout fond", store.topScrim(),
                on -> store.setFlag(Store.TOP_SCRIM, on));
        note("Automatique : foncé sur un fond d'écran clair, blanc sur un fond sombre. Le texte de "
                + "l'accueil comprend l'horloge et le nom des applis. Si un bandeau clair apparaît "
                + "derrière les touches du bas, choisis « Toujours claires » pour elles.");

        // ---------------------------------------------------------- effets
        section("Effets");
        accentChoice();
        slider("Assombrissement", 0, 90, store.dimPercent(), "%d %%", v -> store.setNum(Store.DIM, v));
        choice("Animations", new String[]{"Aucune", "Normales", "Rapides", "Lentes"}, store.speed(),
                i -> store.setNum(Store.SPEED, i));
        toggle("Vibrations", null, store.haptic(), on -> store.setFlag(Store.HAPTIC, on));
    }

    private void pagePanneaux() {
        // ---------------------------------------------------------- panneaux
        section("Panneaux et recherche");
        choice("Thème des panneaux", Sheet.THEMES, store.panelTheme(), i -> store.setNum(Store.PANEL_THEME, i));
        slider("Opacité", 50, 100, store.panelAlpha(), "%d %%", v -> store.setNum(Store.PANEL_ALPHA, v));
        slider("Arrondi des coins", 8, 36, store.panelRadius(), "%d dp", v -> store.setNum(Store.PANEL_RADIUS, v));
        choice("Recherche : position", new String[]{"En bas", "En haut", "Au milieu"}, store.searchPos(),
                i -> store.setNum(Store.SEARCH_POS, i));
        toggle("Une seule appli trouvée : l'ouvrir", "Après une courte pause dans la frappe",
                store.searchAutoOpen(), on -> store.setFlag(Store.SEARCH_AUTO, on));
        choice("Recherche : affichage", new String[]{"Liste", "Grille d'icônes"}, store.searchGrid() ? 1 : 0,
                i -> store.setFlag(Store.SEARCH_GRID, i == 1));
        note("Les panneaux : recherche, menus d'appui long, choix d'applis, de couleurs, de widgets. "
                + "Le bouton Accueil ou Retour les ferme.");
    }


    // ------------------------------------------------------------ structure

    private int px(float v) {
        return (int) (v * dp);
    }

    private void pageMiseAJour() {
        section("Mise à jour");
        String local = Store.version(this);
        LinearLayout r = row("Version installée : " + local, "Recherche de la dernière version…", accent);
        TextView sub = (TextView) ((LinearLayout) r.getChildAt(0)).getChildAt(1);
        TextView title = (TextView) ((LinearLayout) r.getChildAt(0)).getChildAt(0);
        Updater.Info[] found = {null};
        boolean[] busy = {true};
        Runnable check = () -> {
            busy[0] = true;
            sub.setText("Recherche de la dernière version…");
            Updater.check((info, error) -> {
                busy[0] = false;
                found[0] = null;
                if (info == null) {
                    sub.setText(error + " · toucher pour réessayer");
                } else if (Updater.newer(info.version, local)) {
                    found[0] = info;
                    title.setText("Installer la version " + info.version);
                    sub.setText("Version installée : " + local + " · " + (info.size / 1024) + " Ko · toucher pour installer");
                } else {
                    title.setText("Version installée : " + local);
                    sub.setText("C'est la dernière (" + info.version + ") · toucher pour revérifier");
                }
            });
        };
        clickable(r, v -> {
            if (busy[0]) return;
            Updater.Info info = found[0];
            if (info == null) {
                check.run();
                return;
            }
            if (!Updater.canInstall(this)) {
                Toast.makeText(this, "Autorise « Installer des applis inconnues », puis reviens toucher Installer",
                        Toast.LENGTH_LONG).show();
                Updater.askInstallPermission(this);
                return;
            }
            busy[0] = true;
            sub.setText("Téléchargement… 0 %");
            Updater.install(this, info, new Updater.Progress() {
                @Override
                public void update(int percent) {
                    sub.setText(percent >= 100 ? "Installation…" : "Téléchargement… " + percent + " %");
                }

                @Override
                public void failed(String error) {
                    busy[0] = false;
                    sub.setText("Échec : " + error + " · toucher pour réessayer");
                }
            });
        });
        addRow(r);
        note("La nouvelle version est téléchargée depuis GitHub et installée par Android, qui "
                + "demande une confirmation. La première fois, autorise « Installer des applis "
                + "inconnues » pour Mini Launcher. Si l'installation est bloquée, désactive l'analyse "
                + "Play Protect le temps de la mise à jour. Le launcher redémarre ensuite tout seul.");
        check.run();
    }

    private void header() {
        TextView title = text("Mini Launcher", 30, TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(px(6), px(8), 0, 0);
        page.addView(title);
        TextView sub = text("Réglages · version " + Store.version(this), 15, MUTED);
        sub.setPadding(px(6), px(2), 0, px(6));
        page.addView(sub);
    }

    private void section(String name) {
        TextView t = text(name.toUpperCase(), 12, accent);
        t.setLetterSpacing(0.08f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(px(14), px(22), 0, px(8));
        page.addView(t);

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(px(20));
        card.setBackground(bg);
        card.setClipToOutline(true);
        page.addView(card);

        footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        page.addView(footer);
    }

    private void note(String s) {
        TextView t = text(s, 13, MUTED);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(px(14), px(8), px(14), 0);
        footer.addView(t);
    }

    private void addRow(View row) {
        if (card.getChildCount() > 0) {
            View line = new View(this);
            line.setBackgroundColor(DIVIDER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, px(0.7f)));
            lp.leftMargin = px(18);
            card.addView(line, lp);
        }
        card.addView(row);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** Une ligne : titre (et sous-titre) à gauche, un élément à droite. */
    private LinearLayout row(String title, String subtitle, int titleColor) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(px(58));
        r.setPadding(px(18), px(12), px(16), px(12));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(title, 16, titleColor));
        if (subtitle != null) {
            TextView sub = text(subtitle, 13, MUTED);
            sub.setPadding(0, px(2), 0, 0);
            left.addView(sub);
        }
        r.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return r;
    }

    private void clickable(View v, View.OnClickListener l) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, mask));
        v.setOnClickListener(l);
    }

    private TextView valueText(String s) {
        TextView t = text(s, 15, accent);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(px(170));
        t.setPadding(px(10), 0, 0, 0);
        return t;
    }

    private TextView chevron() {
        TextView t = text("›", 22, 0xFF5C6273);
        t.setPadding(px(8), 0, 0, px(2));
        return t;
    }

    // ------------------------------------------------------------ types de lignes

    interface Toggle {
        void set(boolean on);
    }

    interface IntSetter {
        void set(int v);
    }

    private void action(String title, String subtitle, View.OnClickListener l) {
        LinearLayout r = row(title, subtitle, accent);
        r.addView(chevron());
        clickable(r, l);
        addRow(r);
    }

    private void danger(String title, String subtitle, View.OnClickListener l) {
        LinearLayout r = row(title, subtitle, DANGER);
        clickable(r, l);
        addRow(r);
    }

    private void toggle(String title, String subtitle, boolean value, Toggle onChange) {
        LinearLayout r = row(title, subtitle, TEXT);
        Switch s = new Switch(this);
        s.setChecked(value);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFB8BCC8}));
        s.setTrackTintList(new ColorStateList(states, new int[]{(accent & 0x00FFFFFF) | 0x77000000, 0xFF3A3D48}));
        s.setOnCheckedChangeListener((b, on) -> onChange.set(on));
        r.addView(s);
        clickable(r, v -> s.toggle());
        addRow(r);
    }

    private void slider(String title, int min, int max, int value, String format, IntSetter onChange) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(12), 0, px(8));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(px(18), 0, px(18), 0);
        top.addView(text(title, 16, TEXT), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = text(String.format(format, value), 15, accent);
        top.addView(val);
        box.addView(top);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(Math.max(0, Math.min(max - min, value - min)));
        bar.setProgressTintList(ColorStateList.valueOf(accent));
        bar.setThumbTintList(ColorStateList.valueOf(accent));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A3D48));
        bar.setPadding(px(18), px(10), px(18), px(4));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(String.format(format, p + min));
                if (fromUser) onChange.set(p + min);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(bar);
        addRow(box);
    }

    private void choice(String title, String[] options, int index, IntSetter onChange) {
        LinearLayout r = row(title, null, TEXT);
        TextView val = valueText(options[Math.max(0, Math.min(options.length - 1, index))]);
        r.addView(val);
        r.addView(chevron());
        int[] cur = {index};
        clickable(r, v -> Menus.choose(this, title, options, cur[0], which -> {
            cur[0] = which;
            onChange.set(which);
            val.setText(options[which]);
        }));
        addRow(r);
    }

    private void gestureChoice(int index) {
        String key = Actions.GESTURES[index];
        String title = Actions.GESTURE_NAMES[index];
        LinearLayout r = row(title, null, TEXT);
        TextView val = valueText("");
        r.addView(val);
        r.addView(chevron());
        Runnable update = () -> {
            int action = Actions.actionFor(this, key);
            String t = Actions.NAMES[action];
            if (action == Actions.OPEN_APP) t = appName(store.text(key + "App"));
            if (action == Actions.NONE) t = "—";
            val.setText(t);
            val.setTextColor(action == Actions.NONE ? MUTED : accent);
        };
        update.run();
        clickable(r, v -> Menus.choose(this, title, Actions.NAMES, Actions.actionFor(this, key), which -> {
            if (which == Actions.OPEN_APP) {
                Menus.pickApp(this, "Appli à ouvrir", AppRepository.get(this).cached(), app -> {
                    store.setText(key + "App", Store.key(app.component));
                    store.setNum(key, Actions.OPEN_APP);
                    update.run();
                });
            } else {
                store.setNum(key, which);
                update.run();
                if (which == Actions.LOCK && !Actions.lockAllowed(this)) Actions.lock(this);
            }
        }));
        addRow(r);
    }

    private void drawingRows() {
        java.util.List<Object[]> list = Drawings.entries(this);
        if (list.isEmpty()) {
            LinearLayout r = row("Aucun tracé appris", "Dessine sur l'accueil pour en ajouter", MUTED);
            addRow(r);
            return;
        }
        for (Object[] e : list) {
            String key = (String) e[0];
            int n = (Integer) e[1];
            LinearLayout r = row(appName(key), n + (n > 1 ? " tracés appris" : " tracé appris"), TEXT);
            if (e[2] != null) {
                android.widget.ImageView preview = new android.widget.ImageView(this);
                int size = px(40);
                preview.setImageBitmap(((android.gesture.Gesture) e[2]).toBitmap(size, size, px(4), accent));
                r.addView(preview, new LinearLayout.LayoutParams(size, size));
            }
            clickable(r, v -> Sheet.list(this, appName(key), new String[]{"Oublier ses tracés"}, which -> {
                Drawings.forget(this, key);
                showPage(currentPage);
            }));
            addRow(r);
        }
    }

    private String appName(String key) {
        if (key == null) return "(à choisir)";
        for (AppRepository.AppEntry e : AppRepository.get(this).cached()) {
            if (Store.key(e.component).equals(key)) return e.label;
        }
        return "(introuvable)";
    }

    private View swatch(int color) {
        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(px(1), 0x33FFFFFF);
        dot.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(22), px(22));
        lp.leftMargin = px(10);
        dot.setLayoutParams(lp);
        return dot;
    }

    private void colorChoice(String title, String[] names, int[] colors, int current, IntSetter onChange) {
        LinearLayout r = row(title, null, TEXT);
        View dot = swatch(0xFF000000 | current);
        r.addView(dot);
        r.addView(chevron());
        int[] cur = {current};
        clickable(r, v -> Sheet.colors(this, title, names, colors, cur[0], which -> {
            cur[0] = colors[which];
            onChange.set(colors[which]);
            ((GradientDrawable) dot.getBackground()).setColor(0xFF000000 | colors[which]);
        }));
        addRow(r);
    }

    private void accentChoice() {
        LinearLayout r = row("Couleur d'accent", "Lettres, survol, réglages", TEXT);
        View dot = swatch(accent);
        r.addView(dot);
        r.addView(chevron());
        clickable(r, v -> Sheet.colors(this, "Couleur d'accent", ACCENT_NAMES, ACCENTS, accent, which -> {
            store.setNum(Store.ACCENT, ACCENTS[which]);
            recreate(); // toute la page reprend la nouvelle couleur
        }));
        addRow(r);
    }

    private void versionFooter() {
        String v = "";
        try {
            PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
            v = " " + p.versionName;
        } catch (Exception ignored) {
        }
        TextView t = text("Mini Launcher" + v, 12, 0xFF5C6273);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(28), 0, px(8));
        page.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }
}
