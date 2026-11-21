package fr.liseuse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;

/** Liste des livres, enregistrée dans un fichier JSON. */
public final class Bibliotheque {

    private final File racine;
    private final File fichier;
    private JSONArray livres;

    public Bibliotheque(File racine) {
        this.racine = racine;
        this.fichier = new File(racine, "bibliotheque.json");
        dossier("livres");
        dossier("sources");
        dossier("couv");
        charger();
    }

    private File dossier(String n) {
        File d = new File(racine, n);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public File epub(String id) {
        return new File(racine, "livres/" + id + ".epub");
    }

    public File couverture(String id) {
        return new File(racine, "couv/" + id);
    }

    public File source(String id, String ext) {
        return new File(racine, "sources/" + id + "." + ext);
    }

    private synchronized void charger() {
        livres = new JSONArray();
        if (!fichier.exists()) return;
        try {
            InputStream in = new FileInputStream(fichier);
            try {
                livres = new JSONArray(new String(EpubBuilder.lireTout(in), "UTF-8"));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            livres = new JSONArray();
        }
    }

    private synchronized void sauver() {
        try {
            File tmp = new File(racine, "bibliotheque.json.tmp");
            OutputStream o = new FileOutputStream(tmp);
            try {
                o.write(livres.toString().getBytes("UTF-8"));
            } finally {
                o.close();
            }
            if (!tmp.renameTo(fichier)) {
                fichier.delete();
                tmp.renameTo(fichier);
            }
        } catch (IOException ignore) {
        }
    }

    public synchronized String json() {
        return livres.toString();
    }

    public synchronized JSONObject trouver(String id) {
        for (int i = 0; i < livres.length(); i++) {
            JSONObject o = livres.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    public static String nouvelId() {
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public synchronized void ajouter(JSONObject o) {
        String id = o.optString("id");
        for (int i = 0; i < livres.length(); i++) {
            JSONObject x = livres.optJSONObject(i);
            if (x != null && id.equals(x.optString("id"))) {
                try {
                    livres.put(i, o);
                } catch (Exception ignore) {
                }
                sauver();
                return;
            }
        }
        livres.put(o);
        sauver();
    }

    public synchronized void modifier(String id, String cle, Object valeur) {
        JSONObject o = trouver(id);
        if (o == null) return;
        try {
            o.put(cle, valeur);
        } catch (Exception ignore) {
        }
        sauver();
    }

    public synchronized void supprimer(String id) {
        JSONArray n = new JSONArray();
        JSONObject parti = null;
        for (int i = 0; i < livres.length(); i++) {
            JSONObject o = livres.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) parti = o;
            else n.put(o);
        }
        livres = n;
        sauver();
        epub(id).delete();
        couverture(id).delete();
        if (parti != null) {
            String ext = parti.optString("sourceExt", "");
            if (!ext.isEmpty()) source(id, ext).delete();
        }
    }
}
