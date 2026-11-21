package fr.liseuse;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import fr.liseuse.Xml.El;

/** Conversion d'un document Word (.docx) en EPUB. */
public final class DocxConverter {

    /** Traitement des images, fourni par la plate-forme (Android : Bitmap). */
    public interface Images {
        /** Renvoie {données, extension} ou null pour ignorer l'image. */
        Object[] traiter(byte[] data, String ext, boolean compresser);

        /** Couverture PNG générée à partir du titre (ou null). */
        byte[] couverture(String titre, String auteur);
    }

    public static final class Options {
        /** Niveau de titre qui commence un nouveau chapitre (1 à 3). 0 = un seul fichier. */
        public int niveauChapitre = 1;
        /** Repère « Chapitre 1 », « Prologue »… quand le document n'utilise pas les styles de titre. */
        public boolean detecterChapitres = true;
        /** Les sauts de page commencent une nouvelle page dans la liseuse. */
        public boolean sautsDePage = true;
        /** Réduit les très grandes images (plus léger, plus rapide). */
        public boolean compresserImages = true;
        /** Utiliser la première image comme couverture si elle ouvre le document. */
        public boolean couvertureImage = true;
    }

    public static final class Resultat {
        public String titre, auteur;
        public int chapitres, images, notes;
    }

    // ================================================================ styles

    static final int K_NORMAL = 0, K_TITRE_DOC = 1, K_SOUS_TITRE = 2, K_CITATION = 3, K_LEGENDE = 4,
            K_CODE = 5, K_TOC = 6;

    static final class Fmt {
        Boolean b, i, u, s, caps, smallCaps, cache;
        String vert, couleur, surligne;
        int taille; // demi-points, 0 = non défini

        void fusion(Fmt o) {
            if (o == null) return;
            if (o.b != null) b = o.b;
            if (o.i != null) i = o.i;
            if (o.u != null) u = o.u;
            if (o.s != null) s = o.s;
            if (o.caps != null) caps = o.caps;
            if (o.smallCaps != null) smallCaps = o.smallCaps;
            if (o.cache != null) cache = o.cache;
            if (o.vert != null) vert = o.vert;
            if (o.couleur != null) couleur = o.couleur;
            if (o.surligne != null) surligne = o.surligne;
            if (o.taille != 0) taille = o.taille;
        }
    }

    static final class Style {
        String id, nom, base, jc, numId, ilvl;
        int outline = -1;
        Boolean sautAvant;
        Fmt fmt;
        boolean resolu;
        int niveau; // 1..6 si titre
        int kind;
    }

    static final class NivListe {
        String fmt = "decimal", texte = "%1.";
        int debut = 1;
    }

    // ================================================================ état

    private final ZipFile zip;
    private final Images images;
    private final Options opt;
    private EpubBuilder ep;

    private final Map<String, String[]> rels = new HashMap<String, String[]>();
    private final Map<String, Style> styles = new HashMap<String, Style>();
    private Style styleDefaut;
    private Fmt fmtDefaut = new Fmt();
    private int tailleDefaut = 22;
    private final Map<String, Map<Integer, NivListe>> abstraits = new HashMap<String, Map<Integer, NivListe>>();
    private final Map<String, String> numVersAbstrait = new HashMap<String, String>();
    private final Map<String, Map<Integer, Integer>> debutsForces = new HashMap<String, Map<Integer, Integer>>();
    private final Map<String, int[]> compteurs = new HashMap<String, int[]>();
    private final Map<String, El> notesBas = new HashMap<String, El>();
    private final Map<String, El> notesFin = new HashMap<String, El>();
    private final Map<String, String> imagesFaites = new HashMap<String, String>();

    private long largeurTexteEmu = 5486400L; // 6 pouces par défaut
    private boolean titresStyles; // le document utilise des styles de titre
    private boolean premierTitreVu;
    private int textAvantImage;
    private String titreDoc;

    /** Sortie courante (chapitre, cellule de tableau, note…). */
    private StringBuilder out;
    private boolean bloc; // true dans un tableau / une note : pas de chapitre ni de titre
    private int nbTitres;
    private int videsDeSuite;
    private boolean sautEnAttente;

    /** Écrit le saut de page en attente juste avant un vrai contenu. */
    private void emettreSaut() {
        if (sautEnAttente && !bloc) {
            if (!ep.chapitreVide()) out.append("<div class=\"saut\"></div>\n");
            sautEnAttente = false;
        }
    }

    private static final class Liste {
        String tag, numId;
        int ilvl;
        boolean liOuvert;
    }

    private List<Liste> listes = new ArrayList<Liste>();

    private static final class Champ {
        final StringBuilder instr = new StringBuilder();
        boolean resultat, toc, cache, lien;
        int para;
    }

    private final Deque<Champ> champs = new ArrayDeque<Champ>();
    private int numPara;

    // ================================================================ API

    public DocxConverter(File docx, Images images, Options opt) throws IOException {
        this.zip = new ZipFile(docx);
        this.images = images;
        this.opt = opt == null ? new Options() : opt;
    }

    public Resultat convertir(OutputStream sortie, String nomFichier) throws Exception {
        try {
            ep = new EpubBuilder(sortie);
            lireRels();
            lireStyles();
            lireNumerotation();
            lireNotes("word/footnotes.xml", "w:footnote", notesBas);
            lireNotes("word/endnotes.xml", "w:endnote", notesFin);
            lireMeta(nomFichier);
            prepasse();
            corps();
            fermerListes();
            if (titreDoc != null && (ep.titre == null || ep.titre.isEmpty() || ep.titre.equals(sansExtension(nomFichier)))) {
                ep.titre = titreDoc;
            }
            byte[] couv = ep.couverture == null ? images.couverture(ep.titre, ep.auteur) : null;
            ep.finir(couv);
            Resultat r = new Resultat();
            r.titre = ep.titre;
            r.auteur = ep.auteur;
            r.chapitres = ep.chapitres.size();
            r.images = ep.nombreImages();
            return r;
        } finally {
            zip.close();
        }
    }

    static String sansExtension(String n) {
        if (n == null) return "Sans titre";
        int i = n.lastIndexOf('/');
        if (i >= 0) n = n.substring(i + 1);
        i = n.lastIndexOf('.');
        if (i > 0) n = n.substring(0, i);
        n = n.replace('_', ' ').trim();
        return n.isEmpty() ? "Sans titre" : n;
    }

    // ================================================================ lecture des parties

    private InputStream entree(String nom) throws IOException {
        ZipEntry e = zip.getEntry(nom);
        if (e == null) return null;
        return zip.getInputStream(e);
    }

    private El docXml(String nom) {
        try {
            InputStream in = entree(nom);
            if (in == null) return null;
            try {
                return Xml.document(in);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String cheminDocument() {
        El r = docXml("_rels/.rels");
        if (r != null) {
            for (Object o : r.kids) {
                if (o instanceof El) {
                    El e = (El) o;
                    String t = e.a("Type");
                    if (t != null && t.endsWith("/officeDocument")) {
                        String cible = e.a("Target");
                        if (cible != null) {
                            if (cible.startsWith("/")) cible = cible.substring(1);
                            return cible;
                        }
                    }
                }
            }
        }
        return "word/document.xml";
    }

    private String docPath;

    private void lireRels() {
        docPath = cheminDocument();
        int i = docPath.lastIndexOf('/');
        String dir = i >= 0 ? docPath.substring(0, i + 1) : "";
        El r = docXml(dir + "_rels/" + docPath.substring(i + 1) + ".rels");
        if (r == null) return;
        for (Object o : r.kids) {
            if (!(o instanceof El)) continue;
            El e = (El) o;
            String id = e.a("Id"), cible = e.a("Target"), mode = e.a("TargetMode");
            if (id == null || cible == null) continue;
            boolean externe = "External".equalsIgnoreCase(mode);
            String chemin = cible;
            if (!externe) {
                if (cible.startsWith("/")) chemin = cible.substring(1);
                else chemin = normaliser(dir + cible);
            }
            rels.put(id, new String[]{chemin, externe ? "1" : "0", e.a("Type")});
        }
    }

    static String normaliser(String p) {
        String[] parts = p.split("/");
        List<String> l = new ArrayList<String>();
        for (String s : parts) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!l.isEmpty()) l.remove(l.size() - 1);
            } else l.add(s);
        }
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    private static Boolean bool(El rPr, String nom) {
        El e = rPr.c(nom);
        if (e == null) return null;
        String v = e.a("w:val");
        if (v == null) return Boolean.TRUE;
        return !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("none"));
    }

    static Fmt lireFmt(El rPr) {
        if (rPr == null) return null;
        Fmt f = new Fmt();
        f.b = bool(rPr, "w:b");
        f.i = bool(rPr, "w:i");
        String u = rPr.v("w:u");
        if (u != null) f.u = !(u.equals("none") || u.equals("0"));
        Boolean s1 = bool(rPr, "w:strike"), s2 = bool(rPr, "w:dstrike");
        if (s1 != null || s2 != null) f.s = Boolean.TRUE.equals(s1) || Boolean.TRUE.equals(s2);
        f.caps = bool(rPr, "w:caps");
        f.smallCaps = bool(rPr, "w:smallCaps");
        Boolean van = bool(rPr, "w:vanish");
        if (van != null) f.cache = van;
        String va = rPr.v("w:vertAlign");
        if (va != null) f.vert = va;
        El col = rPr.c("w:color");
        if (col != null) {
            String c = col.a("w:val");
            if (c != null && c.matches("[0-9A-Fa-f]{6}")) f.couleur = c.toLowerCase(Locale.ROOT);
            else if (c != null) f.couleur = "";
        }
        String hl = rPr.v("w:highlight");
        if (hl != null) f.surligne = hl;
        El shd = rPr.c("w:shd");
        if (shd != null && f.surligne == null) {
            String fill = shd.a("w:fill");
            if (fill != null && fill.matches("[0-9A-Fa-f]{6}") && !fill.equalsIgnoreCase("FFFFFF")) f.surligne = "#" + fill.toLowerCase(Locale.ROOT);
        }
        String sz = rPr.v("w:sz");
        if (sz != null) {
            try {
                f.taille = Integer.parseInt(sz);
            } catch (NumberFormatException ignore) {
            }
        }
        return f;
    }

    private void lireStyles() {
        El r = docXml("word/styles.xml");
        if (r == null) return;
        El dd = r.c("w:docDefaults");
        if (dd != null) {
            El rp = dd.c("w:rPrDefault");
            if (rp != null && rp.c("w:rPr") != null) {
                El rPr = rp.c("w:rPr");
                Fmt f = lireFmt(rPr);
                if (f.taille > 0) tailleDefaut = f.taille;
                El lang = rPr.c("w:lang");
                if (lang != null && lang.a("w:val") != null && !lang.a("w:val").isEmpty()) {
                    ep.langue = lang.a("w:val");
                }
            }
        }
        for (El s : r.tous("w:style")) {
            Style st = new Style();
            st.id = s.a("w:styleId");
            if (st.id == null) continue;
            String nom = s.v("w:name");
            st.nom = nom == null ? st.id.toLowerCase(Locale.ROOT) : nom.toLowerCase(Locale.ROOT);
            st.base = s.v("w:basedOn");
            El pPr = s.c("w:pPr");
            if (pPr != null) {
                st.jc = pPr.v("w:jc");
                String ol = pPr.v("w:outlineLvl");
                if (ol != null) {
                    try {
                        st.outline = Integer.parseInt(ol);
                    } catch (NumberFormatException ignore) {
                    }
                }
                El np = pPr.c("w:numPr");
                if (np != null) {
                    st.numId = np.v("w:numId");
                    st.ilvl = np.v("w:ilvl");
                }
                st.sautAvant = bool(pPr, "w:pageBreakBefore");
            }
            st.fmt = lireFmt(s.c("w:rPr"));
            styles.put(st.id, st);
            if ("1".equals(s.a("w:default")) && "paragraph".equals(s.a("w:type"))) styleDefaut = st;
        }
        if (styleDefaut != null) {
            resoudre(styleDefaut, 0);
            if (styleDefaut.fmt != null && styleDefaut.fmt.taille > 0) tailleDefaut = styleDefaut.fmt.taille;
        }
    }

    private static final Pattern P_TITRE = Pattern.compile("^(heading|titre|t[ií]tulo|titolo|überschrift|kop|rubrik)\\s*(\\d)$");

    private static final Pattern P_LISTE = Pattern.compile("^list (?:bullet|number|continue)\\s*(\\d)$");

    private Style resoudre(Style s, int prof) {
        if (s == null || s.resolu || prof > 20) return s;
        s.resolu = true;
        Style b = s.base != null ? styles.get(s.base) : null;
        if (b != null && b != s) {
            resoudre(b, prof + 1);
            if (s.jc == null) s.jc = b.jc;
            if (s.outline < 0) s.outline = b.outline;
            if (s.numId == null) {
                s.numId = b.numId;
                if (s.ilvl == null) s.ilvl = b.ilvl;
            }
            if (s.sautAvant == null) s.sautAvant = b.sautAvant;
            Fmt f = new Fmt();
            f.fusion(b.fmt);
            f.fusion(s.fmt);
            s.fmt = f;
        }
        String n = s.nom;
        Matcher m = P_TITRE.matcher(n);
        if (m.find()) s.niveau = Math.min(6, Math.max(1, Integer.parseInt(m.group(2))));
        else if (n.equals("title") || n.equals("titre")) s.kind = K_TITRE_DOC;
        else if (n.equals("subtitle") || n.equals("sous-titre")) s.kind = K_SOUS_TITRE;
        else if (n.equals("quote") || n.equals("intense quote") || n.equals("citation") || n.equals("block text")) s.kind = K_CITATION;
        else if (n.equals("caption") || n.equals("légende")) s.kind = K_LEGENDE;
        else if (n.startsWith("toc ") || n.equals("toc heading") || n.startsWith("table of figures")) s.kind = K_TOC;
        Matcher ml = P_LISTE.matcher(n);
        if (ml.find() && s.numId != null && (s.ilvl == null || s.ilvl.equals("0"))) s.ilvl = String.valueOf(Math.min(8, Integer.parseInt(ml.group(1)) - 1));
        if (s.kind == K_NORMAL && s.niveau == 0 && (n.equals("html preformatted") || n.contains("code") || n.equals("plain text"))) s.kind = K_CODE;
        if (s.niveau == 0 && s.kind == K_NORMAL && s.outline >= 0 && s.outline < 6 && b != null) {
            // style personnalisé avec niveau hiérarchique : c'est un titre
            s.niveau = s.outline + 1;
        }
        if (s.niveau == 0 && s.outline >= 0 && s.outline < 6 && s.kind == K_NORMAL && !n.equals("normal")) {
            s.niveau = s.outline + 1;
        }
        return s;
    }

    private Style style(String id) {
        Style s = id != null ? styles.get(id) : null;
        if (s == null) s = styleDefaut;
        return resoudre(s, 0);
    }

    private void lireNumerotation() {
        El r = docXml("word/numbering.xml");
        if (r == null) return;
        for (El a : r.tous("w:abstractNum")) {
            Map<Integer, NivListe> niv = new HashMap<Integer, NivListe>();
            for (El l : a.tous("w:lvl")) {
                NivListe nl = new NivListe();
                String f = l.v("w:numFmt");
                if (f != null) nl.fmt = f;
                String t = l.v("w:lvlText");
                if (t != null) nl.texte = t;
                String d = l.v("w:start");
                if (d != null) {
                    try {
                        nl.debut = Integer.parseInt(d);
                    } catch (NumberFormatException ignore) {
                    }
                }
                try {
                    niv.put(Integer.parseInt(l.a("w:ilvl")), nl);
                } catch (Exception ignore) {
                }
            }
            abstraits.put(a.a("w:abstractNumId"), niv);
        }
        for (El n : r.tous("w:num")) {
            String id = n.a("w:numId");
            numVersAbstrait.put(id, n.v("w:abstractNumId"));
            for (El o : n.tous("w:lvlOverride")) {
                String so = o.v("w:startOverride");
                if (so != null) {
                    try {
                        Map<Integer, Integer> m = debutsForces.get(id);
                        if (m == null) debutsForces.put(id, m = new HashMap<Integer, Integer>());
                        m.put(Integer.parseInt(o.a("w:ilvl")), Integer.parseInt(so));
                    } catch (Exception ignore) {
                    }
                }
            }
        }
    }

    private NivListe niveauListe(String numId, int ilvl) {
        String a = numVersAbstrait.get(numId);
        Map<Integer, NivListe> m = a != null ? abstraits.get(a) : null;
        NivListe n = m != null ? m.get(ilvl) : null;
        if (n == null) {
            n = new NivListe();
            n.fmt = "bullet";
        }
        return n;
    }

    private int debut(String numId, int ilvl) {
        Map<Integer, Integer> m = debutsForces.get(numId);
        if (m != null && m.containsKey(ilvl)) return m.get(ilvl);
        return niveauListe(numId, ilvl).debut;
    }

    /** Fait avancer le compteur d'une liste et renvoie la valeur. */
    private int compter(String numId, int ilvl) {
        String a = numVersAbstrait.get(numId);
        String cle = a != null ? "a" + a : "n" + numId;
        int[] c = compteurs.get(cle);
        if (c == null) {
            c = new int[9];
            for (int i = 0; i < 9; i++) c[i] = Integer.MIN_VALUE;
            compteurs.put(cle, c);
        }
        if (c[ilvl] == Integer.MIN_VALUE) c[ilvl] = debut(numId, ilvl);
        else c[ilvl]++;
        for (int i = ilvl + 1; i < 9; i++) c[i] = Integer.MIN_VALUE;
        return c[ilvl];
    }

    private String texteNumero(String numId, int ilvl) {
        String a = numVersAbstrait.get(numId);
        int[] c = compteurs.get(a != null ? "a" + a : "n" + numId);
        NivListe nl = niveauListe(numId, ilvl);
        String t = nl.texte;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '%' && i + 1 < t.length() && Character.isDigit(t.charAt(i + 1))) {
                int l = t.charAt(i + 1) - '1';
                i++;
                int v = (c != null && l >= 0 && l < 9 && c[l] != Integer.MIN_VALUE) ? c[l] : 1;
                sb.append(formatNombre(v, niveauListe(numId, Math.max(0, l)).fmt));
            } else sb.append(ch);
        }
        return sb.toString().trim();
    }

    static String formatNombre(int v, String fmt) {
        if (fmt.equals("lowerLetter")) return lettre(v).toLowerCase(Locale.ROOT);
        if (fmt.equals("upperLetter")) return lettre(v);
        if (fmt.equals("lowerRoman")) return romain(v).toLowerCase(Locale.ROOT);
        if (fmt.equals("upperRoman")) return romain(v);
        if (fmt.equals("bullet") || fmt.equals("none")) return "";
        return String.valueOf(v);
    }

    static String lettre(int v) {
        if (v <= 0) return String.valueOf(v);
        StringBuilder sb = new StringBuilder();
        while (v > 0) {
            v--;
            sb.insert(0, (char) ('A' + v % 26));
            v /= 26;
        }
        return sb.toString();
    }

    static String romain(int v) {
        if (v <= 0 || v >= 4000) return String.valueOf(v);
        int[] n = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) while (v >= n[i]) {
            sb.append(s[i]);
            v -= n[i];
        }
        return sb.toString();
    }

    private void lireNotes(String chemin, String tag, Map<String, El> dest) {
        El r = docXml(chemin);
        if (r == null) return;
        for (El n : r.tous(tag)) {
            String t = n.a("w:type");
            if (t != null && !t.equals("normal")) continue;
            dest.put(n.a("w:id"), n);
        }
    }

    private void lireMeta(String nomFichier) {
        ep.titre = sansExtension(nomFichier);
        El c = docXml("docProps/core.xml");
        if (c == null) return;
        for (Object o : c.kids) {
            if (!(o instanceof El)) continue;
            El e = (El) o;
            String v = e.texte().trim();
            if (v.isEmpty()) continue;
            if (e.n.equals("dc:title")) ep.titre = v;
            else if (e.n.equals("dc:creator")) ep.auteur = v;
            else if (e.n.equals("dc:description")) ep.description = v;
            else if (e.n.equals("dc:language")) ep.langue = v;
        }
    }

    /** Parcours rapide : styles de titre utilisés ? largeur de la zone de texte ? */
    private void prepasse() throws Exception {
        InputStream in = entree(docPath);
        if (in == null) throw new IOException("Ce fichier ne contient pas de document Word.");
        int titres = 0;
        try {
            XmlPullParser p = Xml.parser(in);
            int t;
            long pgW = 0, mg = 0, md = 0;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t != XmlPullParser.START_TAG) continue;
                String n = Xml.nom(p);
                if (n.equals("w:pStyle")) {
                    Style s = style(p.getAttributeValue(null, "val") != null ? p.getAttributeValue(null, "val") : attr(p, "w:val"));
                    if (s != null && (s.niveau > 0)) titres++;
                } else if (n.equals("w:outlineLvl")) {
                    titres++;
                } else if (n.equals("w:pgSz")) {
                    pgW = lng(attr(p, "w:w"));
                } else if (n.equals("w:pgMar")) {
                    mg = lng(attr(p, "w:left"));
                    md = lng(attr(p, "w:right"));
                }
            }
            if (pgW > 0) {
                long w = pgW - mg - md;
                if (w > 1440) largeurTexteEmu = w * 635L;
            }
        } finally {
            in.close();
        }
        titresStyles = titres >= 2;
    }

    private static String attr(XmlPullParser p, String nom) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if ((Xml.prefixe(p.getAttributeNamespace(i)) + p.getAttributeName(i)).equals(nom)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static long lng(String s) {
        if (s == null) return 0;
        try {
            return (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ================================================================ corps du document

    private void corps() throws Exception {
        InputStream in = entree(docPath);
        try {
            XmlPullParser p = Xml.parser(in);
            int t;
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t == XmlPullParser.START_TAG && Xml.nom(p).equals("w:body")) break;
            }
            out = ep.html();
            while ((t = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (t == XmlPullParser.START_TAG) {
                    El e = Xml.lire(p);
                    blocCorps(e);
                } else if (t == XmlPullParser.END_TAG && Xml.nom(p).equals("w:body")) {
                    break;
                }
            }
        } finally {
            in.close();
        }
    }

    private void blocCorps(El e) throws IOException {
        String n = e.n;
        if (n.equals("w:p")) paragraphe(e);
        else if (n.equals("w:tbl")) {
            fermerListes();
            tableau(e);
        } else if (n.equals("w:sdt")) {
            El c = e.c("w:sdtContent");
            boolean toc = false;
            El pr = e.c("w:sdtPr");
            if (pr != null) {
                El dp = pr.cherche("w:docPartGallery");
                if (dp != null && dp.a("w:val") != null && dp.a("w:val").toLowerCase(Locale.ROOT).contains("table of contents")) toc = true;
            }
            if (c != null && !toc) for (Object o : c.kids) if (o instanceof El) blocCorps((El) o);
        } else if (n.equals("w:customXml") || n.equals("w:ins") || n.equals("w:smartTag")) {
            for (Object o : e.kids) if (o instanceof El) blocCorps((El) o);
        } else if (n.equals("w:bookmarkStart")) {
            signet(e);
        } else if (n.equals("mc:AlternateContent")) {
            El c = e.c("mc:Choice");
            if (c == null) c = e.c("mc:Fallback");
            if (c != null) for (Object o : c.kids) if (o instanceof El) blocCorps((El) o);
        }
    }

    private void signet(El e) {
        String nom = e.a("w:name");
        if (nom == null || nom.equals("_GoBack")) return;
        out.append("<span id=\"").append(idSur(nom)).append("\"></span>");
    }

    static String idSur(String nom) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nom.length(); i++) {
            char c = nom.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') sb.append(c);
            else sb.append('_');
        }
        if (sb.length() == 0 || !Character.isLetter(sb.charAt(0))) sb.insert(0, 'a');
        return sb.toString();
    }

    // ---------------------------------------------------------------- paragraphes

    private static final Pattern P_CHAPITRE = Pattern.compile(
            "^(chapitre|chapter|cap[ií]tulo|capitolo|kapitel|partie|part|livre|book|tome|prologue|pr[oó]logo|[ée]pilogue|epilogue|ep[ií]logo|introduction|conclusion|avant-propos|pr[ée]face|postface|interlude)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern P_NUMERO = Pattern.compile("^([IVXLC]{1,7}|\\d{1,3})\\.?$");
    private static final Pattern P_SCENE = Pattern.compile("^[\\s*•·~#◇◆✦❧⁂—–-]{1,15}$");

    private static final class Para {
        StringBuilder html = new StringBuilder();
        StringBuilder texte = new StringBuilder();
        int images;
        boolean sautAvant, sautApres;
        List<String> flottants = new ArrayList<String>();
        // fusion des segments de même mise en forme
        String cleFmt;
        String fermeFmt = "";
    }

    private Para para;

    private void paragraphe(El p) throws IOException {
        numPara++;
        El pPr = p.c("w:pPr");
        Style st = style(pPr != null ? pPr.v("w:pStyle") : null);
        if (st == null) st = new Style();
        int niveau = st.niveau;
        int kind = st.kind;
        String jc = st.jc;
        String numId = st.numId, ilvlS = st.ilvl;
        boolean sautAvant = Boolean.TRUE.equals(st.sautAvant);
        boolean sectionApres = false;
        int retrait = 0;
        if (pPr != null) {
            if (pPr.v("w:jc") != null) jc = pPr.v("w:jc");
            El np = pPr.c("w:numPr");
            if (np != null) {
                if (np.v("w:numId") != null) numId = np.v("w:numId");
                if (np.v("w:ilvl") != null) ilvlS = np.v("w:ilvl");
            }
            String ol = pPr.v("w:outlineLvl");
            if (ol != null && niveau == 0 && kind == K_NORMAL) {
                try {
                    int v = Integer.parseInt(ol);
                    if (v >= 0 && v < 6) niveau = v + 1;
                } catch (NumberFormatException ignore) {
                }
            }
            Boolean sb = bool(pPr, "w:pageBreakBefore");
            if (sb != null) sautAvant = sb;
            if (pPr.c("w:sectPr") != null) {
                El type = pPr.c("w:sectPr").c("w:type");
                String tv = type != null ? type.a("w:val") : null;
                sectionApres = tv == null || tv.equals("nextPage") || tv.equals("oddPage") || tv.equals("evenPage");
            }
            El ind = pPr.c("w:ind");
            if (ind != null) {
                String l = ind.a("w:left");
                if (l == null) l = ind.a("w:start");
                retrait = (int) lng(l);
            }
        }
        if (kind == K_TOC) return;
        if ("0".equals(numId)) numId = null;
        int ilvl = 0;
        if (ilvlS != null) {
            try {
                ilvl = Math.max(0, Math.min(8, Integer.parseInt(ilvlS)));
            } catch (NumberFormatException ignore) {
            }
        }
        Fmt base = new Fmt();
        base.fusion(fmtDefaut);
        if (styleDefaut != null) base.fusion(styleDefaut.fmt);
        if (st != styleDefaut) base.fusion(st.fmt);
        if (niveau > 0 || kind == K_TITRE_DOC) {
            base.b = null; // le gras des titres vient du CSS
            base.taille = 0;
            base.caps = null;
        }

        Para ancien = para;
        para = new Para();
        para.sautAvant = sautAvant;
        try {
            contenu(p, base);
            fermerFmt();
            fermerLiensDuParagraphe();
            String txt = para.texte.toString().trim();
            boolean vide = txt.isEmpty() && para.images == 0;

            // saut de page avant le paragraphe
            if (para.sautAvant && opt.sautsDePage && !bloc) {
                fermerListes();
                sautEnAttente = true;
            }

            if (vide) {
                if (para.html.indexOf("<span id=") >= 0) out.append(para.html);
                if (!bloc && listes.isEmpty() && !ep.chapitreVide() && videsDeSuite == 0) {
                    out.append("<p class=\"vide\">&#160;</p>\n");
                }
                videsDeSuite++;
                apresParagraphe(sectionApres);
                return;
            }

            // détection des chapitres sans style
            if (niveau == 0 && kind == K_NORMAL && !bloc && numId == null && opt.detecterChapitres && !titresStyles
                    && txt.length() < 70 && !txt.endsWith(",") && !txt.endsWith(".")
                    && (P_CHAPITRE.matcher(txt).matches() || ("center".equals(jc) && P_NUMERO.matcher(txt).matches()))) {
                niveau = 1;
            }
            // séparateur de scène « * * * »
            if (niveau == 0 && !bloc && numId == null && para.images == 0 && P_SCENE.matcher(txt).matches()) {
                fermerListes();
                emettreSaut();
                out.append("<hr class=\"scene\"/>\n");
                videsDeSuite = 0;
                ep.marquerContenu();
                apresParagraphe(sectionApres);
                return;
            }

            if (niveau == 0 && kind != K_TITRE_DOC) emettreSaut();
            if (bloc && (niveau > 0 || kind == K_TITRE_DOC)) {
                out.append("<p><b>").append(para.html).append("</b></p>\n");
                apresParagraphe(sectionApres);
                return;
            }

            if (niveau > 0 || kind == K_TITRE_DOC) {
                fermerListes();
                String prefixe = "";
                if (numId != null) {
                    NivListe nl = niveauListe(numId, ilvl);
                    compter(numId, ilvl);
                    if (!nl.fmt.equals("bullet") && !nl.fmt.equals("none")) prefixe = texteNumero(numId, ilvl);
                }
                int niv = kind == K_TITRE_DOC ? 1 : Math.min(6, niveau);
                if (kind == K_TITRE_DOC && titreDoc == null) titreDoc = txt;
                boolean coupe = opt.niveauChapitre > 0 && kind != K_TITRE_DOC && niv <= opt.niveauChapitre;
                if (coupe && !ep.chapitreVide()) {
                    ep.nouveauChapitre(null);
                    out = ep.html();
                    sautEnAttente = false;
                }
                emettreSaut();
                premierTitreVu = true;
                nbTitres++;
                String id = "t" + nbTitres;
                String titreToc = (prefixe.isEmpty() ? "" : prefixe + " ") + txt;
                if (kind != K_TITRE_DOC && niv <= Math.max(3, opt.niveauChapitre + 1)) ep.entreeToc(niv, titreToc, id);
                else if (kind == K_TITRE_DOC) ep.entreeToc(1, txt, id);
                out.append("<h").append(niv).append(" id=\"").append(id).append("\"");
                if (kind == K_TITRE_DOC) out.append(" class=\"titre-doc\"");
                out.append(">");
                if (!prefixe.isEmpty()) out.append("<span class=\"num\">").append(EpubBuilder.esc(prefixe)).append("</span> ");
                out.append(para.html).append("</h").append(niv).append(">\n");
                ep.marquerContenu();
                videsDeSuite = 0;
                ecrireFlottants();
                apresParagraphe(sectionApres);
                return;
            }

            // découpe des très longs chapitres (fluidité de la liseuse)
            if (!bloc && listes.isEmpty() && ep.tailleChapitre() > 160000 && out == ep.html()) {
                String t = ep.chapitres.get(ep.chapitres.size() - 1).titre;
                ep.nouveauChapitre(t);
                out = ep.html();
                sautEnAttente = false;
            }

            if (numId != null) {
                itemListe(numId, ilvl);
                String cls = classe(jc, kind, retrait, true);
                if (cls.isEmpty()) out.append(para.html);
                else out.append("<p").append(cls).append(">").append(para.html).append("</p>");
            } else {
                fermerListes();
                if (kind == K_CITATION) {
                    out.append("<blockquote><p>").append(para.html).append("</p></blockquote>\n");
                } else {
                    String cls = classe(jc, kind, retrait, false);
                    if (para.images > 0 && txt.isEmpty()) cls = " class=\"fig\"";
                    out.append("<p").append(cls).append(">").append(para.html).append("</p>\n");
                }
            }
            ecrireFlottants();
            ep.marquerContenu();
            videsDeSuite = 0;
            apresParagraphe(sectionApres);
        } finally {
            para = ancien;
        }
    }

    private void apresParagraphe(boolean sectionApres) {
        if ((para.sautApres || sectionApres) && opt.sautsDePage && !bloc) {
            fermerListes();
            sautEnAttente = true;
        }
    }

    private void ecrireFlottants() {
        for (String f : para.flottants) out.append(f);
    }

    private String classe(String jc, int kind, int retrait, boolean liste) {
        StringBuilder c = new StringBuilder();
        if (jc != null) {
            if (jc.equals("center")) c.append("c");
            else if (jc.equals("right") || jc.equals("end")) c.append("r");
        }
        if (kind == K_SOUS_TITRE) c.append(c.length() > 0 ? " " : "").append("sous-titre");
        else if (kind == K_LEGENDE) c.append(c.length() > 0 ? " " : "").append("legende");
        else if (kind == K_CODE) c.append(c.length() > 0 ? " " : "").append("code");
        String r = c.length() > 0 ? " class=\"" + c + "\"" : "";
        if (!liste && retrait >= 360 && kind == K_NORMAL && c.length() == 0) {
            double em = Math.min(6, retrait / 240.0);
            r += String.format(Locale.ROOT, " style=\"margin-left:%.1fem\"", em);
        }
        return r;
    }

    // ---------------------------------------------------------------- listes

    private void itemListe(String numId, int ilvl) {
        NivListe nl = niveauListe(numId, ilvl);
        int valeur = compter(numId, ilvl);
        String tag = nl.fmt.equals("bullet") || nl.fmt.equals("none") ? "ul" : "ol";
        while (listes.size() > ilvl + 1) fermerUneListe();
        if (listes.size() == ilvl + 1) {
            Liste l = listes.get(ilvl);
            if (!l.tag.equals(tag) || (tag.equals("ol") && !l.numId.equals(numId))) {
                fermerUneListe();
            } else if (l.liOuvert) {
                out.append("</li>\n");
                l.liOuvert = false;
            }
        }
        while (listes.size() < ilvl + 1) {
            if (!listes.isEmpty()) {
                Liste parent = listes.get(listes.size() - 1);
                if (!parent.liOuvert) {
                    out.append("<li class=\"vide\">");
                    parent.liOuvert = true;
                }
            }
            Liste l = new Liste();
            boolean dernier = listes.size() == ilvl;
            l.tag = dernier ? tag : "ul";
            l.numId = dernier ? numId : "";
            l.ilvl = listes.size();
            out.append("<").append(l.tag);
            if (dernier && tag.equals("ol")) {
                String type = typeOl(nl.fmt);
                if (type != null) out.append(" type=\"").append(type).append("\"");
                if (valeur != 1) out.append(" start=\"").append(valeur).append("\"");
            } else if (dernier && nl.fmt.equals("none")) {
                out.append(" class=\"sans\"");
            } else if (!dernier) {
                out.append(" class=\"sans\"");
            }
            out.append(">\n");
            listes.add(l);
        }
        out.append("<li>");
        listes.get(ilvl).liOuvert = true;
    }

    static String typeOl(String fmt) {
        if (fmt.equals("lowerLetter")) return "a";
        if (fmt.equals("upperLetter")) return "A";
        if (fmt.equals("lowerRoman")) return "i";
        if (fmt.equals("upperRoman")) return "I";
        return null;
    }

    private void fermerUneListe() {
        Liste l = listes.remove(listes.size() - 1);
        if (l.liOuvert) out.append("</li>");
        out.append("</").append(l.tag).append(">\n");
    }

    private void fermerListes() {
        while (!listes.isEmpty()) fermerUneListe();
    }

    // ---------------------------------------------------------------- tableaux

    private static final class Cell {
        El tc;
        int col, span = 1, rowspan = 1;
        boolean suite, entete;
    }

    private void tableau(El tbl) throws IOException {
        List<List<Cell>> lignes = new ArrayList<List<Cell>>();
        List<El> trs = new ArrayList<El>();
        for (Object o : tbl.kids) {
            if (!(o instanceof El)) continue;
            El e = (El) o;
            if (e.n.equals("w:tr")) trs.add(e);
            else if (e.n.equals("w:sdt") && e.c("w:sdtContent") != null) trs.addAll(e.c("w:sdtContent").tous("w:tr"));
        }
        for (El tr : trs) {
            List<Cell> l = new ArrayList<Cell>();
            boolean entete = tr.c("w:trPr") != null && tr.c("w:trPr").c("w:tblHeader") != null;
            int col = 0;
            List<El> tcs = new ArrayList<El>();
            for (Object o : tr.kids) {
                if (!(o instanceof El)) continue;
                El e = (El) o;
                if (e.n.equals("w:tc")) tcs.add(e);
                else if (e.n.equals("w:sdt") && e.c("w:sdtContent") != null) tcs.addAll(e.c("w:sdtContent").tous("w:tc"));
            }
            for (El tc : tcs) {
                Cell c = new Cell();
                c.tc = tc;
                c.col = col;
                c.entete = entete;
                El pr = tc.c("w:tcPr");
                if (pr != null) {
                    String gs = pr.v("w:gridSpan");
                    if (gs != null) {
                        try {
                            c.span = Math.max(1, Integer.parseInt(gs));
                        } catch (NumberFormatException ignore) {
                        }
                    }
                    El vm = pr.c("w:vMerge");
                    if (vm != null) {
                        String v = vm.a("w:val");
                        c.suite = v == null || v.equals("continue");
                    }
                }
                col += c.span;
                l.add(c);
            }
            lignes.add(l);
        }
        // fusion verticale
        for (int r = 0; r < lignes.size(); r++) {
            for (Cell c : lignes.get(r)) {
                if (!c.suite) continue;
                for (int k = r - 1; k >= 0; k--) {
                    Cell h = cellA(lignes.get(k), c.col);
                    if (h == null) break;
                    if (!h.suite) {
                        h.rowspan++;
                        break;
                    }
                }
            }
        }
        StringBuilder sauve = out;
        List<Liste> sauveListes = listes;
        boolean sauveBloc = bloc;
        StringBuilder t = new StringBuilder();
        t.append("<div class=\"tableau\"><table>\n");
        for (List<Cell> l : lignes) {
            t.append("<tr>");
            for (Cell c : l) {
                if (c.suite) continue;
                String tag = c.entete ? "th" : "td";
                t.append("<").append(tag);
                if (c.span > 1) t.append(" colspan=\"").append(c.span).append("\"");
                if (c.rowspan > 1) t.append(" rowspan=\"").append(c.rowspan).append("\"");
                t.append(">");
                out = new StringBuilder();
                listes = new ArrayList<Liste>();
                bloc = true;
                for (Object o : c.tc.kids) {
                    if (!(o instanceof El)) continue;
                    El e = (El) o;
                    if (e.n.equals("w:p")) paragraphe(e);
                    else if (e.n.equals("w:tbl")) {
                        fermerListes();
                        tableau(e);
                    } else if (e.n.equals("w:sdt") && e.c("w:sdtContent") != null) {
                        for (El p : e.c("w:sdtContent").tous("w:p")) paragraphe(p);
                    }
                }
                fermerListes();
                t.append(out.toString().replace("<p class=\"vide\">&#160;</p>\n", ""));
                t.append("</").append(tag).append(">");
            }
            t.append("</tr>\n");
        }
        t.append("</table></div>\n");
        out = sauve;
        listes = sauveListes;
        bloc = sauveBloc;
        out.append(t);
        ep.marquerContenu();
        videsDeSuite = 0;
    }

    private static Cell cellA(List<Cell> l, int col) {
        for (Cell c : l) if (c.col == col) return c;
        return null;
    }

    // ---------------------------------------------------------------- contenu en ligne

    private boolean champVisible() {
        for (Champ c : champs) if (!c.resultat || c.toc || c.cache) return false;
        return true;
    }

    private void contenu(El parent, Fmt base) throws IOException {
        for (Object o : parent.kids) {
            if (!(o instanceof El)) continue;
            El e = (El) o;
            String n = e.n;
            if (n.equals("w:r")) run(e, base);
            else if (n.equals("w:hyperlink")) {
                String href = null;
                String rid = e.a("r:id");
                if (rid != null && rels.containsKey(rid)) {
                    String[] r = rels.get(rid);
                    href = r[0];
                    if (e.a("w:anchor") != null) href += "#" + e.a("w:anchor");
                } else if (e.a("w:anchor") != null) {
                    href = EpubBuilder.lienAncre(idSur(e.a("w:anchor")));
                }
                if (href != null && champVisible()) {
                    fermerFmt();
                    para.html.append("<a href=\"").append(href.startsWith("@@") ? href : EpubBuilder.esc(href)).append("\">");
                    contenu(e, base);
                    fermerFmt();
                    para.html.append("</a>");
                } else contenu(e, base);
            } else if (n.equals("w:ins") || n.equals("w:smartTag") || n.equals("w:customXml") || n.equals("w:dir")
                    || n.equals("w:bdo") || n.equals("w:moveTo")) {
                contenu(e, base);
            } else if (n.equals("w:sdt")) {
                El c = e.c("w:sdtContent");
                if (c != null) contenu(c, base);
            } else if (n.equals("w:fldSimple")) {
                String ins = e.a("w:instr");
                ins = ins == null ? "" : ins.trim();
                String up = ins.toUpperCase(Locale.ROOT);
                if (up.startsWith("PAGE") || up.startsWith("NUMPAGES") || up.startsWith("PAGEREF") || up.startsWith("TOC")) continue;
                String lien = up.startsWith("HYPERLINK") ? lienChamp(ins) : null;
                if (lien != null) {
                    fermerFmt();
                    para.html.append("<a href=\"").append(lien.startsWith("@@") ? lien : EpubBuilder.esc(lien)).append("\">");
                    contenu(e, base);
                    fermerFmt();
                    para.html.append("</a>");
                } else contenu(e, base);
            } else if (n.equals("w:bookmarkStart")) {
                if (champVisible()) {
                    fermerFmt();
                    String nom = e.a("w:name");
                    if (nom != null && !nom.equals("_GoBack")) {
                        para.html.append("<span id=\"").append(idSur(nom)).append("\"></span>");
                    }
                }
            } else if (n.equals("m:oMathPara") || n.equals("m:oMath")) {
                fermerFmt();
                String t = maths(e);
                if (!t.isEmpty()) {
                    para.html.append("<span class=\"math\">");
                    EpubBuilder.echapper(t, para.html);
                    para.html.append("</span>");
                    para.texte.append(t);
                }
            } else if (n.equals("mc:AlternateContent")) {
                El c = e.c("mc:Choice");
                if (c == null) c = e.c("mc:Fallback");
                if (c != null) contenu(c, base);
            }
        }
    }

    private static String maths(El e) {
        List<El> ts = new ArrayList<El>();
        e.chercheTous("m:t", ts);
        StringBuilder sb = new StringBuilder();
        for (El t : ts) sb.append(t.texte());
        return sb.toString();
    }

    private String lienChamp(String instr) {
        Matcher m = Pattern.compile("\\\\l\\s+\"([^\"]*)\"").matcher(instr);
        if (m.find()) return EpubBuilder.lienAncre(idSur(m.group(1)));
        m = Pattern.compile("HYPERLINK\\s+\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(instr);
        if (m.find()) return m.group(1);
        m = Pattern.compile("HYPERLINK\\s+(\\S+)", Pattern.CASE_INSENSITIVE).matcher(instr);
        if (m.find()) return m.group(1);
        return null;
    }

    private void fermerLiensDuParagraphe() {
        for (Champ c : champs) {
            if (c.lien && c.para == numPara) {
                fermerFmt();
                para.html.append("</a>");
                c.lien = false;
            }
        }
    }

    private void run(El r, Fmt base) throws IOException {
        Fmt f = new Fmt();
        f.fusion(base);
        El rPr = r.c("w:rPr");
        if (rPr != null) {
            String rs = rPr.v("w:rStyle");
            if (rs != null) {
                Style s = styles.get(rs);
                if (s != null) {
                    resoudre(s, 0);
                    f.fusion(s.fmt);
                }
            }
            f.fusion(lireFmt(rPr));
        }
        if (Boolean.TRUE.equals(f.cache)) return;
        for (Object o : r.kids) {
            if (!(o instanceof El)) continue;
            El e = (El) o;
            String n = e.n;
            if (n.equals("w:t")) {
                if (champVisible()) texte(e.texte(), f);
            } else if (n.equals("w:tab") || n.equals("w:ptab")) {
                if (champVisible() && para.texte.length() > 0) texte(" ", f);
            } else if (n.equals("w:br") || n.equals("w:cr")) {
                if (!champVisible()) continue;
                String t = e.a("w:type");
                if ("page".equals(t)) {
                    if (para.texte.toString().trim().isEmpty() && para.images == 0) para.sautAvant = true;
                    else para.sautApres = true;
                } else if (!"column".equals(t)) {
                    fermerFmt();
                    para.html.append("<br/>");
                }
            } else if (n.equals("w:noBreakHyphen")) {
                if (champVisible()) texte("‑", f);
            } else if (n.equals("w:softHyphen")) {
                if (champVisible()) texte("­", f);
            } else if (n.equals("w:sym")) {
                String font = e.a("w:font");
                String ch = e.a("w:char");
                if (champVisible() && ch != null && (font == null || !font.toLowerCase(Locale.ROOT).matches(".*(symbol|wingding|webding).*"))) {
                    try {
                        int cp = Integer.parseInt(ch, 16);
                        if (cp >= 0xF000) cp -= 0xF000;
                        if (cp >= 0x20) texte(new String(Character.toChars(cp)), f);
                    } catch (Exception ignore) {
                    }
                }
            } else if (n.equals("w:drawing")) {
                if (champVisible()) dessin(e);
            } else if (n.equals("w:pict") || n.equals("w:object")) {
                if (champVisible()) vml(e);
            } else if (n.equals("mc:AlternateContent")) {
                El c = e.c("mc:Choice");
                if (c == null) c = e.c("mc:Fallback");
                if (c != null) {
                    El faux = new El("w:r");
                    if (rPr != null) faux.kids.add(rPr);
                    faux.kids.addAll(c.kids);
                    run(faux, base);
                }
            } else if (n.equals("w:footnoteReference") || n.equals("w:endnoteReference")) {
                if (!champVisible()) continue;
                El note = (n.equals("w:footnoteReference") ? notesBas : notesFin).get(e.a("w:id"));
                if (note != null) note(note);
            } else if (n.equals("w:fldChar")) {
                String t = e.a("w:fldCharType");
                if ("begin".equals(t)) {
                    Champ c = new Champ();
                    c.para = numPara;
                    champs.push(c);
                } else if ("separate".equals(t) && !champs.isEmpty()) {
                    Champ c = champs.peek();
                    c.resultat = true;
                    String ins = c.instr.toString().trim();
                    String up = ins.toUpperCase(Locale.ROOT);
                    if (up.startsWith("TOC")) c.toc = true;
                    else if (up.startsWith("PAGE") || up.startsWith("NUMPAGES") || up.startsWith("SECTIONPAGES")) c.cache = true;
                    else if (up.startsWith("HYPERLINK") && champVisible()) {
                        String l = lienChamp(ins);
                        if (l != null) {
                            fermerFmt();
                            para.html.append("<a href=\"").append(l.startsWith("@@") ? l : EpubBuilder.esc(l)).append("\">");
                            c.lien = true;
                            c.para = numPara;
                        }
                    }
                } else if ("end".equals(t) && !champs.isEmpty()) {
                    Champ c = champs.pop();
                    if (c.lien && c.para == numPara) {
                        fermerFmt();
                        para.html.append("</a>");
                    }
                }
            } else if (n.equals("w:instrText")) {
                if (!champs.isEmpty()) champs.peek().instr.append(e.texte());
            }
        }
    }

    // ---------------------------------------------------------------- texte et mise en forme

    private void texte(String t, Fmt f) {
        if (para.texte.length() == 0) {
            int i = 0;
            while (i < t.length() && (t.charAt(i) == ' ' || t.charAt(i) == '\t')) i++;
            t = t.substring(i);
        }
        if (t.isEmpty()) return;
        String[] ouv = new String[1];
        String cle = cleFmt(f, ouv);
        if (!cle.equals(para.cleFmt)) {
            fermerFmt();
            para.html.append(ouv[0]);
            para.cleFmt = cle;
            para.fermeFmt = fermeture(f);
        }
        if (Boolean.TRUE.equals(f.caps)) t = t.toUpperCase(Locale.ROOT);
        EpubBuilder.echapper(t, para.html);
        para.texte.append(t);
        textAvantImage += t.length();
    }

    private void fermerFmt() {
        if (para == null) return;
        if (para.cleFmt != null) {
            para.html.append(para.fermeFmt);
            para.cleFmt = null;
            para.fermeFmt = "";
        }
    }

    private String couleurOk(String c) {
        if (c == null || c.isEmpty()) return null;
        int r = Integer.parseInt(c.substring(0, 2), 16), g = Integer.parseInt(c.substring(2, 4), 16), b = Integer.parseInt(c.substring(4, 6), 16);
        double l = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        if (l < 0.2 || l > 0.9) return null; // presque noir / blanc : laissé au thème
        return "#" + c;
    }

    private static String surligne(String h) {
        if (h == null || h.equals("none")) return null;
        if (h.startsWith("#")) return h;
        if (h.equals("yellow")) return "#fff176";
        if (h.equals("green")) return "#a5d6a7";
        if (h.equals("cyan")) return "#80deea";
        if (h.equals("magenta")) return "#f48fb1";
        if (h.equals("blue")) return "#90caf9";
        if (h.equals("red")) return "#ef9a9a";
        if (h.equals("darkYellow")) return "#d4c26a";
        if (h.equals("lightGray")) return "#d6d6d6";
        if (h.equals("darkGray")) return "#9e9e9e";
        return "#fff176";
    }

    private String cleFmt(Fmt f, String[] ouv) {
        StringBuilder o = new StringBuilder();
        if (Boolean.TRUE.equals(f.b)) o.append("<b>");
        if (Boolean.TRUE.equals(f.i)) o.append("<i>");
        if (Boolean.TRUE.equals(f.u)) o.append("<u>");
        if (Boolean.TRUE.equals(f.s)) o.append("<s>");
        if ("superscript".equals(f.vert)) o.append("<sup>");
        else if ("subscript".equals(f.vert)) o.append("<sub>");
        StringBuilder style = new StringBuilder();
        if (Boolean.TRUE.equals(f.smallCaps)) style.append("font-variant:small-caps;");
        if (f.taille > 0 && f.vert == null) {
            double ratio = f.taille / (double) tailleDefaut;
            if (Math.abs(ratio - 1) > 0.15) {
                ratio = Math.max(0.6, Math.min(2.5, ratio));
                style.append(String.format(Locale.ROOT, "font-size:%.2fem;", ratio));
            }
        }
        if (style.length() > 0) o.append("<span style=\"").append(style).append("\">");
        String coul = couleurOk(f.couleur), sur = surligne(f.surligne);
        if (coul != null || sur != null) {
            o.append("<span class=\"coul\" style=\"");
            if (coul != null) o.append("color:").append(coul).append(";");
            if (sur != null) o.append("background-color:").append(sur).append(";");
            o.append("\">");
        }
        ouv[0] = o.toString();
        return ouv[0];
    }

    private String fermeture(Fmt f) {
        StringBuilder c = new StringBuilder();
        String coul = couleurOk(f.couleur), sur = surligne(f.surligne);
        if (coul != null || sur != null) c.append("</span>");
        boolean st = Boolean.TRUE.equals(f.smallCaps);
        if (f.taille > 0 && f.vert == null) {
            double ratio = f.taille / (double) tailleDefaut;
            if (Math.abs(ratio - 1) > 0.15) st = true;
        }
        if (st) c.append("</span>");
        if ("superscript".equals(f.vert)) c.append("</sup>");
        else if ("subscript".equals(f.vert)) c.append("</sub>");
        if (Boolean.TRUE.equals(f.s)) c.append("</s>");
        if (Boolean.TRUE.equals(f.u)) c.append("</u>");
        if (Boolean.TRUE.equals(f.i)) c.append("</i>");
        if (Boolean.TRUE.equals(f.b)) c.append("</b>");
        return c.toString();
    }

    // ---------------------------------------------------------------- images

    private void dessin(El d) throws IOException {
        // zone de texte dans une forme
        List<El> txbx = new ArrayList<El>();
        d.chercheTous("w:txbxContent", txbx);
        List<El> blips = new ArrayList<El>();
        d.chercheTous("a:blip", blips);
        long cx = 0;
        El ext = d.cherche("wp:extent");
        if (ext != null) cx = lng(ext.a("cx"));
        String alt = null;
        El docPr = d.cherche("wp:docPr");
        if (docPr != null) {
            alt = docPr.a("descr");
            if (alt == null || alt.isEmpty()) alt = docPr.a("title");
        }
        if (alt != null && alt.length() > 300) alt = alt.substring(0, 300);
        for (El b : blips) {
            String rid = b.a("r:embed");
            if (rid == null) rid = b.a("r:link");
            if (rid != null) {
                image(rid, cx, alt);
                break; // une seule image par dessin
            }
        }
        for (El t : txbx) zoneTexte(t);
    }

    private void vml(El e) throws IOException {
        List<El> imgs = new ArrayList<El>();
        e.chercheTous("v:imagedata", imgs);
        for (El im : imgs) {
            String rid = im.a("r:id");
            if (rid == null) rid = im.a("r:pict");
            if (rid == null) continue;
            long cx = 0;
            El shape = e.cherche("v:shape");
            if (shape != null && shape.a("style") != null) {
                Matcher m = Pattern.compile("width:\\s*([0-9.]+)(pt|in|px|cm|mm)").matcher(shape.a("style"));
                if (m.find()) {
                    double v = Double.parseDouble(m.group(1));
                    String u = m.group(2);
                    double pt = u.equals("pt") ? v : u.equals("in") ? v * 72 : u.equals("px") ? v * 0.75 : u.equals("cm") ? v * 28.35 : v * 2.835;
                    cx = (long) (pt * 12700);
                }
            }
            String alt = im.a("o:title");
            image(rid, cx, alt);
            break;
        }
        if (e.n.equals("w:object")) return;
        List<El> txbx = new ArrayList<El>();
        e.chercheTous("w:txbxContent", txbx);
        for (El t : txbx) zoneTexte(t);
    }

    private void zoneTexte(El txbx) throws IOException {
        StringBuilder sauve = out;
        List<Liste> sl = listes;
        boolean sb = bloc;
        Para sp = para;
        out = new StringBuilder();
        listes = new ArrayList<Liste>();
        bloc = true;
        try {
            for (Object o : txbx.kids) {
                if (!(o instanceof El)) continue;
                El e = (El) o;
                if (e.n.equals("w:p")) paragraphe(e);
                else if (e.n.equals("w:tbl")) tableau(e);
            }
            fermerListes();
            String h = out.toString().replace("<p class=\"vide\">&#160;</p>\n", "").trim();
            if (!h.isEmpty()) sp.flottants.add("<div class=\"textbox\">" + h + "</div>\n");
        } finally {
            out = sauve;
            listes = sl;
            bloc = sb;
            para = sp;
        }
    }

    private void image(String rid, long cx, String alt) throws IOException {
        String[] r = rels.get(rid);
        if (r == null || "1".equals(r[1])) return;
        String chemin = r[0];
        String src = imagesFaites.get(chemin);
        if (src == null) {
            ZipEntry ze = zip.getEntry(chemin);
            if (ze == null) return;
            InputStream in = zip.getInputStream(ze);
            byte[] data;
            try {
                data = EpubBuilder.lireTout(in);
            } finally {
                in.close();
            }
            String ext = chemin.contains(".") ? chemin.substring(chemin.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            Object[] res = images.traiter(data, ext, opt.compresserImages);
            if (res == null) {
                imagesFaites.put(chemin, "");
                return;
            }
            src = ep.ajouterImage((byte[]) res[0], (String) res[1]);
            imagesFaites.put(chemin, src);
            if (opt.couvertureImage && ep.couverture == null && ep.chapitres.size() == 1 && textAvantImage < 200 && !bloc
                    && (cx == 0 || cx > largeurTexteEmu / 3)) {
                ep.couverture = src;
            }
        }
        if (src.isEmpty()) return;
        fermerFmt();
        para.images++;
        para.html.append("<img src=\"").append(src).append("\" alt=\"").append(EpubBuilder.esc(alt == null ? "" : alt)).append("\"");
        if (cx > 0) {
            double pct = cx * 100.0 / largeurTexteEmu;
            if (pct >= 85) pct = 100;
            pct = Math.max(4, Math.min(100, pct));
            para.html.append(String.format(Locale.ROOT, " style=\"width:%.0f%%\"", pct));
        }
        para.html.append("/>");
        ep.marquerContenu();
    }

    // ---------------------------------------------------------------- notes

    private void note(El note) throws IOException {
        StringBuilder sauve = out;
        List<Liste> sl = listes;
        boolean sb = bloc;
        Para sp = para;
        Deque<Champ> sc = new ArrayDeque<Champ>(champs);
        champs.clear();
        out = new StringBuilder();
        listes = new ArrayList<Liste>();
        bloc = true;
        String h;
        try {
            for (Object o : note.kids) {
                if (!(o instanceof El)) continue;
                El e = (El) o;
                if (e.n.equals("w:p")) paragraphe(e);
                else if (e.n.equals("w:tbl")) tableau(e);
            }
            fermerListes();
            h = out.toString().replace("<p class=\"vide\">&#160;</p>\n", "");
        } finally {
            out = sauve;
            listes = sl;
            bloc = sb;
            para = sp;
            champs.clear();
            for (java.util.Iterator<Champ> it = sc.descendingIterator(); it.hasNext(); ) champs.push(it.next());
        }
        int n = ep.ajouterNote(h);
        fermerFmt();
        para.html.append(EpubBuilder.appelNote(n));
    }
}
