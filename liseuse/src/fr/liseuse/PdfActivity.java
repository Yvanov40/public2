package fr.liseuse;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * Lecteur de PDF « tel quel », entièrement natif : les pages sont rendues par le moteur PDF
 * d'Android sur un fil séparé et dessinées dans une vue qui défile et se zoome.
 * Écran à part : le quitter ne peut jamais bloquer la bibliothèque.
 */
public final class PdfActivity extends Activity {

    static final String EXTRA_ID = "id", EXTRA_TITRE = "titre", EXTRA_PAGE = "page", EXTRA_FRACTION = "fraction",
            EXTRA_NUIT = "nuit", EXTRA_LUM = "lum", EXTRA_CHAUD = "chaud", EXTRA_PLEIN = "plein", EXTRA_ALLUME = "allume";

    private String id;
    private PdfView vue;
    private LinearLayout haut, bas;
    private TextView titre, numero, pastille;
    private View voile, filtreChaud;
    private LinearLayout panneauLum;
    private float lum;   // -1 = automatique, sinon 0..1 (sous 0,2 : mode nuit extra-sombre)
    private int chaud;   // 0..100
    private boolean nuit;
    private boolean barres;
    private float densite;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- moteur PDF (fil dédié)
    private HandlerThread fil;
    private Handler moteur;
    private ParcelFileDescriptor fd;
    private PdfRenderer rendu;
    private volatile int generation;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Intent in = getIntent();
        id = in.getStringExtra(EXTRA_ID);
        nuit = in.getBooleanExtra(EXTRA_NUIT, false);
        densite = getResources().getDisplayMetrics().density;

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0xFF1F1F21);
        w.setNavigationBarColor(0xFF1F1F21);
        if (in.getBooleanExtra(EXTRA_ALLUME, false)) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lum = in.getFloatExtra(EXTRA_LUM, -1f);
        chaud = in.getIntExtra(EXTRA_CHAUD, 0);
        if (in.getBooleanExtra(EXTRA_PLEIN, true)) pleinEcran(true);

        FrameLayout racine = new FrameLayout(this);
        racine.setBackgroundColor(nuit ? 0xFF111111 : 0xFF3A3A3C);
        vue = new PdfView(this);
        racine.addView(vue, new FrameLayout.LayoutParams(-1, -1));

        // voile de nuit et filtre chaud (simples calques semi-transparents)
        filtreChaud = new View(this);
        filtreChaud.setBackgroundColor(0xFFFF8A1E);
        racine.addView(filtreChaud, new FrameLayout.LayoutParams(-1, -1));
        voile = new View(this);
        voile.setBackgroundColor(0xFF000000);
        racine.addView(voile, new FrameLayout.LayoutParams(-1, -1));

        // barre du haut
        haut = new LinearLayout(this);
        haut.setOrientation(LinearLayout.HORIZONTAL);
        haut.setGravity(Gravity.CENTER_VERTICAL);
        haut.setBackgroundColor(0xF21F1F21);
        haut.setPadding(dp(4), dp(28), dp(12), dp(8));
        TextView retour = bouton("‹", 30);
        retour.setPadding(dp(14), 0, dp(14), dp(4));
        retour.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        haut.addView(retour);
        LinearLayout textes = new LinearLayout(this);
        textes.setOrientation(LinearLayout.VERTICAL);
        titre = new TextView(this);
        titre.setTextColor(0xFFEEEEEE);
        titre.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titre.setTypeface(Typeface.DEFAULT_BOLD);
        titre.setSingleLine(true);
        titre.setEllipsize(TextUtils.TruncateAt.END);
        titre.setText(in.getStringExtra(EXTRA_TITRE));
        numero = new TextView(this);
        numero.setTextColor(0xFFAAAAAA);
        numero.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        textes.addView(titre);
        textes.addView(numero);
        haut.addView(textes, new LinearLayout.LayoutParams(0, -2, 1f));
        racine.addView(haut, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // barre du bas
        bas = new LinearLayout(this);
        bas.setOrientation(LinearLayout.HORIZONTAL);
        bas.setBackgroundColor(0xF21F1F21);
        bas.setPadding(dp(6), dp(6), dp(6), dp(20));
        ajouterAction("Aller à…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                allerPage();
            }
        });
        ajouterAction("Ajuster", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                vue.ajuster();
            }
        });
        ajouterAction("Nuit", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nuit = !nuit;
                vue.setNuit(nuit);
            }
        });
        ajouterAction("Luminosité", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                panneauLum.setVisibility(panneauLum.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        ajouterAction("Autre appli", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ouvrirAilleurs();
            }
        });
        racine.addView(bas, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        construirePanneauLumiere(racine);

        // pastille du numéro de page
        pastille = new TextView(this);
        pastille.setTextColor(0xFFFFFFFF);
        pastille.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pastille.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable fond = new GradientDrawable();
        fond.setColor(0xA6000000);
        fond.setCornerRadius(dp(16));
        pastille.setBackground(fond);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pl.bottomMargin = dp(22);
        racine.addView(pastille, pl);
        pastille.setAlpha(0f);

        setContentView(racine);
        appliquerLumiere();
        montrerBarres(false);
        vue.setNuit(nuit);

        // ouverture du PDF sur le fil dédié
        fil = new HandlerThread("liseuse-pdf");
        fil.start();
        moteur = new Handler(fil.getLooper());
        final int page = in.getIntExtra(EXTRA_PAGE, 0);
        final float fraction = in.getFloatExtra(EXTRA_FRACTION, 0f);
        moteur.post(new Runnable() {
            @Override
            public void run() {
                ouvrirMoteur(page, fraction);
            }
        });
    }

    /** Applique la luminosité : écran du téléphone, voile extra-sombre, filtre chaud. */
    private void appliquerLumiere() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float voileAlpha = 0f;
        if (lum < 0) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else {
            float v = lum * 100f;
            if (v >= 20) lp.screenBrightness = 0.01f + (v - 20) / 80f * 0.99f;
            else {
                lp.screenBrightness = 0.01f;
                voileAlpha = (20 - v) / 20f * 0.85f;
            }
        }
        getWindow().setAttributes(lp);
        voile.setAlpha(voileAlpha);
        voile.setVisibility(voileAlpha > 0 ? View.VISIBLE : View.GONE);
        float ac = chaud / 100f * 0.3f;
        filtreChaud.setAlpha(ac);
        filtreChaud.setVisibility(ac > 0 ? View.VISIBLE : View.GONE);
    }

    private void construirePanneauLumiere(FrameLayout racine) {
        panneauLum = new LinearLayout(this);
        panneauLum.setOrientation(LinearLayout.VERTICAL);
        panneauLum.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable fond = new GradientDrawable();
        fond.setColor(0xF2262628);
        fond.setCornerRadius(dp(18));
        panneauLum.setBackground(fond);
        panneauLum.setClickable(true); // les touchers sur le panneau ne traversent pas

        final TextView valLum = new TextView(this);
        final TextView valChaud = new TextView(this);
        final android.widget.SeekBar curseur = new android.widget.SeekBar(this);
        final android.widget.SeekBar curseurChaud = new android.widget.SeekBar(this);
        final android.widget.CheckBox auto = new android.widget.CheckBox(this);

        panneauLum.addView(ligne("Luminosité", curseur, valLum));
        TextView aide = new TextView(this);
        aide.setText("Tout à gauche : mode nuit, plus sombre que le minimum du téléphone.");
        aide.setTextColor(0xFF999999);
        aide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        aide.setPadding(0, 0, 0, dp(10));
        panneauLum.addView(aide);
        panneauLum.addView(ligne("Filtre chaud", curseurChaud, valChaud));
        auto.setText("Luminosité automatique du téléphone");
        auto.setTextColor(0xFFDDDDDD);
        auto.setPadding(dp(4), dp(8), 0, dp(4));
        panneauLum.addView(auto);

        curseur.setMax(100);
        curseur.setProgress(lum < 0 ? 60 : Math.round(lum * 100));
        curseurChaud.setMax(100);
        curseurChaud.setProgress(chaud);
        auto.setChecked(lum < 0);
        valLum.setText(texteLum());
        valChaud.setText(chaud > 0 ? chaud + " %" : "non");

        curseur.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) {
                if (!user) return;
                lum = v / 100f;
                auto.setChecked(false);
                valLum.setText(texteLum());
                appliquerLumiere();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar b) {
            }
        });
        curseurChaud.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar b, int v, boolean user) {
                if (!user) return;
                chaud = v;
                valChaud.setText(chaud > 0 ? chaud + " %" : "non");
                appliquerLumiere();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar b) {
            }
        });
        auto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean oui) {
                lum = oui ? -1f : curseur.getProgress() / 100f;
                valLum.setText(texteLum());
                appliquerLumiere();
            }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        lp.bottomMargin = dp(96);
        panneauLum.setVisibility(View.GONE);
        racine.addView(panneauLum, lp);
    }

    private LinearLayout ligne(String nom, android.widget.SeekBar curseur, TextView valeur) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(nom);
        t.setTextColor(0xFFDDDDDD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        l.addView(t, new LinearLayout.LayoutParams(dp(96), -2));
        l.addView(curseur, new LinearLayout.LayoutParams(0, dp(44), 1f));
        valeur.setTextColor(0xFFAAAAAA);
        valeur.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        valeur.setGravity(Gravity.END);
        l.addView(valeur, new LinearLayout.LayoutParams(dp(70), -2));
        return l;
    }

    private String texteLum() {
        if (lum < 0) return "auto";
        int v = Math.round(lum * 100);
        return v < 20 ? "nuit " + Math.round((20 - v) * 5) + " %" : Math.round((v - 20) * 100 / 80f) + " %";
    }

    private int dp(float v) {
        return Math.round(v * densite);
    }

    private TextView bouton(String texte, float taille) {
        TextView t = new TextView(this);
        t.setText(texte);
        t.setTextColor(0xFFEEEEEE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, taille);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void ajouterAction(String texte, View.OnClickListener l) {
        TextView t = bouton(texte, 13);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        t.setOnClickListener(l);
        bas.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    private boolean plein;
    private static final int MISE_EN_PAGE = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    private static final int CACHE = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;

    /** Plein écran : le contenu passe sous les barres d'Android, qui sont cachées (sauf menus ouverts). */
    private void pleinEcran(boolean oui) {
        plein = oui;
        View d = getWindow().getDecorView();
        int f = d.getSystemUiVisibility();
        f = oui ? f | MISE_EN_PAGE | CACHE : f & ~(MISE_EN_PAGE | CACHE);
        d.setSystemUiVisibility(f);
    }

    /** Barres d'Android (heure, boutons de navigation) visibles ou cachées, sans redimensionner la page. */
    private void barresSysteme(boolean visibles) {
        if (!plein) return;
        View d = getWindow().getDecorView();
        int f = d.getSystemUiVisibility() | MISE_EN_PAGE;
        f = visibles ? f & ~CACHE : f | CACHE;
        d.setSystemUiVisibility(f);
    }

    private int dimSysteme(String nom) {
        int r = getResources().getIdentifier(nom, "dimen", "android");
        return r > 0 ? getResources().getDimensionPixelSize(r) : 0;
    }

    void basculerBarres() {
        if (panneauLum != null && panneauLum.getVisibility() == View.VISIBLE) {
            panneauLum.setVisibility(View.GONE);
            return;
        }
        montrerBarres(!barres);
    }

    private void montrerBarres(boolean v) {
        barres = v;
        // en plein écran, les menus laissent la place aux barres d'Android (heure en haut, navigation en bas)
        int hautSys = plein ? dimSysteme("status_bar_height") : 0;
        int basSys = plein ? dimSysteme("navigation_bar_height") : 0;
        haut.setPadding(dp(4), Math.max(dp(8), hautSys + dp(4)), dp(12), dp(8));
        bas.setPadding(dp(6), dp(6), dp(6), basSys + dp(8));
        FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) panneauLum.getLayoutParams();
        pl.bottomMargin = dp(96) + basSys;
        panneauLum.setLayoutParams(pl);
        barresSysteme(v);
        haut.setVisibility(v ? View.VISIBLE : View.GONE);
        bas.setVisibility(v ? View.VISIBLE : View.GONE);
        if (!v && panneauLum != null) panneauLum.setVisibility(View.GONE);
    }

    void majNumero(int page, int total) {
        String t = (page + 1) + " / " + total;
        numero.setText(t);
        pastille.setText(t);
        pastille.animate().cancel();
        pastille.setAlpha(1f);
        pastille.animate().alpha(0f).setStartDelay(1200).setDuration(300).start();
    }

    private void allerPage() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(vue.pageCourante() + 1));
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Aller à la page (1 à " + vue.total() + ")")
                .setView(e)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Aller", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        try {
                            int p = Integer.parseInt(e.getText().toString().trim()) - 1;
                            vue.allerA(Math.max(0, Math.min(vue.total() - 1, p)), 0f);
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }).show();
    }

    private void ouvrirAilleurs() {
        try {
            Uri u = Uri.parse("content://" + Fichiers.AUTORITE + "/" + id + "/document.pdf");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/pdf");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Ouvrir le PDF avec"));
        } catch (Exception e) {
            Toast.makeText(this, "Aucune autre application ne peut ouvrir ce PDF.", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- retour : on renvoie la position

    @Override
    public void finish() {
        Intent r = new Intent();
        r.putExtra(EXTRA_ID, id);
        r.putExtra(EXTRA_PAGE, vue.pageCourante());
        r.putExtra(EXTRA_FRACTION, vue.fractionCourante());
        r.putExtra("total", vue.total());
        r.putExtra(EXTRA_NUIT, nuit);
        r.putExtra(EXTRA_LUM, lum);
        r.putExtra(EXTRA_CHAUD, chaud);
        setResult(RESULT_OK, r);
        super.finish();
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_BACK) {
            if (panneauLum != null && panneauLum.getVisibility() == View.VISIBLE) panneauLum.setVisibility(View.GONE);
            else finish();
            return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override
    protected void onDestroy() {
        generation++;
        final HandlerThread f = fil;
        if (moteur != null) {
            moteur.post(new Runnable() {
                @Override
                public void run() {
                    fermerMoteur();
                    f.quitSafely();
                }
            });
        }
        vue.liberer();
        super.onDestroy();
    }

    private File fichier() {
        File f = new File(getFilesDir(), "livres/" + id + ".pdf");
        if (!f.exists()) f = new File(getFilesDir(), "sources/" + id + ".pdf");
        return f;
    }

    /** Sur le fil du moteur. */
    private void ouvrirMoteur(final int page, final float fraction) {
        try {
            fd = ParcelFileDescriptor.open(fichier(), ParcelFileDescriptor.MODE_READ_ONLY);
            rendu = new PdfRenderer(fd);
            final int n = rendu.getPageCount();
            if (n == 0) throw new Exception("PDF vide");
            PdfRenderer.Page p = rendu.openPage(0);
            final int w = p.getWidth(), h = p.getHeight();
            p.close();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    vue.demarrer(n, w, h, page, fraction);
                }
            });
            // tailles réelles de toutes les pages, par petits paquets (le rendu reste prioritaire)
            tailles(1, n);
        } catch (final Throwable e) {
            fermerMoteur();
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(PdfActivity.this, "Ce PDF ne peut pas être affiché ici (" + e.getMessage()
                            + "). Essayez « Autre appli ».", Toast.LENGTH_LONG).show();
                    montrerBarres(true);
                }
            });
        }
    }

    private void tailles(final int debut, final int n) {
        if (rendu == null || debut >= n) return;
        final int fin = Math.min(n, debut + 40);
        final int[][] t = new int[fin - debut][];
        try {
            for (int i = debut; i < fin; i++) {
                PdfRenderer.Page p = rendu.openPage(i);
                t[i - debut] = new int[]{p.getWidth(), p.getHeight()};
                p.close();
            }
        } catch (Throwable e) {
            return;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                vue.tailles(debut, t);
            }
        });
        moteur.post(new Runnable() {
            @Override
            public void run() {
                tailles(fin, n);
            }
        });
    }

    private void fermerMoteur() {
        try {
            if (rendu != null) rendu.close();
        } catch (Throwable ignore) {
        }
        try {
            if (fd != null) fd.close();
        } catch (Throwable ignore) {
        }
        rendu = null;
        fd = null;
    }

    /** Demande le rendu d'une page (traité en priorité, abandonné si la page n'est plus utile). */
    void demander(final int page, final int largeur) {
        final int g = generation;
        moteur.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                if (g != generation || rendu == null || !vue.utile(page)) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            vue.abandonnee(page, largeur);
                        }
                    });
                    return;
                }
                Bitmap b = null;
                try {
                    PdfRenderer.Page p = rendu.openPage(page);
                    try {
                        int w = Math.max(64, largeur);
                        int h = Math.max(1, Math.round((float) w * p.getHeight() / Math.max(1, p.getWidth())));
                        if ((long) w * h > 8000000L) {
                            float k = (float) Math.sqrt(8000000.0 / ((long) w * h));
                            w = (int) (w * k);
                            h = (int) (h * k);
                        }
                        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        b.eraseColor(0xFFFFFFFF);
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } finally {
                        p.close();
                    }
                } catch (Throwable e) {
                    if (b != null) b.recycle();
                    b = null;
                }
                final Bitmap fb = b;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (g != generation) {
                            if (fb != null) fb.recycle();
                            return;
                        }
                        vue.pageRendue(page, largeur, fb);
                    }
                });
            }
        });
    }

    // ================================================================ vue des pages

    private final class PdfView extends View {

        private int n;
        private float[] ratios = new float[0];   // hauteur / largeur de chaque page
        private float[] hauts = new float[0];    // position du haut de chaque page (zoom courant)
        private float hauteurTotale;
        private float zoom = 1f;
        private float sx, sy;                    // défilement
        private final float ecart;
        private final OverScroller defil;
        private final GestureDetector gestes;
        private final ScaleGestureDetector pince;
        private final LruCache<Integer, Bitmap> cache;
        private final android.util.SparseIntArray enCours = new android.util.SparseIntArray();
        private final Paint peinture = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint fondPage = new Paint();
        private final Paint texte = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private boolean pret;
        private int pageInitiale;
        private float fractionInitiale;
        private int dernierNumero = -1;

        PdfView(Context c) {
            super(c);
            ecart = 10 * densite;
            defil = new OverScroller(c);
            int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 5, 160L * 1024 * 1024);
            cache = new LruCache<Integer, Bitmap>(max) {
                @Override
                protected int sizeOf(Integer k, Bitmap b) {
                    return b.getByteCount();
                }

                @Override
                protected void entryRemoved(boolean evicted, Integer k, Bitmap ancien, Bitmap nouveau) {
                    if (ancien != nouveau && ancien != null && !ancien.isRecycled()) ancien.recycle();
                }
            };
            texte.setColor(0xFF999999);
            texte.setTextAlign(Paint.Align.CENTER);
            texte.setTextSize(18 * densite);
            gestes = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    defil.forceFinished(true);
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    deplacer(sx + dx, sy + dy);
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    defil.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxX(), 0, (int) maxY());
                    postInvalidateOnAnimation();
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    basculerBarres();
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    zoomer(zoom > 1.2f ? 1f : 2.5f, e.getX(), e.getY());
                    return true;
                }
            });
            pince = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoomer(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector d) {
                    invalidate(); // demandera les pages à la netteté du nouveau zoom
                }
            });
        }

        void setNuit(boolean v) {
            if (v) {
                ColorMatrix m = new ColorMatrix(new float[]{
                        -0.9f, 0, 0, 0, 240,
                        0, -0.9f, 0, 0, 240,
                        0, 0, -0.9f, 0, 240,
                        0, 0, 0, 1, 0});
                peinture.setColorFilter(new ColorMatrixColorFilter(m));
                fondPage.setColor(0xFF2A2A2A);
            } else {
                peinture.setColorFilter(null);
                fondPage.setColor(0xFFFFFFFF);
            }
            ((View) getParent()).setBackgroundColor(v ? 0xFF111111 : 0xFF3A3A3C);
            invalidate();
        }

        int total() {
            return n;
        }

        void demarrer(int n, int w, int h, int page, float fraction) {
            this.n = n;
            ratios = new float[n];
            java.util.Arrays.fill(ratios, (float) h / Math.max(1, w));
            hauts = new float[n];
            pageInitiale = Math.min(n - 1, Math.max(0, page));
            fractionInitiale = fraction;
            pret = true;
            if (getWidth() > 0) placer();
            invalidate();
        }

        void tailles(int debut, int[][] t) {
            if (!pret) return;
            boolean change = false;
            for (int k = 0; k < t.length; k++) {
                if (t[k] == null) continue;
                float r = (float) t[k][1] / Math.max(1, t[k][0]);
                if (Math.abs(r - ratios[debut + k]) > 0.005f) {
                    ratios[debut + k] = r;
                    change = true;
                }
            }
            if (change) {
                int p = pageCourante();
                float f = fractionCourante();
                calculer();
                allerA(p, f);
            }
        }

        private void placer() {
            calculer();
            allerA(pageInitiale, fractionInitiale);
            pageInitiale = 0;
            fractionInitiale = 0;
        }

        private float largeurPage() {
            return (getWidth() - 2 * ecart) * zoom;
        }

        private void calculer() {
            float lp = largeurPage();
            float y = ecart;
            for (int i = 0; i < n; i++) {
                hauts[i] = y;
                y += lp * ratios[i] + ecart;
            }
            hauteurTotale = y;
        }

        private float maxX() {
            return Math.max(0, largeurPage() + 2 * ecart - getWidth());
        }

        private float maxY() {
            return Math.max(0, hauteurTotale - getHeight());
        }

        private void deplacer(float x, float y) {
            sx = Math.max(0, Math.min(maxX(), x));
            sy = Math.max(0, Math.min(maxY(), y));
            invalidate();
        }

        void ajuster() {
            zoomer(1f, getWidth() / 2f, getHeight() / 2f);
        }

        private void zoomer(float z, float fx, float fy) {
            if (!pret) return;
            z = Math.max(1f, Math.min(5f, z));
            float cx = (sx + fx) / zoom, cy = (sy + fy) / zoom;
            zoom = z;
            calculer();
            deplacer(cx * zoom - fx, cy * zoom - fy);
        }

        void allerA(int page, float fraction) {
            if (!pret || n == 0) return;
            page = Math.max(0, Math.min(n - 1, page));
            float h = largeurPage() * ratios[page];
            deplacer(sx, hauts[page] - ecart + fraction * h);
        }

        int pageCourante() {
            if (!pret || n == 0) return 0;
            float y = sy + getHeight() * 0.3f;
            int a = 0, b = n - 1, r = 0;
            while (a <= b) {
                int m = (a + b) >>> 1;
                if (hauts[m] <= y) {
                    r = m;
                    a = m + 1;
                } else b = m - 1;
            }
            return r;
        }

        float fractionCourante() {
            if (!pret || n == 0) return 0f;
            int p = pageCourante();
            float h = largeurPage() * ratios[p];
            return Math.max(0f, Math.min(1f, (sy - hauts[p] + ecart) / Math.max(1f, h)));
        }

        /** La page est-elle encore à l'écran (ou juste à côté) ? Appelé depuis le fil du moteur. */
        boolean utile(int page) {
            int c = pageVisibleCache;
            return Math.abs(page - c) <= 3;
        }

        private volatile int pageVisibleCache;

        void abandonnee(int page, int largeur) {
            if (enCours.get(page, -1) == largeur) enCours.delete(page);
        }

        void pageRendue(int page, int largeur, Bitmap b) {
            if (enCours.get(page, -1) == largeur) enCours.delete(page);
            if (b == null) return;
            cache.put(page, b);
            invalidate();
        }

        void liberer() {
            cache.evictAll();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (!pret) return;
            if (ow == 0) {
                placer();
            } else {
                // rotation : on garde la page en cours
                int p = pageCourante();
                float f = fractionCourante();
                calculer();
                allerA(p, f);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            pince.onTouchEvent(e);
            if (!pince.isInProgress()) gestes.onTouchEvent(e);
            return true;
        }

        @Override
        public void computeScroll() {
            if (defil.computeScrollOffset()) {
                deplacer(defil.getCurrX(), defil.getCurrY());
                postInvalidateOnAnimation();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!pret || n == 0) return;
            float lp = largeurPage();
            float x0 = ecart - sx;
            int cible = Math.min(2000, Math.max(200, ((int) Math.ceil(lp / 200f)) * 200));
            int premiere = pageCourante();
            pageVisibleCache = premiere;
            // recule jusqu'à la première page visible
            int i = premiere;
            while (i > 0 && hauts[i] - sy > 0) i--;
            for (; i < n; i++) {
                float top = hauts[i] - sy;
                if (top > getHeight()) break;
                float h = lp * ratios[i];
                if (top + h < 0) continue;
                dst.set(x0, top, x0 + lp, top + h);
                Bitmap b = cache.get(i);
                if (b != null && !b.isRecycled()) {
                    src.set(0, 0, b.getWidth(), b.getHeight());
                    c.drawBitmap(b, src, dst, peinture);
                } else {
                    c.drawRect(dst, fondPage);
                    c.drawText(String.valueOf(i + 1), dst.centerX(), Math.max(top + 40 * densite, Math.min(top + h / 2, getHeight() / 2f)), texte);
                }
                // demande (ou redemande à meilleure netteté) le rendu de la page
                if ((b == null || b.getWidth() < cible * 0.8f) && enCours.get(i, -1) != cible) {
                    enCours.put(i, cible);
                    demander(i, cible);
                }
            }
            // précharge la page suivante
            int suiv = Math.min(n - 1, i);
            if (suiv < n && cache.get(suiv) == null && enCours.get(suiv, -1) != cible) {
                enCours.put(suiv, cible);
                demander(suiv, cible);
            }
            int p = pageCourante();
            if (p != dernierNumero) {
                dernierNumero = p;
                final int fp = p, fn = n;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        majNumero(fp, fn);
                    }
                });
            }
        }
    }
}
