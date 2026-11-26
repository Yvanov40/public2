package fr.minilauncher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mise à jour depuis l'appli : cherche la dernière version publiée sur GitHub, la télécharge
 * et la passe à l'installateur d'Android (qui demande une confirmation).
 */
final class Updater {

    private static final String RELEASES = "https://api.github.com/repos/Yvanov40/public2/releases?per_page=30";
    private static final String TAG_PREFIX = "mini-launcher-v";

    static final class Info {
        String version;
        String url;
        long size;
        int run;
    }

    interface CheckCallback {
        /** info == null : erreur (message dans error). */
        void done(Info info, String error);
    }

    interface Progress {
        void update(int percent);

        void failed(String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updater() {
    }

    /** Cherche la plus récente version publiée (sur un fil à part). */
    static void check(CheckCallback cb) {
        new Thread(() -> {
            try {
                HttpURLConnection c = open(RELEASES);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("GitHub répond " + code);
                String body = readAll(c.getInputStream());
                JSONArray arr = new JSONArray(body);
                Info best = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject r = arr.getJSONObject(i);
                    if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                    String tag = r.optString("tag_name");
                    if (!tag.startsWith(TAG_PREFIX)) continue;
                    int run;
                    try {
                        run = Integer.parseInt(tag.substring(TAG_PREFIX.length()));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    JSONArray assets = r.optJSONArray("assets");
                    if (assets == null) continue;
                    for (int k = 0; k < assets.length(); k++) {
                        JSONObject as = assets.getJSONObject(k);
                        String name = as.optString("name");
                        if (!name.startsWith("MiniLauncher") || !name.endsWith(".apk")) continue;
                        if (best != null && best.run >= run) continue;
                        Info in = new Info();
                        in.run = run;
                        in.url = as.optString("browser_download_url");
                        in.size = as.optLong("size");
                        String v = name.substring("MiniLauncher".length(), name.length() - 4);
                        in.version = v.startsWith("-") ? v.substring(1) : v;
                        best = in;
                    }
                }
                Info found = best;
                MAIN.post(() -> {
                    if (found == null) cb.done(null, "Aucune version publiée trouvée");
                    else cb.done(found, null);
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                MAIN.post(() -> cb.done(null, "Pas de connexion à GitHub (" + msg + ")"));
            }
        }).start();
    }

    /** true si la version publiée est plus récente que celle installée. */
    static boolean newer(String remote, String local) {
        // « 5.6 (build 46) » : seul « 5.6 » compte.
        String[] a = remote.trim().split("\\s")[0].split("\\."), b = local.trim().split("\\s")[0].split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** L'appli doit avoir le droit « Installer des applis inconnues ». */
    static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    static void askInstallPermission(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        }
    }

    /** Télécharge l'APK directement dans une session d'installation, puis la valide. */
    static void install(Context ctx, Info info, Progress p) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                int id = pi.createSession(params);
                session = pi.openSession(id);

                HttpURLConnection c = open(info.url);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("téléchargement : code " + code);
                long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : info.size;
                try (InputStream in = c.getInputStream();
                     OutputStream out = session.openWrite("base.apk", 0, total > 0 ? total : -1)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int last = -1, n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        int pct = total > 0 ? (int) (done * 100 / total) : 0;
                        if (pct != last) {
                            last = pct;
                            int shown = pct;
                            MAIN.post(() -> p.update(shown));
                        }
                    }
                    session.fsync(out);
                }
                Intent done = new Intent(app, InstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pending = PendingIntent.getBroadcast(app, id, done, flags);
                session.commit(pending.getIntentSender());
                session.close();
            } catch (Exception e) {
                if (session != null) session.abandon();
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                MAIN.post(() -> p.failed(msg));
            }
        }).start();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "MiniLauncher-updater");
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream s = in) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** Réponse de l'installateur : demander la confirmation, ou dire pourquoi ça a échoué. */
    public static final class InstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        c.startActivity(confirm);
                    } catch (Exception e) {
                        Toast.makeText(c, "Impossible d'ouvrir l'installateur", Toast.LENGTH_LONG).show();
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                String why = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "annulée"
                        : status == PackageInstaller.STATUS_FAILURE_BLOCKED ? "bloquée (Play Protect ?)"
                        : status == PackageInstaller.STATUS_FAILURE_CONFLICT ? "en conflit (signature différente ?)"
                        : status == PackageInstaller.STATUS_FAILURE_STORAGE ? "manque de place"
                        : "refusée";
                Toast.makeText(c, "Mise à jour " + why + (msg != null ? " : " + msg : ""),
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
