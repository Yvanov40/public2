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
    private static final int REQ_IMPORT = 1, REQ_EXPORT = 2;

    private WebView web;
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
        prefs = getSharedPreferences("liseuse", MODE_PRIVATE);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        boolean sombre = systemeSombre();
        int fond = sombre ? 0xFF121212 : 0xFFF7F5F0;
        barres(fond, sombre);

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
        setContentView(web);
        web.loadUrl(BASE + "app/index.html");
        recevoir(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        recevoir(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        js("window.app && app.pause && app.pause()");
    }

    @Override
    protected void onDestroy() {
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
        web.evaluateJavascript("window.app && app.retour ? app.retour() : false", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (!"true".equals(v)) MainActivity.super.onBackPressed();
            }
        });
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
            throw new IOException("Les PDF ne sont pas pris en charge : partez du document Word d'origine.");
        String l = nom.toLowerCase(Locale.ROOT);
        if (l.endsWith(".txt") || l.endsWith(".text") || l.endsWith(".md")) return "txt";
        if (f.length() < 20 * 1024 * 1024) {
            // texte brut sans extension ?
            boolean binaire = false;
            for (int i = 0; i < n; i++) if (h[i] == 0) binaire = true;
            if (!binaire) return "txt";
        }
        throw new IOException("Format non reconnu. Choisissez un fichier .docx (Word), .epub ou .txt.");
    }

    /** Convertit (ou copie) le fichier et l'ajoute à la bibliothèque. */
    private JSONObject convertir(File src, String nom, String id, JSONObject ancien) throws Exception {
        String t = type(src, nom);
        File dest = biblio.epub(id);
        File tmp = new File(dest.getPath() + ".tmp");
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
        } else if (req == REQ_EXPORT && exportId != null) {
            final Uri u = data.getData();
            final String id = exportId;
            travail.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        OutputStream o = getContentResolver().openOutputStream(u);
                        InputStream in = new FileInputStream(biblio.epub(id));
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
                            "application/msword", "application/epub+zip", "text/plain",
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
                    i.setType("application/epub+zip");
                    i.putExtra(Intent.EXTRA_TITLE, nomFichierSur(meta.optString("titre", "livre")));
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
                    Uri u = Uri.parse("content://" + Fichiers.AUTORITE + "/" + id + "/" + Uri.encode(nomFichierSur(meta.optString("titre", "livre"))));
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("application/epub+zip");
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

        @JavascriptInterface
        public void pleinEcran(final boolean oui) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    View d = getWindow().getDecorView();
                    int f = d.getSystemUiVisibility();
                    int plein = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                    if (oui) f |= plein;
                    else f &= ~plein;
                    d.setSystemUiVisibility(f);
                }
            });
        }

        @JavascriptInterface
        public void volume(boolean oui) {
            volumeActif = oui;
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
