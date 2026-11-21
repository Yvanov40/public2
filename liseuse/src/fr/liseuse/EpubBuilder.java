package fr.liseuse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fabrique un fichier EPUB 3 (compatible EPUB 2 grâce au toc.ncx) :
 * chapitres XHTML, images, table des matières, notes, couverture.
 */
public final class EpubBuilder {

    static final Charset UTF8 = Charset.forName("UTF-8");

    static final class Chap {
        String titre;
        String fichier;
        final StringBuilder html = new StringBuilder();
        boolean contenu;
    }

    static final class Toc {
        int niveau;
        String titre;
        String fichier;
        String id;
    }

    static final class Ress {
        String id, href, mime, props;
    }

    private final ZipOutputStream zip;
    final List<Chap> chapitres = new ArrayList<Chap>();
    final List<Toc> toc = new ArrayList<Toc>();
    private final List<Ress> ressources = new ArrayList<Ress>();
    private final Map<String, String> ancres = new HashMap<String, String>();
    private final StringBuilder notes = new StringBuilder();
    private int nbNotes;
    private int nbImages;
    Chap cur;

    public String titre = "Sans titre";
    public String auteur = "";
    public String langue = "fr";
    public String description = "";
    /** href (relatif à OEBPS) de l'image utilisée comme couverture, ou null. */
    public String couverture;

    public EpubBuilder(OutputStream os) throws IOException {
        zip = new ZipOutputStream(os);
        byte[] mt = "application/epub+zip".getBytes(UTF8);
        ZipEntry e = new ZipEntry("mimetype");
        e.setMethod(ZipEntry.STORED);
        e.setSize(mt.length);
        e.setCompressedSize(mt.length);
        CRC32 crc = new CRC32();
        crc.update(mt);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(mt);
        zip.closeEntry();
        ecrire("META-INF/container.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n"
                        + "  <rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles>\n"
                        + "</container>\n");
        nouveauChapitre(null);
    }

    private void ecrire(String chemin, String texte) throws IOException {
        ecrire(chemin, texte.getBytes(UTF8));
    }

    private void ecrire(String chemin, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(chemin);
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    // ---------------------------------------------------------------- chapitres

    public void nouveauChapitre(String titreChap) {
        if (cur != null && !cur.contenu && cur.html.toString().trim().isEmpty()) {
            // le chapitre courant est vide : on le réutilise
            if (titreChap != null) cur.titre = titreChap;
            return;
        }
        cur = new Chap();
        cur.titre = titreChap;
        cur.fichier = String.format(Locale.ROOT, "chap%03d.xhtml", chapitres.size() + 1);
        chapitres.add(cur);
    }

    public StringBuilder html() {
        return cur.html;
    }

    public void marquerContenu() {
        cur.contenu = true;
    }

    public boolean chapitreVide() {
        return !cur.contenu;
    }

    public int tailleChapitre() {
        return cur.html.length();
    }

    public String fichierCourant() {
        return cur.fichier;
    }

    public void entreeToc(int niveau, String titreEntree, String id) {
        Toc t = new Toc();
        t.niveau = niveau;
        t.titre = titreEntree;
        t.fichier = cur.fichier;
        t.id = id;
        toc.add(t);
        if (cur.titre == null) cur.titre = titreEntree;
    }

    /** Lien interne vers un signet : résolu à la fin, quand on connaît le chapitre. */
    public static String lienAncre(String nom) {
        return "@@A:" + nom + "@@";
    }

    // ---------------------------------------------------------------- images

    public String ajouterImage(byte[] data, String ext) throws IOException {
        nbImages++;
        ext = ext.toLowerCase(Locale.ROOT);
        if (ext.equals("jpeg")) ext = "jpg";
        String href = "images/img" + nbImages + "." + ext;
        ecrire("OEBPS/" + href, data);
        Ress r = new Ress();
        r.id = "img" + nbImages;
        r.href = href;
        r.mime = mimeImage(ext);
        ressources.add(r);
        return href;
    }

    public int nombreImages() {
        return nbImages;
    }

    static String mimeImage(String ext) {
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("png")) return "image/png";
        if (ext.equals("gif")) return "image/gif";
        if (ext.equals("svg")) return "image/svg+xml";
        if (ext.equals("webp")) return "image/webp";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- notes

    /** Ajoute une note ; renvoie son numéro. */
    public int ajouterNote(String contenuHtml) {
        nbNotes++;
        int n = nbNotes;
        notes.append("<aside epub:type=\"footnote\" class=\"note\" id=\"n").append(n).append("\">")
                .append("<p class=\"num\"><a class=\"retour\" href=\"").append(lienAncre("nr" + n)).append("\">").append(n).append(".</a></p>")
                .append(contenuHtml).append("</aside>\n");
        return n;
    }

    public static String appelNote(int n) {
        return "<sup><a class=\"noteref\" epub:type=\"noteref\" id=\"nr" + n + "\" href=\"notes.xhtml#n"
                + n + "\">" + n + "</a></sup>";
    }

    // ---------------------------------------------------------------- fin

    public void finir(byte[] couvertureGeneree) throws IOException {
        // retire un éventuel dernier chapitre vide
        if (chapitres.size() > 1 && !cur.contenu && cur.html.toString().trim().isEmpty()) {
            chapitres.remove(chapitres.size() - 1);
        }
        if (couverture == null && couvertureGeneree != null) {
            ecrire("OEBPS/images/couverture.png", couvertureGeneree);
            Ress r = new Ress();
            r.id = "couverture";
            r.href = "images/couverture.png";
            r.mime = "image/png";
            ressources.add(r);
            couverture = r.href;
        }
        for (Ress r : ressources) {
            if (r.href.equals(couverture)) r.props = "cover-image";
        }
        // repère dans quel chapitre se trouve chaque identifiant
        Pattern pid = Pattern.compile(" id=\"([^\"]+)\"");
        for (Chap c : chapitres) {
            Matcher m = pid.matcher(c.html);
            while (m.find()) if (!ancres.containsKey(m.group(1))) ancres.put(m.group(1), c.fichier);
        }
        int i = 0;
        for (Chap c : chapitres) {
            i++;
            if (c.titre == null) c.titre = (i == 1) ? titre : "Partie " + i;
            ecrire("OEBPS/" + c.fichier, page(c.titre, resoudreLiens(c.html.toString(), c.fichier)));
        }
        if (nbNotes > 0) {
            ecrire("OEBPS/notes.xhtml", page("Notes",
                    "<section epub:type=\"footnotes\" class=\"notes\"><h1>Notes</h1>\n" + resoudreLiens(notes.toString(), "notes.xhtml") + "</section>"));
        }
        ecrire("OEBPS/style.css", CSS);
        ecrire("OEBPS/nav.xhtml", nav());
        ecrire("OEBPS/toc.ncx", ncx());
        ecrire("OEBPS/content.opf", opf());
        zip.close();
    }

    private String resoudreLiens(String corps, String fichier) {
        Matcher m = Pattern.compile("@@A:(.*?)@@").matcher(corps);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String nom = m.group(1);
            String f = ancres.get(nom);
            String cible = f == null ? "#" : (f.equals(fichier) ? "" : f) + "#" + nom;
            m.appendReplacement(sb, Matcher.quoteReplacement(cible));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String page(String t, String corps) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\""
                + esc(langue) + "\" xml:lang=\"" + esc(langue) + "\">\n<head>\n<meta charset=\"utf-8\"/>\n<title>"
                + esc(t) + "</title>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>\n</head>\n<body>\n"
                + corps + "\n</body>\n</html>\n";
    }

    private List<Toc> tocEffective() {
        if (!toc.isEmpty()) return toc;
        List<Toc> l = new ArrayList<Toc>();
        int i = 0;
        for (Chap c : chapitres) {
            i++;
            Toc t = new Toc();
            t.niveau = 1;
            t.titre = c.titre != null ? c.titre : (i == 1 ? titre : "Partie " + i);
            t.fichier = c.fichier;
            l.add(t);
        }
        return l;
    }

    private String nav() {
        StringBuilder sb = new StringBuilder();
        sb.append("<nav epub:type=\"toc\" id=\"toc\"><h1>Sommaire</h1>\n");
        List<Toc> l = tocEffective();
        int min = 99;
        for (Toc t : l) min = Math.min(min, t.niveau);
        int prof = 0;
        for (Toc t : l) {
            int n = Math.max(1, t.niveau - min + 1);
            if (n > prof + 1) n = prof + 1;
            if (n > prof) {
                while (prof < n) {
                    sb.append("<ol>");
                    prof++;
                }
            } else {
                sb.append("</li>");
                while (prof > n) {
                    sb.append("</ol></li>");
                    prof--;
                }
            }
            sb.append("\n<li><a href=\"").append(t.fichier).append(t.id != null ? "#" + t.id : "").append("\">")
                    .append(esc(t.titre)).append("</a>");
        }
        while (prof > 0) {
            sb.append("</li></ol>");
            prof--;
        }
        sb.append("\n</nav>");
        if (nbNotes > 0) {
            sb.append("\n<nav epub:type=\"landmarks\" hidden=\"\"><ol><li><a epub:type=\"bodymatter\" href=\"")
                    .append(chapitres.get(0).fichier).append("\">Début</a></li></ol></nav>");
        }
        return page("Sommaire", sb.toString());
    }

    private String ncx() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n<head><meta name=\"dtb:uid\" content=\"urn:uuid:")
                .append(uuid()).append("\"/></head>\n<docTitle><text>").append(esc(titre)).append("</text></docTitle>\n<navMap>\n");
        int i = 0;
        for (Toc t : tocEffective()) {
            i++;
            sb.append("<navPoint id=\"np").append(i).append("\" playOrder=\"").append(i).append("\"><navLabel><text>")
                    .append(esc(t.titre)).append("</text></navLabel><content src=\"").append(t.fichier)
                    .append(t.id != null ? "#" + t.id : "").append("\"/></navPoint>\n");
        }
        sb.append("</navMap>\n</ncx>\n");
        return sb.toString();
    }

    private String uuid;

    private String uuid() {
        if (uuid == null) uuid = UUID.randomUUID().toString();
        return uuid;
    }

    private String opf() {
        StringBuilder sb = new StringBuilder();
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT) {
            {
                setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            }
        }.format(new java.util.Date());
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\" xml:lang=\"")
                .append(esc(langue)).append("\">\n<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
                .append("<dc:identifier id=\"uid\">urn:uuid:").append(uuid()).append("</dc:identifier>\n")
                .append("<dc:title>").append(esc(titre)).append("</dc:title>\n")
                .append("<dc:language>").append(esc(langue)).append("</dc:language>\n");
        if (auteur != null && !auteur.isEmpty()) sb.append("<dc:creator>").append(esc(auteur)).append("</dc:creator>\n");
        if (description != null && !description.isEmpty())
            sb.append("<dc:description>").append(esc(description)).append("</dc:description>\n");
        sb.append("<meta property=\"dcterms:modified\">").append(date).append("</meta>\n");
        if (couverture != null) {
            for (Ress r : ressources) if (r.href.equals(couverture)) sb.append("<meta name=\"cover\" content=\"").append(r.id).append("\"/>\n");
        }
        sb.append("</metadata>\n<manifest>\n")
                .append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
                .append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
                .append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n");
        int i = 0;
        for (Chap c : chapitres) {
            i++;
            sb.append("<item id=\"c").append(i).append("\" href=\"").append(c.fichier).append("\" media-type=\"application/xhtml+xml\"/>\n");
        }
        if (nbNotes > 0) sb.append("<item id=\"notes\" href=\"notes.xhtml\" media-type=\"application/xhtml+xml\"/>\n");
        for (Ress r : ressources) {
            sb.append("<item id=\"").append(r.id).append("\" href=\"").append(r.href).append("\" media-type=\"").append(r.mime).append("\"");
            if (r.props != null) sb.append(" properties=\"").append(r.props).append("\"");
            sb.append("/>\n");
        }
        sb.append("</manifest>\n<spine toc=\"ncx\">\n");
        for (i = 1; i <= chapitres.size(); i++) sb.append("<itemref idref=\"c").append(i).append("\"/>\n");
        if (nbNotes > 0) sb.append("<itemref idref=\"notes\"/>\n");
        sb.append("</spine>\n</package>\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- utilitaires

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        echapper(s, sb);
        return sb.toString();
    }

    public static void echapper(String s, StringBuilder sb) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case ' ': sb.append("&#160;"); break;
                default:
                    if (c < 0x20 && c != '\n' && c != '\t') break; // caractère interdit en XML
                    if (c == 0xFFFE || c == 0xFFFF) break;
                    sb.append(c);
            }
        }
    }

    static byte[] lireTout(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    static final String CSS =
            "@charset \"utf-8\";\n"
            + "body { margin: 0; padding: 0; line-height: 1.5; text-align: justify; -webkit-hyphens: auto; hyphens: auto; widows: 2; orphans: 2; }\n"
            + "p { margin: 0; text-indent: 1.2em; }\n"
            + "p.vide { text-indent: 0; height: 1em; }\n"
            + "h1, h2, h3, h4, h5, h6 { text-indent: 0; text-align: left; line-height: 1.25; -webkit-hyphens: manual; hyphens: manual; break-after: avoid; page-break-after: avoid; }\n"
            + "h1 { font-size: 1.6em; text-align: center; margin: 1.5em 0 1em; }\n"
            + "h2 { font-size: 1.35em; margin: 1.3em 0 .7em; }\n"
            + "h3 { font-size: 1.15em; margin: 1.2em 0 .6em; }\n"
            + "h4, h5, h6 { font-size: 1em; margin: 1em 0 .5em; }\n"
            + "h1 + p, h2 + p, h3 + p, h4 + p, h5 + p, h6 + p, p.vide + p, .saut + p, .fig + p, .tableau + p, blockquote + p, ul + p, ol + p { text-indent: 0; }\n"
            + "h1.titre-doc { font-size: 2em; margin-top: 2em; }\n"
            + "p.sous-titre { text-align: center; text-indent: 0; font-style: italic; font-size: 1.15em; margin-bottom: 1.5em; }\n"
            + ".c { text-align: center; text-indent: 0; }\n"
            + ".r { text-align: right; text-indent: 0; }\n"
            + ".j { text-align: justify; }\n"
            + "p.fig, div.fig { text-align: center; text-indent: 0; margin: .6em 0; break-inside: avoid; page-break-inside: avoid; }\n"
            + "img { max-width: 100%; height: auto; }\n"
            + "p.legende { text-align: center; text-indent: 0; font-size: .85em; font-style: italic; margin-bottom: .8em; }\n"
            + "blockquote { margin: .8em 1.5em; font-style: italic; }\n"
            + "blockquote p { text-indent: 0; }\n"
            + "pre, p.code { font-family: monospace; font-size: .85em; white-space: pre-wrap; text-align: left; text-indent: 0; }\n"
            + "ul, ol { margin: .4em 0; padding-left: 1.6em; }\n"
            + "li { margin: .15em 0; }\n"
            + "li p { text-indent: 0; }\n"
            + "ul.sans { list-style: none; }\n"
            + "li.vide { list-style: none; }\n"
            + ".tableau { margin: .8em 0; overflow-x: auto; }\n"
            + "table { border-collapse: collapse; width: 100%; font-size: .9em; }\n"
            + "td, th { border: 1px solid rgba(128,128,128,.5); padding: .25em .4em; vertical-align: top; text-align: left; }\n"
            + "td p, th p { text-indent: 0; text-align: left; }\n"
            + "th { font-weight: bold; }\n"
            + ".saut { break-before: page; page-break-before: always; height: 0; }\n"
            + "hr.scene { border: 0; text-align: center; margin: 1em 0; }\n"
            + "hr.scene::after { content: \"* * *\"; }\n"
            + "a.noteref { text-decoration: none; font-weight: bold; padding: 0 .1em; }\n"
            + "aside.note { margin: 0 0 .8em; font-size: .9em; }\n"
            + "aside.note p { text-indent: 0; }\n"
            + "aside.note p.num { float: left; margin-right: .4em; }\n"
            + ".textbox { border: 1px solid rgba(128,128,128,.4); padding: .4em .6em; margin: .6em 0; }\n"
            + ".math { font-style: italic; }\n";
}
