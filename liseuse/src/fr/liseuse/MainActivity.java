package fr.liseuse;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MainActivity extends Activity {

    static final String HOTE = "appassets.androidplatform.net";
    static final String BASE = "https://" + HOTE + "/";
    static final int MISE_EN_PAGE = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
    static final int CACHE = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
    private static final int REQ_IMPORT = 1, REQ_EXPORT = 2, REQ_IMAGE = 3, REQ_DOSSIER = 4, REQ_PDF = 5;
    private org.json.JSONArray exportIds;
    static final int NB_ICONES = 8;
    private String nomRaccourci = "Liseuse";

    private WebView web;
    private Achats achats;
    private android.widget.FrameLayout racine;
    private WindowInsets dernierInsets;
    private boolean lecturePlein;
    private Bibliotheque biblio;
    private SharedPreferences prefs;
    private final ExecutorService travail = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, ZipFile> zips = new HashMap<String, ZipFile>();
    private final List<String> enAttente = new ArrayList<String>();
    private boolean pret;
    private boolean volumeActif;
    private String exportId;

    // ================================================================ cycle de vie

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        biblio = new Bibliotheque(getFilesDir());
        achats = Achats.creer(this, new Achats.Ecouteur() {
            @Override
            public void changement(boolean pro, String prix, String message) {
                evenement(obj("type", "pro", "actif", pro, "prix", prix == null ? "" : prix,
                        "message", message == null ? "" : message, "edition", achats == null ? "" : achats.edition()));
            }
        });
        prefs = getSharedPreferences("liseuse", MODE_PRIVATE);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        boolean sombre = systemeSombre();
        int fond = sombre ? 0xFF121212 : 0xFFF7F5F0;
        barres(fond, sombre);

        creerWeb(fond);
        web.loadUrl(BASE + "app/index.html");
        recevoir(getIntent());
    }

    private void creerWeb(int fond) {
        web = new WebView(this);
        web.setBackgroundColor(fond);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Pont(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new Client());
        if (racine == null) {
            racine = new android.widget.FrameLayout(this);
            racine.setBackgroundColor(fond);
            // bord à bord (obligatoire pour les versions récentes d'Android) : la page passe sous les barres,
            // et on lui laisse la place des barres sauf en lecture plein écran
            racine.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets ins) {
                    dernierInsets = ins;
                    appliquerMarges();
                    return ins.consumeSystemWindowInsets();
                }
            });
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(d.getSystemUiVisibility() | MISE_EN_PAGE);
            setContentView(racine);
        }
        racine.addView(web, new android.widget.FrameLayout.LayoutParams(-1, -1));
    }

    private void appliquerMarges() {
        if (racine == null) return;
        if (lecturePlein || dernierInsets == null) racine.setPadding(0, 0, 0, 0);
        else racine.setPadding(dernierInsets.getSystemWindowInsetLeft(), dernierInsets.getSystemWindowInsetTop(),
                dernierInsets.getSystemWindowInsetRight(), dernierInsets.getSystemWindowInsetBottom());
    }

    /**
     * Sortie de secours : si l'interface ne répond plus (page figée), on recrée entièrement
     * la WebView et on revient à la bibliothèque, sans rouvrir le livre en cours.
     */
    private void secours() {
        generationPdf++;
        filPdf.execute(new Runnable() {
            @Override
            public void run() {
                fermerMoteurPdf();
            }
        });
        WebView ancienne = web;
        racine.removeView(ancienne);
        pret = false;
        enAttente.clear();
        boolean sombre = systemeSombre();
        creerWeb(sombre ? 0xFF121212 : 0xFFF7F5F0);
        try {
            ancienne.stopLoading();
            ancienne.destroy();
        } catch (Throwable ignore) {
        }
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility((d.getSystemUiVisibility() & ~CACHE) | MISE_EN_PAGE);
        lecturePlein = false;
        appliquerMarges();
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        web.loadUrl(BASE + "app/index.html?accueil=1");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        recevoir(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) js("window.app && app.reprise && app.reprise()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        js("window.app && app.pause && app.pause()");
    }

    @Override
    protected void onDestroy() {
        if (achats != null) achats.fermer();
        generationPdf++;
        filPdf.execute(new Runnable() {
            @Override
            public void run() {
                fermerMoteurPdf();
            }
        });
        filPdf.shutdown();
        for (ZipFile z : zips.values()) {
            try {
                z.close();
            } catch (IOException ignore) {
            }
        }
        zips.clear();
        travail.shutdown();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        js("window.app && app.systeme && app.systeme(" + (systemeSombre() ? "true" : "false") + ")");
    }

    @Override
    public void onBackPressed() {
        final boolean[] repondu = {false};
        final WebView cible = web;
        cible.evaluateJavascript("window.app && app.retour ? app.retour() : false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                repondu[0] = true;
                if (cible != web) return;
                if (!"true".equals(v)) MainActivity.super.onBackPressed();
            }
        });
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!repondu[0] && cible == web) {
                    toast("L'affichage ne répondait plus : retour à la bibliothèque.");
                    secours();
                }
            }
        }, 1200);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (volumeActif && (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                js("window.lecteur && lecteur.tourner(" + (k == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1) + ")");
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean systemeSombre() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    /** Envoie un évènement à l'interface (mis en attente tant qu'elle n'est pas prête). */
    private void evenement(JSONObject o) {
        final String s = o.toString();
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (pret) web.evaluateJavascript("app.evenement(" + s + ")", null);
                else enAttente.add(s);
            }
        });
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignore) {
        }
        return o;
    }

    private void barres(int couleur, boolean sombre) {
        Window w = getWindow();
        if (racine != null) racine.setBackgroundColor(couleur);
        w.setStatusBarColor(couleur);
        w.setNavigationBarColor(couleur);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility();
        if (sombre) {
            f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            f &= ~0x10; // SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= 0x10;
        }
        d.setSystemUiVisibility(f);
    }

    // ================================================================ fichiers reçus

    private void recevoir(Intent i) {
        if (i == null) return;
        List<Uri> uris = new ArrayList<Uri>();
        String a = i.getAction();
        if (Intent.ACTION_VIEW.equals(a) && i.getData() != null) uris.add(i.getData());
        else if (Intent.ACTION_SEND.equals(a)) {
            Parcelable p = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uris.add((Uri) p);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Parcelable> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) for (Parcelable p : l) if (p instanceof Uri) uris.add((Uri) p);
        }
        i.setAction(null);
        for (Uri u : uris) importer(u, uris.size() == 1);
    }

    private String nomAffiche(Uri u) {
        String nom = null;
        try {
            Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) nom = c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignore) {
        }
        if (nom == null) nom = u.getLastPathSegment();
        if (nom == null) nom = "document";
        int s = nom.lastIndexOf('/');
        if (s >= 0) nom = nom.substring(s + 1);
        return nom;
    }

    private DocxConverter.Options options() {
        DocxConverter.Options o = new DocxConverter.Options();
        try {
            JSONObject j = new JSONObject(prefs.getString("conversion", "{}"));
            o.niveauChapitre = j.optInt("niveauChapitre", o.niveauChapitre);
            o.detecterChapitres = j.optBoolean("detecterChapitres", o.detecterChapitres);
            o.sautsDePage = j.optBoolean("sautsDePage", o.sautsDePage);
            o.compresserImages = j.optBoolean("compresserImages", o.compresserImages);
            o.couvertureImage = j.optBoolean("couvertureImage", o.couvertureImage);
        } catch (Exception ignore) {
        }
        return o;
    }

    private void importer(final Uri u, final boolean ouvrir) {
        final String nom = nomAffiche(u);
        evenement(obj("type", "debut", "nom", nom));
        travail.execute(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(getCacheDir(), "import-" + System.nanoTime());
                try {
                    ContentResolver cr = getContentResolver();
                    InputStream in = cr.openInputStream(u);
                    if (in == null) throw new IOException("Impossible de lire ce fichier.");
                    copier(in, tmp);
                    String id = Bibliotheque.nouvelId();
                    if (type(tmp, nom).equals("pdf")) {
                        File pdf = new File(getCacheDir(), "pdf-" + id);
                        if (!tmp.renameTo(pdf)) throw new IOException("Copie du PDF impossible.");
                        demarrerPdf(pdf, nom, id, null, ouvrir, true, "pdf-choix");
                        return;
                    }
                    JSONObject livre = convertir(tmp, nom, id, null);
                    evenement(obj("type", "fini", "id", id, "titre", livre.optString("titre"), "ouvrir", ouvrir));
                } catch (Throwable t) {
                    evenement(obj("type", "erreur", "nom", nom, "message", message(t)));
                } finally {
                    tmp.delete();
                }
            }
        });
    }

    private static String message(Throwable t) {
        if (t instanceof OutOfMemoryError) return "Document trop volumineux pour la mémoire du téléphone.";
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        if (t instanceof java.util.zip.ZipException) m = "Fichier abîmé ou incomplet.";
        if (t instanceof org.xmlpull.v1.XmlPullParserException) m = "Le contenu du document est illisible (" + m + ").";
        return m;
    }

    private static void copier(InputStream in, File f) throws IOException {
        OutputStream o = new FileOutputStream(f);
        try {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        } finally {
            o.close();
            in.close();
        }
    }

    private static String type(File f, String nom) throws IOException {
        byte[] h = new byte[8];
        InputStream in = new FileInputStream(f);
        int n;
        try {
            n = in.read(h);
        } finally {
            in.close();
        }
        if (n >= 4 && h[0] == 'P' && h[1] == 'K') {
            ZipFile z = new ZipFile(f);
            try {
                if (z.getEntry("word/document.xml") != null) return "docx";
                if (z.getEntry("META-INF/container.xml") != null) return "epub";
                ZipEntry ct = z.getEntry("[Content_Types].xml");
                if (ct != null) {
                    String s = new String(EpubBuilder.lireTout(z.getInputStream(ct)), "UTF-8");
                    if (s.contains("wordprocessingml")) return "docx";
                    if (s.contains("presentationml")) throw new IOException("C'est une présentation PowerPoint, pas un document Word.");
                    if (s.contains("spreadsheetml")) throw new IOException("C'est un tableur Excel, pas un document Word.");
                }
                if (z.getEntry("content.xml") != null)
                    throw new IOException("Format OpenDocument (.odt) : enregistrez-le en .docx depuis LibreOffice ou Word.");
                if (z.getEntry("mimetype") != null) return "epub";
            } finally {
                z.close();
            }
            throw new IOException("Archive inconnue : ce n'est ni un .docx ni un .epub.");
        }
        if (n >= 4 && (h[0] & 0xff) == 0xD0 && (h[1] & 0xff) == 0xCF && (h[2] & 0xff) == 0x11 && (h[3] & 0xff) == 0xE0)
            throw new IOException("Ancien format Word (.doc) : ouvrez-le dans Word et enregistrez-le en .docx.");
        if (n >= 5 && h[0] == '{' && h[1] == '\\' && h[2] == 'r' && h[3] == 't' && h[4] == 'f')
            throw new IOException("Format RTF : enregistrez-le en .docx depuis Word.");
        if (n >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F')
            return "pdf";
        String l = nom.toLowerCase(Locale.ROOT);
        if (l.endsWith(".txt") || l.endsWith(".text") || l.endsWith(".md")) return "txt";
        if (f.length() < 20 * 1024 * 1024) {
            // texte brut sans extension ?
            boolean binaire = false;
            for (int i = 0; i < n; i++) if (h[i] == 0) binaire = true;
            if (!binaire) return "txt";
        }
        throw new IOException("Format non reconnu. Choisissez un fichier .docx (Word), .pdf, .epub ou .txt.");
    }

    /** Convertit (ou copie) le fichier et l'ajoute à la bibliothèque. */
    private JSONObject convertir(File src, String nom, String id, JSONObject ancien) throws Exception {
        String t = type(src, nom);
        File tmp = new File(biblio.epub(id).getPath() + ".tmp");
        AndroidImages images = new AndroidImages();
        if (t.equals("epub")) {
            copier(new FileInputStream(src), tmp);
        } else {
            OutputStream o = new FileOutputStream(tmp);
            try {
                if (t.equals("docx")) new DocxConverter(src, images, options()).convertir(o, nom);
                else TxtConverter.convertir(src, o, nom, images);
            } finally {
                o.close();
            }
        }
        return enregistrer(tmp, src, t, nom, id, ancien);
    }

    /** Valide l'EPUB produit, le range dans la bibliothèque, garde la source et la couverture. */
    private JSONObject enregistrer(File tmp, File src, String t, String nom, String id, JSONObject ancien) throws Exception {
        File dest = biblio.epub(id);
        EpubLivre l;
        try {
            l = EpubLivre.lire(tmp);
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
        fermerZip(id);
        dest.delete();
        if (!tmp.renameTo(dest)) throw new IOException("Enregistrement impossible.");
        if (!t.equals("epub") && (ancien == null)) {
            copier(new FileInputStream(src), biblio.source(id, t));
        }
        // couverture pour la bibliothèque
        File couv = biblio.couverture(id);
        couv.delete();
        if (l.couverture != null) {
            byte[] c = EpubLivre.lireEntree(dest, l.couverture);
            if (c != null) {
                OutputStream o = new FileOutputStream(couv);
                try {
                    o.write(c);
                } finally {
                    o.close();
                }
            }
        }
        JSONObject livre = ancien != null ? ancien : new JSONObject();
        livre.put("id", id);
        if (ancien == null || !ancien.optBoolean("titreModifie")) livre.put("titre", l.titre);
        if (ancien == null || !ancien.optBoolean("titreModifie")) livre.put("auteur", l.auteur);
        livre.put("format", t);
        livre.put("nomFichier", nom);
        livre.put("taille", dest.length());
        livre.put("chapitres", l.spine.size());
        livre.put("couverture", couv.exists());
        livre.put("sourceExt", t.equals("epub") ? "" : t);
        livre.put("convertiLe", System.currentTimeMillis());
        if (ancien == null) livre.put("ajoute", System.currentTimeMillis());
        biblio.ajouter(livre);
        synchronized (infos) {
            infos.remove(id);
        }
        return livre;
    }

    // ================================================================ PDF tel quel : rendu natif page par page
    // Tout le travail sur le PDF se fait sur un seul fil d'arrière-plan ; l'interface ne l'attend jamais.
    // Les pages demandées puis devenues inutiles (on a défilé plus loin) sont abandonnées.

    private final ExecutorService filPdf = Executors.newSingleThreadExecutor();
    private volatile int generationPdf;
    private final java.util.Set<Integer> pagesVoulues = java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    private String renduId;
    private android.os.ParcelFileDescriptor renduFd;
    private android.graphics.pdf.PdfRenderer rendu;

    private File fichierPdf(String id) {
        File f = biblio.pdf(id);
        if (!f.exists()) f = biblio.source(id, "pdf");
        return f;
    }

    private File dossierPages() {
        File d = new File(getCacheDir(), "pdfpages");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** À n'appeler que sur filPdf. */
    private android.graphics.pdf.PdfRenderer moteurPdf(String id) throws IOException {
        if (rendu != null && id.equals(renduId)) return rendu;
        fermerMoteurPdf();
        File f = fichierPdf(id);
        if (!f.exists()) throw new IOException("PDF introuvable.");
        renduFd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            rendu = new android.graphics.pdf.PdfRenderer(renduFd);
        } catch (IOException e) {
            renduFd.close();
            renduFd = null;
            throw e;
        } catch (SecurityException e) {
            renduFd.close();
            renduFd = null;
            throw new IOException("PDF protégé par un mot de passe.");
        }
        renduId = id;
        return rendu;
    }

    /** À n'appeler que sur filPdf. */
    private void fermerMoteurPdf() {
        if (rendu != null) {
            try {
                rendu.close();
            } catch (Exception ignore) {
            }
        }
        if (renduFd != null) {
            try {
                renduFd.close();
            } catch (IOException ignore) {
            }
        }
        rendu = null;
        renduFd = null;
        renduId = null;
    }

    private void viderPages() {
        File[] l = dossierPages().listFiles();
        if (l != null) for (File f : l) f.delete();
    }

    /** Rend une page en JPEG dans le cache (sur filPdf). Renvoie {largeur, hauteur} de la page en points. */
    private int[] rendrePage(String id, int i, int largeur, File sortie) throws IOException {
        android.graphics.pdf.PdfRenderer r = moteurPdf(id);
        if (i < 0 || i >= r.getPageCount()) throw new IOException("page hors du document");
        android.graphics.pdf.PdfRenderer.Page pg = r.openPage(i);
        android.graphics.Bitmap b = null;
        try {
            int pw = pg.getWidth(), ph = pg.getHeight();
            int w = Math.max(100, Math.min(2000, largeur));
            int h = Math.max(1, Math.round((float) w * ph / Math.max(1, pw)));
            if ((long) w * h > 6000000L) { // limite mémoire (pages très allongées)
                float k = (float) Math.sqrt(6000000.0 / ((long) w * h));
                w = Math.max(1, (int) (w * k));
                h = Math.max(1, (int) (h * k));
            }
            b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFFFFFFFF);
            pg.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            File tmp = new File(sortie.getPath() + ".tmp");
            OutputStream o = new FileOutputStream(tmp);
            try {
                b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, o);
            } finally {
                o.close();
            }
            if (!tmp.renameTo(sortie)) throw new IOException("écriture impossible");
            return new int[]{pw, ph};
        } finally {
            pg.close();
            if (b != null) b.recycle();
        }
    }

    // ================================================================ PDF (converti par pdf.js dans la WebView)

    private static final class TravailPdf {
        File src, sortie;
        String nom, id;
        JSONObject ancien;
        boolean ouvrir, temporaire;
        OutputStream os;
        EpubBuilder ep;
    }

    private final Map<String, TravailPdf> pdfs = new HashMap<String, TravailPdf>();

    private void demarrerPdf(File src, String nom, String id, JSONObject ancien, boolean ouvrir, boolean temporaire) {
        demarrerPdf(src, nom, id, ancien, ouvrir, temporaire, "pdf");
    }

    private void demarrerPdf(File src, String nom, String id, JSONObject ancien, boolean ouvrir, boolean temporaire, String type) {
        TravailPdf t = new TravailPdf();
        t.src = src;
        t.nom = nom;
        t.id = id;
        t.ancien = ancien;
        t.ouvrir = ouvrir;
        t.temporaire = temporaire;
        synchronized (pdfs) {
            pdfs.put(id, t);
        }
        JSONObject o = obj("type", type, "jeton", id, "nom", nom, "ouvrir", ouvrir, "taille", src.length());
        try {
            JSONObject op = new JSONObject(prefs.getString("conversion", "{}"));
            o.put("options", op);
        } catch (Exception ignore) {
        }
        evenement(o);
    }

    private TravailPdf pdf(String jeton) {
        synchronized (pdfs) {
            return pdfs.get(jeton);
        }
    }

    private void finPdf(TravailPdf t) {
        synchronized (pdfs) {
            pdfs.remove(t.id);
        }
        if (t.os != null) {
            try {
                t.os.close();
            } catch (IOException ignore) {
            }
        }
        if (t.temporaire) t.src.delete();
    }

    private void fermerZip(String id) {
        synchronized (zips) {
            ZipFile z = zips.remove(id);
            if (z != null) {
                try {
                    z.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    private final Map<String, String> infos = new HashMap<String, String>();

    // ================================================================ résultats d'activités

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_IMPORT) {
            List<Uri> l = new ArrayList<Uri>();
            ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) l.add(cd.getItemAt(i).getUri());
            else if (data.getData() != null) l.add(data.getData());
            for (Uri u : l) importer(u, l.size() == 1);
        } else if (req == REQ_PDF) {
            JSONObject o = obj("id", data.getStringExtra(PdfActivity.EXTRA_ID),
                    "c", data.getIntExtra(PdfActivity.EXTRA_PAGE, 0),
                    "f", (double) data.getFloatExtra(PdfActivity.EXTRA_FRACTION, 0f),
                    "n", data.getIntExtra("total", 0),
                    "nuit", data.getBooleanExtra(PdfActivity.EXTRA_NUIT, false),
                    "lum", (double) data.getFloatExtra(PdfActivity.EXTRA_LUM, -1f),
                    "chaud", data.getIntExtra(PdfActivity.EXTRA_CHAUD, 0));
            js("window.app && app.pdfFerme && app.pdfFerme(" + o + ")");
        } else if (req == REQ_DOSSIER && data.getData() != null && exportIds != null) {
            final Uri arbre = data.getData();
            final org.json.JSONArray ids = exportIds;
            travail.execute(new Runnable() {
                @Override
                public void run() {
                    int ok = 0;
                    try {
                        Uri parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(arbre,
                                android.provider.DocumentsContract.getTreeDocumentId(arbre));
                        for (int i = 0; i < ids.length(); i++) {
                            String id = ids.optString(i);
                            JSONObject meta = biblio.trouver(id);
                            File f = fichierLivre(id);
                            if (meta == null || !f.exists()) continue;
                            String nom = nomPartage(id, meta);
                            Uri doc = android.provider.DocumentsContract.createDocument(getContentResolver(), parent,
                                    original(id) ? "application/pdf" : "application/epub+zip", nom);
                            if (doc == null) continue;
                            OutputStream o = getContentResolver().openOutputStream(doc);
                            InputStream in = new FileInputStream(f);
                            byte[] b = new byte[65536];
                            int n;
                            while ((n = in.read(b)) > 0) o.write(b, 0, n);
                            in.close();
                            o.close();
                            ok++;
                        }
                        toast(ok + " livre" + (ok > 1 ? "s enregistrés" : " enregistré"));
                    } catch (Exception e) {
                        toast("Échec de l'enregistrement : " + message(e));
                    }
                }
            });
        } else if (req == REQ_IMAGE && data.getData() != null) {
            creerRaccourci(data.getData());
        } else if (req == REQ_EXPORT && exportId != null) {
            final Uri u = data.getData();
            final String id = exportId;
            travail.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        OutputStream o = getContentResolver().openOutputStream(u);
                        InputStream in = new FileInputStream(fichierLivre(id));
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                        in.close();
                        o.close();
                        toast("EPUB enregistré");
                    } catch (Exception e) {
                        toast("Échec de l'enregistrement : " + message(e));
                    }
                }
            });
        }
    }

    /** Raccourci sur l'écran d'accueil avec l'image et le nom choisis (Android 8 et plus). */
    private void creerRaccourci(Uri u) {
        try {
            InputStream in = getContentResolver().openInputStream(u);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            byte[] data = EpubBuilder.lireTout(in);
            in.close();
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int ech = 1;
            while (Math.min(o.outWidth, o.outHeight) / (ech * 2) >= 256) ech *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = ech;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b == null) throw new IOException("Image illisible.");
            int c = Math.min(b.getWidth(), b.getHeight());
            android.graphics.Bitmap carre = android.graphics.Bitmap.createBitmap(b, (b.getWidth() - c) / 2, (b.getHeight() - c) / 2, c, c);
            android.graphics.Bitmap icone = android.graphics.Bitmap.createScaledBitmap(carre, 192, 192, true);
            Class<?> smc = Class.forName("android.content.pm.ShortcutManager");
            Object sm = getSystemService(smc);
            boolean ok = (Boolean) smc.getMethod("isRequestPinShortcutSupported").invoke(sm);
            if (!ok) {
                toast("Votre écran d'accueil n'accepte pas les raccourcis personnalisés.");
                return;
            }
            Class<?> bc = Class.forName("android.content.pm.ShortcutInfo$Builder");
            Object bu = bc.getConstructor(Context.class, String.class).newInstance(this, "perso-" + System.currentTimeMillis());
            bc.getMethod("setShortLabel", CharSequence.class).invoke(bu, nomRaccourci);
            bc.getMethod("setIcon", android.graphics.drawable.Icon.class).invoke(bu, android.graphics.drawable.Icon.createWithBitmap(icone));
            Intent lance = new Intent(Intent.ACTION_MAIN);
            lance.setClass(this, MainActivity.class);
            bc.getMethod("setIntent", Intent.class).invoke(bu, lance);
            Object info = bc.getMethod("build").invoke(bu);
            smc.getMethod("requestPinShortcut", Class.forName("android.content.pm.ShortcutInfo"), android.content.IntentSender.class).invoke(sm, info, null);
        } catch (Throwable e) {
            toast("Raccourci impossible : " + message(e));
        }
    }

    private void toast(final String m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String nomFichierSur(String titre) {
        String s = titre.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = "livre";
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s + ".epub";
    }

    private static String nomFichierSur(String titre, String ext) {
        String n = nomFichierSur(titre);
        return n.substring(0, n.length() - 5) + ext;
    }

    /** Fichier à partager / enregistrer : l'EPUB, ou le PDF gardé tel quel. */
    private File fichierLivre(String id) {
        File e = biblio.epub(id);
        if (e.exists()) return e;
        return biblio.pdf(id);
    }

    private boolean original(String id) {
        return !biblio.epub(id).exists() && biblio.pdf(id).exists();
    }

    private String nomPartage(String id, JSONObject meta) {
        return nomFichierSur(meta.optString("titre", "livre"), original(id) ? ".pdf" : ".epub");
    }

    /** Range un PDF tel quel dans la bibliothèque (sans conversion). */
    private JSONObject garderPdf(File src, boolean deplacer, String nom, String id) throws Exception {
        File dest = biblio.pdf(id);
        if (!(deplacer && src.renameTo(dest))) copier(new FileInputStream(src), dest);
        int pages = 0;
        File couv = biblio.couverture(id);
        couv.delete();
        android.os.ParcelFileDescriptor fd = null;
        android.graphics.pdf.PdfRenderer r = null;
        try {
            fd = android.os.ParcelFileDescriptor.open(dest, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            r = new android.graphics.pdf.PdfRenderer(fd);
            pages = r.getPageCount();
            if (pages > 0) {
                android.graphics.pdf.PdfRenderer.Page pg = r.openPage(0);
                int w = 600, h = Math.max(1, Math.round(600f * pg.getHeight() / Math.max(1, pg.getWidth())));
                android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                b.eraseColor(0xFFFFFFFF);
                pg.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                pg.close();
                OutputStream o = new FileOutputStream(couv);
                try {
                    b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o);
                } finally {
                    o.close();
                }
                b.recycle();
            }
        } catch (Throwable e) {
            // PDF protégé ou illisible par Android : il sera tout de même lu par pdf.js
        } finally {
            if (r != null) r.close();
            if (fd != null) {
                try {
                    fd.close();
                } catch (IOException ignore) {
                }
            }
        }
        JSONObject livre = new JSONObject();
        livre.put("id", id);
        livre.put("titre", DocxConverter.sansExtension(nom));
        livre.put("auteur", "");
        livre.put("format", "pdf");
        livre.put("original", true);
        livre.put("nomFichier", nom);
        livre.put("taille", dest.length());
        livre.put("pages", pages);
        livre.put("couverture", couv.exists());
        livre.put("sourceExt", "");
        livre.put("convertiLe", System.currentTimeMillis());
        livre.put("ajoute", System.currentTimeMillis());
        biblio.ajouter(livre);
        return livre;
    }

    // ================================================================ service des fichiers à la WebView

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!HOTE.equals(u.getHost())) return null;
            String p = u.getPath();
            if (p == null) p = "/";
            try {
                if (p.startsWith("/app/")) {
                    String chemin = "www/" + p.substring(5);
                    if (chemin.contains("..")) return introuvable();
                    return reponse(mime(chemin, false), getAssets().open(chemin));
                }
                if (p.startsWith("/livre/")) {
                    String reste = p.substring(7);
                    int s = reste.indexOf('/');
                    if (s <= 0) return introuvable();
                    String id = reste.substring(0, s);
                    String chemin = reste.substring(s + 1);
                    ZipFile z = zip(id);
                    if (z == null) return introuvable();
                    ZipEntry e = z.getEntry(chemin);
                    if (e == null) return introuvable();
                    boolean html = u.getQueryParameter("html") != null;
                    return reponse(mime(chemin, html), z.getInputStream(e));
                }
                if (p.startsWith("/pdfcache/")) {
                    String nom = p.substring(10).replaceAll("[^0-9a-z.\\-]", "");
                    File f = new File(dossierPages(), nom);
                    if (!f.exists()) return introuvable();
                    return reponse("image/jpeg", new FileInputStream(f));
                }
                if (p.startsWith("/pdf/")) {
                    String id = p.substring(5).replaceAll("[^0-9a-f]", "");
                    File f = biblio.pdf(id);
                    if (!f.exists()) f = biblio.source(id, "pdf");
                    if (!f.exists()) return introuvable();
                    return morceauFichier(f, "application/pdf", r.getRequestHeaders());
                }
                if (p.startsWith("/import/")) {
                    TravailPdf t = pdf(p.substring(8));
                    if (t == null || !t.src.exists()) return introuvable();
                    return reponse("application/pdf", new FileInputStream(t.src));
                }
                if (p.startsWith("/icone/")) {
                    String n = p.substring(7).replaceAll("[^0-9]", "");
                    int[] icones = {R.mipmap.ic_icone1, R.mipmap.ic_icone2, R.mipmap.ic_icone3, R.mipmap.ic_icone4,
                            R.mipmap.ic_icone5, R.mipmap.ic_icone6, R.mipmap.ic_icone7, R.mipmap.ic_icone8};
                    int k = n.isEmpty() ? 0 : Integer.parseInt(n);
                    int res = k >= 1 && k <= 8 ? icones[k - 1] : 0;
                    if (res == 0) return introuvable();
                    return reponse("image/png", getResources().openRawResource(res));
                }
                if (p.startsWith("/couv/")) {
                    String id = p.substring(6).replaceAll("[^0-9a-f]", "");
                    File f = biblio.couverture(id);
                    if (!f.exists()) return introuvable();
                    return reponse("image/*", new FileInputStream(f));
                }
            } catch (Exception e) {
                return introuvable();
            }
            return introuvable();
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            Uri u = Uri.parse(url);
            if (HOTE.equals(u.getHost())) return false;
            ouvrirLien(u.toString());
            return true;
        }
    }

    private ZipFile zip(String id) throws IOException {
        synchronized (zips) {
            ZipFile z = zips.get(id);
            if (z == null) {
                File f = biblio.epub(id);
                if (!f.exists()) return null;
                z = new ZipFile(f);
                zips.put(id, z);
            }
            return z;
        }
    }

    private static WebResourceResponse reponse(String mime, InputStream in) {
        WebResourceResponse r = new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("xml") || mime.contains("javascript") ? "utf-8" : null, in);
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cache-Control", "no-cache");
        h.put("Access-Control-Allow-Origin", "*");
        r.setResponseHeaders(h);
        return r;
    }

    /** Réponse HTTP avec prise en charge des requêtes partielles (Range: bytes=a-b). */
    private static WebResourceResponse morceauFichier(File f, String mime, Map<String, String> entetes) throws IOException {
        long total = f.length();
        String plage = null;
        if (entetes != null) {
            for (Map.Entry<String, String> e : entetes.entrySet()) {
                if ("range".equalsIgnoreCase(e.getKey())) plage = e.getValue();
            }
        }
        Map<String, String> h = new HashMap<String, String>();
        h.put("Accept-Ranges", "bytes");
        h.put("Cache-Control", "no-cache");
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Access-Control-Expose-Headers", "Accept-Ranges, Content-Range, Content-Length");
        if (plage != null && plage.startsWith("bytes=")) {
            try {
                String[] ab = plage.substring(6).split(",")[0].trim().split("-", -1);
                long a, b;
                if (ab[0].isEmpty()) {
                    a = Math.max(0, total - Long.parseLong(ab[1]));
                    b = total - 1;
                } else {
                    a = Long.parseLong(ab[0]);
                    b = ab.length > 1 && !ab[1].isEmpty() ? Math.min(total - 1, Long.parseLong(ab[1])) : total - 1;
                }
                if (a <= b && a < total) {
                    int n = (int) Math.min(b - a + 1, 16L * 1024 * 1024);
                    byte[] buf = new byte[n];
                    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
                    try {
                        raf.seek(a);
                        raf.readFully(buf);
                    } finally {
                        raf.close();
                    }
                    h.put("Content-Range", "bytes " + a + "-" + (a + n - 1) + "/" + total);
                    h.put("Content-Length", String.valueOf(n));
                    return new WebResourceResponse(mime, null, 206, "Partial Content", h, new ByteArrayInputStream(buf));
                }
            } catch (NumberFormatException ignore) {
            }
        }
        h.put("Content-Length", String.valueOf(total));
        return new WebResourceResponse(mime, null, 200, "OK", h, new FileInputStream(f));
    }

    private static WebResourceResponse introuvable() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    static String mime(String chemin, boolean html) {
        String l = chemin.toLowerCase(Locale.ROOT);
        if (l.endsWith(".xhtml") || l.endsWith(".xht") || l.endsWith(".xml")) return html ? "text/html" : "application/xhtml+xml";
        if (l.endsWith(".html") || l.endsWith(".htm")) return "text/html";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".js")) return "application/javascript";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".woff")) return "font/woff";
        if (l.endsWith(".ttf")) return "font/ttf";
        if (l.endsWith(".otf")) return "font/otf";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }

    private void ouvrirLien(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Aucune application pour ouvrir ce lien.");
        }
    }

    // ================================================================ pont JavaScript

    private final class Pont {

        @JavascriptInterface
        public void pret() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    pret = true;
                    for (String s : enAttente) web.evaluateJavascript("app.evenement(" + s + ")", null);
                    enAttente.clear();
                }
            });
        }

        @JavascriptInterface
        public String systeme() {
            return obj("sombre", systemeSombre(), "version", BuildConfig.VERSION, "android", Build.VERSION.SDK_INT).toString();
        }

        @JavascriptInterface
        public String livres() {
            return biblio.json();
        }

        @JavascriptInterface
        public String livre(String id) {
            synchronized (infos) {
                String s = infos.get(id);
                if (s != null) return s;
            }
            try {
                JSONObject o = EpubLivre.lire(biblio.epub(id)).json();
                o.put("id", id);
                JSONObject meta = biblio.trouver(id);
                if (meta != null && meta.optBoolean("titreModifie")) {
                    o.put("titre", meta.optString("titre"));
                    o.put("auteur", meta.optString("auteur"));
                }
                String s = o.toString();
                synchronized (infos) {
                    infos.put(id, s);
                }
                return s;
            } catch (Exception e) {
                return obj("erreur", message(e)).toString();
            }
        }

        @JavascriptInterface
        public void options(String json) {
            prefs.edit().putString("conversion", json).apply();
        }

        @JavascriptInterface
        public void importer() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/msword", "application/epub+zip", "text/plain", "application/pdf",
                            "application/octet-stream", "application/zip", "application/x-zip-compressed"});
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(i, REQ_IMPORT);
                    } catch (Exception e) {
                        toast("Aucun gestionnaire de fichiers disponible.");
                    }
                }
            });
        }

        @JavascriptInterface
        public void reconvertir(final String id) {
            final JSONObject meta = biblio.trouver(id);
            if (meta == null) return;
            final String ext = meta.optString("sourceExt", "");
            final File src = biblio.source(id, ext);
            if (ext.isEmpty() || !src.exists()) {
                evenement(obj("type", "erreur", "nom", meta.optString("titre"), "message", "Le document d'origine n'est pas disponible pour ce livre."));
                return;
            }
            evenement(obj("type", "debut", "nom", meta.optString("nomFichier", meta.optString("titre"))));
            if (ext.equals("pdf")) {
                demarrerPdf(src, meta.optString("nomFichier", "document.pdf"), id, meta, false, false);
                return;
            }
            travail.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        convertir(src, meta.optString("nomFichier", "document." + ext), id, meta);
                        evenement(obj("type", "reconverti", "id", id, "titre", meta.optString("titre")));
                    } catch (Throwable t) {
                        evenement(obj("type", "erreur", "nom", meta.optString("titre"), "message", message(t)));
                    }
                }
            });
        }

        @JavascriptInterface
        public void supprimer(String id) {
            fermerZip(id);
            synchronized (infos) {
                infos.remove(id);
            }
            biblio.supprimer(id);
        }

        @JavascriptInterface
        public void renommer(String id, String titre, String auteur) {
            biblio.modifier(id, "titre", titre);
            biblio.modifier(id, "auteur", auteur);
            biblio.modifier(id, "titreModifie", true);
            synchronized (infos) {
                infos.remove(id);
            }
        }

        @JavascriptInterface
        public void exporter(final String id) {
            final JSONObject meta = biblio.trouver(id);
            if (meta == null) return;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    exportId = id;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(original(id) ? "application/pdf" : "application/epub+zip");
                    i.putExtra(Intent.EXTRA_TITLE, nomPartage(id, meta));
                    try {
                        startActivityForResult(i, REQ_EXPORT);
                    } catch (Exception e) {
                        toast("Enregistrement impossible sur cet appareil.");
                    }
                }
            });
        }

        @JavascriptInterface
        public void partager(final String id) {
            final JSONObject meta = biblio.trouver(id);
            if (meta == null) return;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    Uri u = Uri.parse("content://" + Fichiers.autorite(MainActivity.this) + "/" + id + "/" + Uri.encode(nomPartage(id, meta)));
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(original(id) ? "application/pdf" : "application/epub+zip");
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    i.putExtra(Intent.EXTRA_SUBJECT, meta.optString("titre"));
                    i.setClipData(ClipData.newRawUri("", u));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(i, "Partager le livre"));
                    } catch (Exception e) {
                        toast("Aucune application pour partager.");
                    }
                }
            });
        }

        /** Ouvre le lecteur PDF natif (écran séparé de la WebView). */
        @JavascriptInterface
        public void ouvrirPdfNatif(final String id, final String titre, final String reglages) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject r = new JSONObject(reglages == null ? "{}" : reglages);
                        Intent i = new Intent(MainActivity.this, PdfActivity.class);
                        i.putExtra(PdfActivity.EXTRA_ID, id);
                        i.putExtra(PdfActivity.EXTRA_TITRE, titre);
                        i.putExtra(PdfActivity.EXTRA_PAGE, r.optInt("c", 0));
                        i.putExtra(PdfActivity.EXTRA_FRACTION, (float) r.optDouble("f", 0));
                        i.putExtra(PdfActivity.EXTRA_NUIT, r.optBoolean("nuit", false));
                        i.putExtra(PdfActivity.EXTRA_LUM, (float) r.optDouble("lum", -1));
                        i.putExtra(PdfActivity.EXTRA_CHAUD, r.optInt("chaud", 0));
                        i.putExtra(PdfActivity.EXTRA_PLEIN, r.optBoolean("plein", true));
                        i.putExtra(PdfActivity.EXTRA_ALLUME, r.optBoolean("allume", false));
                        startActivityForResult(i, REQ_PDF);
                    } catch (Exception e) {
                        toast("Ouverture du PDF impossible : " + message(e));
                    }
                }
            });
        }

        /** Ouvre le PDF (tel quel ou d'origine) dans une autre application de lecture. */
        @JavascriptInterface
        public void ouvrirAilleurs(final String id) {
            final JSONObject meta = biblio.trouver(id);
            if (meta == null) return;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        File f = fichierPdf(id);
                        if (!f.exists()) {
                            toast("PDF introuvable.");
                            return;
                        }
                        Uri u = Uri.parse("content://" + Fichiers.autorite(MainActivity.this) + "/" + id + "/"
                                + Uri.encode(nomFichierSur(meta.optString("titre", "document"), ".pdf")));
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(u, "application/pdf");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Ouvrir le PDF avec"));
                    } catch (Exception e) {
                        toast("Aucune autre application ne peut ouvrir ce PDF.");
                    }
                }
            });
        }

        @JavascriptInterface
        public void partagerPlusieurs(final String idsJson) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        org.json.JSONArray ids = new org.json.JSONArray(idsJson);
                        ArrayList<Uri> uris = new ArrayList<Uri>();
                        ClipData clip = null;
                        for (int i = 0; i < ids.length(); i++) {
                            String id = ids.optString(i);
                            JSONObject meta = biblio.trouver(id);
                            if (meta == null) continue;
                            Uri u = Uri.parse("content://" + Fichiers.autorite(MainActivity.this) + "/" + id + "/" + Uri.encode(nomPartage(id, meta)));
                            uris.add(u);
                            if (clip == null) clip = ClipData.newRawUri("", u);
                            else clip.addItem(new ClipData.Item(u));
                        }
                        if (uris.isEmpty()) return;
                        Intent i = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
                        i.setType("*/*");
                        if (uris.size() == 1) i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                        else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                        i.setClipData(clip);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Partager " + uris.size() + " livre" + (uris.size() > 1 ? "s" : "")));
                    } catch (Exception e) {
                        toast("Partage impossible : " + message(e));
                    }
                }
            });
        }

        @JavascriptInterface
        public void exporterPlusieurs(final String idsJson) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        exportIds = new org.json.JSONArray(idsJson);
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DOSSIER);
                    } catch (Exception e) {
                        toast("Enregistrement impossible sur cet appareil.");
                    }
                }
            });
        }

        @JavascriptInterface
        public void lien(String url) {
            ouvrirLien(url);
        }

        @JavascriptInterface
        public void pressePapiers(String texte) {
            final String t = texte;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("texte", t));
                }
            });
        }

        @JavascriptInterface
        public void afficher(String m) {
            toast(m);
        }

        @JavascriptInterface
        public void couleurs(final String fond, final boolean sombre) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        int c = Color.parseColor(fond);
                        barres(c, sombre);
                        web.setBackgroundColor(c);
                    } catch (Exception ignore) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void luminosite(final float v) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.01f, Math.min(1f, v));
                    getWindow().setAttributes(lp);
                }
            });
        }

        @JavascriptInterface
        public void ecranAllume(final boolean oui) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (oui) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        /**
         * Mode lecture plein écran : la page passe sous les barres d'Android (taille stable),
         * qui sont cachées ; barresSysteme(true) les fait réapparaître avec les menus.
         */
        @JavascriptInterface
        public void modeLecture(final boolean oui) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    View d = getWindow().getDecorView();
                    int f = d.getSystemUiVisibility() | MISE_EN_PAGE;
                    d.setSystemUiVisibility(oui ? f | CACHE : f & ~CACHE);
                    lecturePlein = oui;
                    appliquerMarges();
                }
            });
        }

        @JavascriptInterface
        public void barresSysteme(final boolean visibles) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    View d = getWindow().getDecorView();
                    int f = d.getSystemUiVisibility();
                    if (!lecturePlein) return; // pas en plein écran
                    d.setSystemUiVisibility(visibles ? f & ~CACHE : f | CACHE);
                }
            });
        }

        /** Hauteur des barres d'Android, en pixels CSS. */
        @JavascriptInterface
        public String tailleBarres() {
            float k = getResources().getDisplayMetrics().density;
            int h = getResources().getIdentifier("status_bar_height", "dimen", "android");
            int b = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            return obj("haut", h > 0 ? getResources().getDimensionPixelSize(h) / k : 24,
                    "bas", b > 0 ? getResources().getDimensionPixelSize(b) / k : 48).toString();
        }

        @JavascriptInterface
        public void pleinEcran(final boolean oui) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    View d = getWindow().getDecorView();
                    int f = d.getSystemUiVisibility();
                    d.setSystemUiVisibility(oui ? f | CACHE | MISE_EN_PAGE : (f & ~CACHE) | MISE_EN_PAGE);
                    lecturePlein = oui;
                    appliquerMarges();
                }
            });
        }

        @JavascriptInterface
        public void volume(boolean oui) {
            volumeActif = oui;
        }

        // ---------------------------------------------------------------- construction d'un EPUB depuis un PDF

        @JavascriptInterface
        public boolean pdfCommencer(String jeton, String titre, String auteur, String langue) {
            TravailPdf t = pdf(jeton);
            if (t == null) return false;
            try {
                t.sortie = new File(biblio.epub(t.id).getPath() + ".tmp");
                t.os = new FileOutputStream(t.sortie);
                t.ep = new EpubBuilder(t.os);
                if (titre != null && !titre.trim().isEmpty()) t.ep.titre = titre.trim();
                else t.ep.titre = DocxConverter.sansExtension(t.nom);
                t.ep.auteur = auteur == null ? "" : auteur.trim();
                if (langue != null && !langue.isEmpty()) t.ep.langue = langue;
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void pdfChapitre(String jeton, String titre) {
            TravailPdf t = pdf(jeton);
            if (t != null && t.ep != null && !t.ep.chapitreVide()) t.ep.nouveauChapitre(titre == null || titre.isEmpty() ? null : titre);
        }

        @JavascriptInterface
        public void pdfHtml(String jeton, String html) {
            TravailPdf t = pdf(jeton);
            if (t == null || t.ep == null || html == null || html.isEmpty()) return;
            t.ep.html().append(html);
            t.ep.marquerContenu();
        }

        @JavascriptInterface
        public int pdfTaille(String jeton) {
            TravailPdf t = pdf(jeton);
            return t == null || t.ep == null ? 0 : t.ep.tailleChapitre();
        }

        @JavascriptInterface
        public void pdfToc(String jeton, int niveau, String titre, String id) {
            TravailPdf t = pdf(jeton);
            if (t != null && t.ep != null) t.ep.entreeToc(niveau, titre, id);
        }

        @JavascriptInterface
        public String pdfImage(String jeton, String base64, String ext) {
            TravailPdf t = pdf(jeton);
            if (t == null || t.ep == null) return "";
            try {
                byte[] d = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                return t.ep.ajouterImage(d, ext);
            } catch (Throwable e) {
                return "";
            }
        }

        @JavascriptInterface
        public void pdfCouverture(String jeton, String href) {
            TravailPdf t = pdf(jeton);
            if (t != null && t.ep != null && href != null && !href.isEmpty()) t.ep.couverture = href;
        }

        /** Ouvre un PDF tel quel ; la réponse arrive par visionneusePdf.infos(...). */
        @JavascriptInterface
        public void pdfOuvrir(final String id) {
            final int g = ++generationPdf;
            pagesVoulues.clear();
            filPdf.execute(new Runnable() {
                @Override
                public void run() {
                    if (g != generationPdf) return;
                    try {
                        viderPages();
                        android.graphics.pdf.PdfRenderer r = moteurPdf(id);
                        int n = r.getPageCount();
                        int w = 595, h = 842;
                        if (n > 0) {
                            android.graphics.pdf.PdfRenderer.Page pg = r.openPage(0);
                            w = pg.getWidth();
                            h = pg.getHeight();
                            pg.close();
                        }
                        js("window.visionneusePdf && visionneusePdf.infos(" + g + "," + obj("pages", n, "l", w, "h", h) + ")");
                    } catch (Throwable e) {
                        fermerMoteurPdf();
                        js("window.visionneusePdf && visionneusePdf.infos(" + g + "," + obj("erreur", message(e)) + ")");
                    }
                }
            });
        }

        /** Pages actuellement à l'écran (ou presque) : les autres demandes sont abandonnées. */
        @JavascriptInterface
        public void pdfVoulues(String json) {
            try {
                org.json.JSONArray a = new org.json.JSONArray(json);
                java.util.Set<Integer> n = new java.util.HashSet<Integer>();
                for (int i = 0; i < a.length(); i++) n.add(a.optInt(i));
                synchronized (pagesVoulues) {
                    pagesVoulues.clear();
                    pagesVoulues.addAll(n);
                }
            } catch (Exception ignore) {
            }
        }

        /** Demande le rendu d'une page ; la réponse arrive par visionneusePdf.pagePrete(...). */
        @JavascriptInterface
        public void pdfPage(final String id, final int i, final int largeur) {
            final int g = generationPdf;
            filPdf.execute(new Runnable() {
                @Override
                public void run() {
                    if (g != generationPdf) return;
                    if (!pagesVoulues.isEmpty() && !pagesVoulues.contains(i)) {
                        js("window.visionneusePdf && visionneusePdf.pagePrete(" + g + "," + i + "," + largeur + ",null)");
                        return;
                    }
                    String nom = id + "-" + i + "-" + largeur + ".jpg";
                    File f = new File(dossierPages(), nom);
                    try {
                        int[] t = f.exists() ? null : rendrePage(id, i, largeur, f);
                        js("window.visionneusePdf && visionneusePdf.pagePrete(" + g + "," + i + "," + largeur + ","
                                + obj("url", "/pdfcache/" + nom, "l", t == null ? 0 : t[0], "h", t == null ? 0 : t[1]) + ")");
                    } catch (Throwable e) {
                        js("window.visionneusePdf && visionneusePdf.pagePrete(" + g + "," + i + "," + largeur + "," + obj("erreur", message(e)) + ")");
                    }
                }
            });
        }

        @JavascriptInterface
        public void pdfFermer() {
            generationPdf++;
            pagesVoulues.clear();
            filPdf.execute(new Runnable() {
                @Override
                public void run() {
                    fermerMoteurPdf();
                    viderPages();
                }
            });
        }

        @JavascriptInterface
        public long pdfTailleFichier(String id) {
            return fichierPdf(id).length();
        }

        /** L'utilisateur a choisi de convertir le PDF : on lance la conversion. */
        @JavascriptInterface
        public void pdfConvertir(String jeton) {
            TravailPdf t = pdf(jeton);
            if (t == null) return;
            JSONObject o = obj("type", "pdf", "jeton", t.id, "nom", t.nom);
            try {
                o.put("options", new JSONObject(prefs.getString("conversion", "{}")));
            } catch (Exception ignore) {
            }
            evenement(o);
        }

        /** L'utilisateur a choisi de garder le PDF tel quel. */
        @JavascriptInterface
        public void pdfGarder(final String jeton) {
            final TravailPdf t = pdf(jeton);
            if (t == null) return;
            travail.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject l = garderPdf(t.src, t.temporaire, t.nom, t.id);
                        evenement(obj("type", "fini", "id", t.id, "titre", l.optString("titre"), "ouvrir", t.ouvrir));
                    } catch (Throwable e) {
                        evenement(obj("type", "erreur", "nom", t.nom, "message", message(e)));
                    } finally {
                        synchronized (pdfs) {
                            pdfs.remove(t.id);
                        }
                        if (t.temporaire) t.src.delete();
                    }
                }
            });
        }

        /** Convertit en EPUB un PDF qui avait été gardé tel quel. */
        @JavascriptInterface
        public void pdfVersEpub(final String id) {
            final JSONObject meta = biblio.trouver(id);
            if (meta == null || !biblio.pdf(id).exists()) return;
            evenement(obj("type", "debut", "nom", meta.optString("nomFichier", meta.optString("titre"))));
            travail.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        File copie = new File(getCacheDir(), "pdf-" + id);
                        copier(new FileInputStream(biblio.pdf(id)), copie);
                        demarrerPdf(copie, meta.optString("nomFichier", "document.pdf"), id, meta, false, true);
                    } catch (Throwable e) {
                        evenement(obj("type", "erreur", "nom", meta.optString("titre"), "message", message(e)));
                    }
                }
            });
        }

        @JavascriptInterface
        public void pdfFinir(final String jeton) {
            final TravailPdf t = pdf(jeton);
            if (t == null || t.ep == null) return;
            travail.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] couv = t.ep.couverture == null ? new AndroidImages().couverture(t.ep.titre, t.ep.auteur) : null;
                        t.ep.finir(couv);
                        t.os.close();
                        t.os = null;
                        boolean etaitOriginal = t.ancien != null && t.ancien.optBoolean("original");
                        JSONObject l = enregistrer(t.sortie, t.src, "pdf", t.nom, t.id, t.ancien);
                        if (etaitOriginal) {
                            File orig = biblio.pdf(t.id), src = biblio.source(t.id, "pdf");
                            src.delete();
                            if (!orig.renameTo(src)) copier(new FileInputStream(orig), src);
                            orig.delete();
                            l.put("original", false);
                            biblio.ajouter(l);
                        }
                        if (t.ancien != null) evenement(obj("type", "reconverti", "id", t.id, "titre", l.optString("titre")));
                        else evenement(obj("type", "fini", "id", t.id, "titre", l.optString("titre"), "ouvrir", t.ouvrir));
                    } catch (Throwable e) {
                        if (t.sortie != null) t.sortie.delete();
                        evenement(obj("type", "erreur", "nom", t.nom, "message", message(e)));
                    } finally {
                        finPdf(t);
                    }
                }
            });
        }

        @JavascriptInterface
        public void pdfEchec(String jeton, String message) {
            TravailPdf t = pdf(jeton);
            if (t == null) return;
            if (t.sortie != null) {
                if (t.os != null) {
                    try {
                        t.os.close();
                    } catch (IOException ignore) {
                    }
                    t.os = null;
                }
                t.sortie.delete();
            }
            finPdf(t);
            evenement(obj("type", "erreur", "nom", t.nom, "message", message == null ? "PDF illisible." : message));
        }

        // ---------------------------------------------------------------- icône de l'application

        @JavascriptInterface
        public int icone() {
            android.content.pm.PackageManager pm = getPackageManager();
            for (int i = 1; i <= NB_ICONES; i++) {
                int e = pm.getComponentEnabledSetting(new android.content.ComponentName(MainActivity.this, "fr.liseuse.Icone" + i));
                if (e == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i;
                if (i == 1 && e == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    boolean autre = false;
                    for (int k = 2; k <= NB_ICONES; k++) {
                        if (pm.getComponentEnabledSetting(new android.content.ComponentName(MainActivity.this, "fr.liseuse.Icone" + k))
                                == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED) autre = true;
                    }
                    if (!autre) return 1;
                }
            }
            return 1;
        }

        @JavascriptInterface
        public void changerIcone(int n) {
            if (n < 1 || n > NB_ICONES) return;
            android.content.pm.PackageManager pm = getPackageManager();
            // on active d'abord la nouvelle, puis on désactive les autres
            pm.setComponentEnabledSetting(new android.content.ComponentName(MainActivity.this, "fr.liseuse.Icone" + n),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP);
            for (int i = 1; i <= NB_ICONES; i++) {
                if (i == n) continue;
                pm.setComponentEnabledSetting(new android.content.ComponentName(MainActivity.this, "fr.liseuse.Icone" + i),
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP);
            }
        }

        @JavascriptInterface
        public boolean raccourciPossible() {
            return Build.VERSION.SDK_INT >= 26;
        }

        @JavascriptInterface
        public void raccourciPerso(final String nom) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    nomRaccourci = nom == null || nom.trim().isEmpty() ? "Liseuse" : nom.trim();
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    try {
                        startActivityForResult(i, REQ_IMAGE);
                    } catch (Exception e) {
                        toast("Aucune application pour choisir une image.");
                    }
                }
            });
        }

        // ---------------------------------------------------------------- mise à jour (édition complète)

        @JavascriptInterface
        public String versionInstallee() {
            return obj("code", MiseAJour.versionInstallee(MainActivity.this), "nom", BuildConfig.VERSION).toString();
        }

        /** Cherche une nouvelle version ; réponse par l'évènement « maj ». */
        @JavascriptInterface
        public void verifierMaj(final boolean manuel) {
            if (!"complete".equals(achats.edition())) return; // l'édition Play Store passe par le Play Store
            travail.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject d = new MiseAJour(MainActivity.this).derniere();
                        int code = d.optInt("versionCode", 0);
                        boolean dispo = code > MiseAJour.versionInstallee(MainActivity.this);
                        evenement(obj("type", "maj", "disponible", dispo, "code", code, "nom", d.optString("versionName"),
                                "notes", d.optString("notes"), "manuel", manuel));
                    } catch (Throwable e) {
                        evenement(obj("type", "maj", "erreur", "Impossible de joindre GitHub (" + message(e) + ").", "manuel", manuel));
                    }
                }
            });
        }

        /** Télécharge puis propose l'installation de la nouvelle version. */
        @JavascriptInterface
        public void installerMaj(final int code) {
            if (!"complete".equals(achats.edition())) return;
            final MiseAJour m = new MiseAJour(MainActivity.this);
            travail.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        m.telecharger(code, new MiseAJour.Suivi() {
                            @Override
                            public void progres(int p) {
                                evenement(obj("type", "maj-progres", "pourcent", p));
                            }
                        });
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!m.installationAutorisee()) {
                                    evenement(obj("type", "maj-autorisation"));
                                    return;
                                }
                                try {
                                    m.installer();
                                    evenement(obj("type", "maj-prete"));
                                } catch (Exception e) {
                                    evenement(obj("type", "maj-erreur", "message", message(e)));
                                }
                            }
                        });
                    } catch (Throwable e) {
                        evenement(obj("type", "maj-erreur", "message", message(e)));
                    }
                }
            });
        }

        /** Après avoir autorisé l'installation : relance l'installateur avec le fichier déjà téléchargé. */
        @JavascriptInterface
        public void reprendreMaj() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    MiseAJour m = new MiseAJour(MainActivity.this);
                    if (!m.fichier().exists()) {
                        evenement(obj("type", "maj-erreur", "message", "fichier de mise à jour introuvable, recommencez"));
                    } else if (!m.installationAutorisee()) {
                        evenement(obj("type", "maj-autorisation"));
                    } else {
                        m.installer();
                        evenement(obj("type", "maj-prete"));
                    }
                }
            });
        }

        @JavascriptInterface
        public void autoriserInstallation() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        new MiseAJour(MainActivity.this).ouvrirAutorisation();
                    } catch (Exception e) {
                        toast("Ouvrez Paramètres → Applications → Liseuse → Installer des applis inconnues.");
                    }
                }
            });
        }

        // ---------------------------------------------------------------- version Pro

        @JavascriptInterface
        public String pro() {
            return obj("actif", achats.estPro(), "prix", achats.prix(), "edition", achats.edition()).toString();
        }

        @JavascriptInterface
        public void acheterPro() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    achats.acheter();
                }
            });
        }

        @JavascriptInterface
        public void restaurerPro() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    achats.restaurer();
                }
            });
        }

        @JavascriptInterface
        public void quitter() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }
    }
}
