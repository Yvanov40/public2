package fr.liseuse;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mise à jour de l'édition complète depuis GitHub (l'édition Play Store est mise à jour par le Play Store).
 * Rien n'est installé sans l'accord de l'utilisateur : Android affiche toujours sa propre confirmation.
 */
final class MiseAJour {

    /** Dossier du dépôt qui contient Liseuse.apk et derniere-version.json. */
    static final String DEPOT = "https://raw.githubusercontent.com/Yvanov40/public2/claude/word-epub-reader-apk-ewxbw2/liseuse/";

    interface Suivi {
        void progres(int pourcent);
    }

    private final Activity activite;

    MiseAJour(Activity a) {
        activite = a;
    }

    static int versionInstallee(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static HttpURLConnection ouvrir(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("serveur : code " + code);
        return c;
    }

    /** Lit la dernière version publiée (à appeler hors du fil de l'interface). */
    JSONObject derniere() throws Exception {
        HttpURLConnection c = ouvrir(DEPOT + "derniere-version.json?t=" + System.currentTimeMillis());
        try {
            InputStream in = c.getInputStream();
            try {
                return new JSONObject(new String(EpubBuilder.lireTout(in), "UTF-8"));
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    File fichier() {
        File d = new File(activite.getCacheDir(), "maj");
        if (!d.exists()) d.mkdirs();
        return new File(d, "Liseuse.apk");
    }

    /** Télécharge l'APK et vérifie qu'il s'agit bien d'une version plus récente de cette appli. */
    File telecharger(int versionAttendue, Suivi suivi) throws Exception {
        File f = fichier();
        File tmp = new File(f.getPath() + ".part");
        HttpURLConnection c = ouvrir(DEPOT + "Liseuse.apk?t=" + System.currentTimeMillis());
        try {
            long total = c.getContentLength();
            InputStream in = c.getInputStream();
            OutputStream o = new FileOutputStream(tmp);
            try {
                byte[] b = new byte[65536];
                long lu = 0;
                int n, dernier = -1;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n);
                    lu += n;
                    if (total > 0) {
                        int p = (int) (lu * 100 / total);
                        if (p != dernier) {
                            dernier = p;
                            suivi.progres(p);
                        }
                    }
                }
            } finally {
                o.close();
                in.close();
            }
        } finally {
            c.disconnect();
        }
        PackageInfo pi = activite.getPackageManager().getPackageArchiveInfo(tmp.getPath(), 0);
        if (pi == null || !activite.getPackageName().equals(pi.packageName)) {
            tmp.delete();
            throw new IOException("le fichier téléchargé n'est pas une version de Liseuse");
        }
        if (pi.versionCode <= versionInstallee(activite)) {
            tmp.delete();
            throw new IOException("le fichier téléchargé n'est pas plus récent que la version installée");
        }
        f.delete();
        if (!tmp.renameTo(f)) throw new IOException("enregistrement impossible");
        return f;
    }

    /** Android 8 et plus : l'appli a-t-elle le droit de proposer l'installation d'applis ? */
    boolean installationAutorisee() {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            return (Boolean) PackageManager.class.getMethod("canRequestPackageInstalls").invoke(activite.getPackageManager());
        } catch (Throwable t) {
            return true;
        }
    }

    void ouvrirAutorisation() {
        Intent i = new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + activite.getPackageName()));
        activite.startActivity(i);
    }

    /** Lance l'installateur d'Android (qui demande confirmation à l'utilisateur). */
    void installer() {
        Uri u = Uri.parse("content://" + Fichiers.autorite(activite) + "/maj/Liseuse.apk");
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(u, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activite.startActivity(i);
    }
}
