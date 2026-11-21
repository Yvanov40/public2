package fr.liseuse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import fr.liseuse.Xml.El;

/** Lecture d'un EPUB : métadonnées, ordre de lecture, sommaire, couverture. */
public final class EpubLivre {

    public String titre = "", auteur = "", langue = "", description = "";
    public String opf, dossier = "";
    public String couverture; // chemin dans l'archive
    public final List<String> spine = new ArrayList<String>();
    public final List<Long> tailles = new ArrayList<Long>();
    public final JSONArray toc = new JSONArray();
    private final Map<String, String[]> manifeste = new HashMap<String, String[]>();

    public static EpubLivre lire(File f) throws Exception {
        ZipFile z = new ZipFile(f);
        try {
            EpubLivre l = new EpubLivre();
            l.analyser(z);
            return l;
        } finally {
            z.close();
        }
    }

    private static El xml(ZipFile z, String chemin) {
        try {
            ZipEntry e = z.getEntry(chemin);
            if (e == null) return null;
            InputStream in = z.getInputStream(e);
            try {
                return Xml.document(in);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String sansNs(String n) {
        int i = n.indexOf(':');
        return i >= 0 ? n.substring(i + 1) : n;
    }

    private String resoudre(String base, String href) {
        try {
            href = URLDecoder.decode(href.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignore) {
        }
        String frag = "";
        int h = href.indexOf('#');
        if (h >= 0) {
            frag = href.substring(h);
            href = href.substring(0, h);
        }
        if (href.isEmpty()) return frag;
        return DocxConverter.normaliser(base + href) + frag;
    }

    private void analyser(ZipFile z) throws IOException {
        El c = xml(z, "META-INF/container.xml");
        if (c != null) {
            List<El> rf = new ArrayList<El>();
            trouver(c, "rootfile", rf);
            for (El r : rf) {
                if (r.a("full-path") != null) {
                    opf = r.a("full-path");
                    break;
                }
            }
        }
        if (opf == null) {
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.toLowerCase().endsWith(".opf")) {
                    opf = n;
                    break;
                }
            }
        }
        if (opf == null) throw new IOException("Livre EPUB illisible (pas de fichier OPF).");
        int i = opf.lastIndexOf('/');
        dossier = i >= 0 ? opf.substring(0, i + 1) : "";
        El p = xml(z, opf);
        if (p == null) throw new IOException("Livre EPUB illisible.");
        El meta = null, man = null, sp = null;
        for (Object o : p.kids) {
            if (!(o instanceof El)) continue;
            El e = (El) o;
            String n = sansNs(e.n);
            if (n.equals("metadata")) meta = e;
            else if (n.equals("manifest")) man = e;
            else if (n.equals("spine")) sp = e;
        }
        String idCouv = null;
        if (meta != null) {
            List<El> tout = new ArrayList<El>();
            aplatir(meta, tout);
            for (El e : tout) {
                String n = sansNs(e.n);
                String t = e.texte().trim();
                if (n.equals("title") && titre.isEmpty()) titre = t;
                else if (n.equals("creator") && auteur.isEmpty()) auteur = t;
                else if (n.equals("language") && langue.isEmpty()) langue = t;
                else if (n.equals("description") && description.isEmpty()) description = t.replaceAll("<[^>]*>", "");
                else if (n.equals("meta") && "cover".equals(e.a("name"))) idCouv = e.a("content");
            }
        }
        String ncx = null, nav = null;
        if (man != null) {
            for (Object o : man.kids) {
                if (!(o instanceof El)) continue;
                El e = (El) o;
                if (!sansNs(e.n).equals("item")) continue;
                String id = e.a("id"), href = e.a("href"), mt = e.a("media-type"), props = e.a("properties");
                if (id == null || href == null) continue;
                String chemin = resoudre(dossier, href);
                manifeste.put(id, new String[]{chemin, mt == null ? "" : mt});
                if (props != null && props.contains("cover-image")) couverture = chemin;
                if (props != null && props.contains("nav")) nav = chemin;
                if ("application/x-dtbncx+xml".equals(mt)) ncx = chemin;
            }
        }
        if (couverture == null && idCouv != null && manifeste.containsKey(idCouv)) {
            String[] m = manifeste.get(idCouv);
            if (m[1].startsWith("image/")) couverture = m[0];
        }
        if (sp != null) {
            String t = sp.a("toc");
            if (t != null && manifeste.containsKey(t)) ncx = manifeste.get(t)[0];
            for (Object o : sp.kids) {
                if (!(o instanceof El)) continue;
                El e = (El) o;
                if (!sansNs(e.n).equals("itemref")) continue;
                String[] m = manifeste.get(e.a("idref"));
                if (m == null) continue;
                if ("no".equals(e.a("linear")) && !spine.isEmpty() && !m[0].toLowerCase().contains("note")) continue;
                spine.add(m[0]);
                ZipEntry ze = z.getEntry(m[0]);
                tailles.add(ze != null ? Math.max(1, ze.getSize()) : 1L);
            }
        }
        if (spine.isEmpty()) throw new IOException("Ce livre ne contient aucun chapitre lisible.");
        if (nav != null) lireNav(z, nav);
        if (toc.length() == 0 && ncx != null) lireNcx(z, ncx);
        if (toc.length() == 0) {
            for (int k = 0; k < spine.size(); k++) ajouterToc("Partie " + (k + 1), spine.get(k), 1);
        }
        if (titre.isEmpty()) titre = "Sans titre";
    }

    private static void trouver(El e, String nom, List<El> out) {
        for (Object o : e.kids) {
            if (o instanceof El) {
                El k = (El) o;
                if (sansNs(k.n).equals(nom)) out.add(k);
                trouver(k, nom, out);
            }
        }
    }

    private static void aplatir(El e, List<El> out) {
        for (Object o : e.kids) {
            if (o instanceof El) {
                out.add((El) o);
                aplatir((El) o, out);
            }
        }
    }

    private void ajouterToc(String t, String href, int niveau) {
        try {
            JSONObject o = new JSONObject();
            o.put("titre", t);
            o.put("href", href);
            o.put("niveau", niveau);
            toc.put(o);
        } catch (Exception ignore) {
        }
    }

    private void lireNav(ZipFile z, String chemin) {
        El d = xml(z, chemin);
        if (d == null) return;
        String base = chemin.contains("/") ? chemin.substring(0, chemin.lastIndexOf('/') + 1) : "";
        List<El> navs = new ArrayList<El>();
        trouver(d, "nav", navs);
        El cible = null;
        for (El n : navs) {
            String t = n.a("epub:type");
            if (t == null) t = n.a("type");
            if (t != null && t.contains("toc")) {
                cible = n;
                break;
            }
        }
        if (cible == null && !navs.isEmpty()) cible = navs.get(0);
        if (cible == null) return;
        for (Object o : cible.kids) {
            if (o instanceof El && sansNs(((El) o).n).equals("ol")) parcourirOl((El) o, base, 1);
        }
    }

    private void parcourirOl(El ol, String base, int niveau) {
        for (Object o : ol.kids) {
            if (!(o instanceof El)) continue;
            El li = (El) o;
            if (!sansNs(li.n).equals("li")) continue;
            for (Object k : li.kids) {
                if (!(k instanceof El)) continue;
                El e = (El) k;
                String n = sansNs(e.n);
                if (n.equals("a") && e.a("href") != null) {
                    String t = e.texte().replaceAll("\\s+", " ").trim();
                    ajouterToc(t.isEmpty() ? "…" : t, resoudre(base, e.a("href")), niveau);
                } else if (n.equals("span")) {
                    String t = e.texte().replaceAll("\\s+", " ").trim();
                    if (!t.isEmpty()) ajouterToc(t, "", niveau);
                } else if (n.equals("ol")) {
                    parcourirOl(e, base, niveau + 1);
                }
            }
        }
    }

    private void lireNcx(ZipFile z, String chemin) {
        El d = xml(z, chemin);
        if (d == null) return;
        String base = chemin.contains("/") ? chemin.substring(0, chemin.lastIndexOf('/') + 1) : "";
        for (Object o : d.kids) {
            if (o instanceof El && sansNs(((El) o).n).equals("navMap")) points((El) o, base, 1);
        }
    }

    private void points(El parent, String base, int niveau) {
        for (Object o : parent.kids) {
            if (!(o instanceof El)) continue;
            El np = (El) o;
            if (!sansNs(np.n).equals("navPoint")) continue;
            String t = "", src = "";
            for (Object k : np.kids) {
                if (!(k instanceof El)) continue;
                El e = (El) k;
                String n = sansNs(e.n);
                if (n.equals("navLabel")) t = e.texte().replaceAll("\\s+", " ").trim();
                else if (n.equals("content") && e.a("src") != null) src = resoudre(base, e.a("src"));
            }
            ajouterToc(t.isEmpty() ? "…" : t, src, niveau);
            points(np, base, niveau + 1);
        }
    }

    public JSONObject json() throws Exception {
        JSONObject o = new JSONObject();
        o.put("titre", titre);
        o.put("auteur", auteur);
        o.put("langue", langue);
        o.put("description", description);
        JSONArray s = new JSONArray();
        for (int i = 0; i < spine.size(); i++) {
            JSONObject c = new JSONObject();
            c.put("href", spine.get(i));
            c.put("taille", tailles.get(i));
            s.put(c);
        }
        o.put("spine", s);
        o.put("toc", toc);
        return o;
    }

    public static byte[] lireEntree(File f, String chemin) throws IOException {
        ZipFile z = new ZipFile(f);
        try {
            ZipEntry e = z.getEntry(chemin);
            if (e == null) return null;
            InputStream in = z.getInputStream(e);
            try {
                return EpubBuilder.lireTout(in);
            } finally {
                in.close();
            }
        } finally {
            z.close();
        }
    }
}
