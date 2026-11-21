package fr.liseuse;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Petit DOM léger, construit morceau par morceau à partir d'un XmlPullParser. */
public final class Xml {

    private Xml() {
    }

    public static final class El {
        public final String n;
        final List<String> attrs = new ArrayList<String>(4);
        public final List<Object> kids = new ArrayList<Object>(4);

        El(String n) {
            this.n = n;
        }

        public String a(String nom) {
            for (int i = 0; i < attrs.size(); i += 2) if (attrs.get(i).equals(nom)) return attrs.get(i + 1);
            return null;
        }

        public El c(String nom) {
            for (Object o : kids) if (o instanceof El && ((El) o).n.equals(nom)) return (El) o;
            return null;
        }

        /** Valeur w:val de l'enfant, ou null si l'enfant n'existe pas. */
        public String v(String nom) {
            El e = c(nom);
            if (e == null) return null;
            String s = e.a("w:val");
            return s == null ? "" : s;
        }

        public List<El> tous(String nom) {
            List<El> l = new ArrayList<El>();
            for (Object o : kids) if (o instanceof El && ((El) o).n.equals(nom)) l.add((El) o);
            return l;
        }

        public El cherche(String nom) {
            for (Object o : kids) {
                if (o instanceof El) {
                    El e = (El) o;
                    if (e.n.equals(nom)) return e;
                    El r = e.cherche(nom);
                    if (r != null) return r;
                }
            }
            return null;
        }

        public void chercheTous(String nom, List<El> out) {
            for (Object o : kids) {
                if (o instanceof El) {
                    El e = (El) o;
                    if (e.n.equals(nom)) out.add(e);
                    else e.chercheTous(nom, out);
                }
            }
        }

        public String texte() {
            StringBuilder sb = new StringBuilder();
            texte(sb);
            return sb.toString();
        }

        void texte(StringBuilder sb) {
            for (Object o : kids) {
                if (o instanceof String) sb.append((String) o);
                else ((El) o).texte(sb);
            }
        }
    }

    public static XmlPullParser parser(InputStream in) throws XmlPullParserException {
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(true);
        XmlPullParser p = f.newPullParser();
        try {
            p.setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false);
        } catch (Exception ignore) {
        }
        p.setInput(in, null);
        return p;
    }

    public static String prefixe(String ns) {
        if (ns == null || ns.isEmpty()) return "";
        if (ns.endsWith("wordprocessingml/2006/main") || ns.endsWith("ooxml/wordprocessingml/main")) return "w:";
        if (ns.endsWith("officeDocument/2006/relationships") || ns.endsWith("officeDocument/relationships")) return "r:";
        if (ns.endsWith("drawingml/2006/main") || ns.endsWith("drawingml/main")) return "a:";
        if (ns.endsWith("drawingml/2006/wordprocessingDrawing") || ns.endsWith("drawingml/wordprocessingDrawing")) return "wp:";
        if (ns.endsWith("drawingml/2006/picture") || ns.endsWith("drawingml/picture")) return "pic:";
        if (ns.equals("urn:schemas-microsoft-com:vml")) return "v:";
        if (ns.endsWith("markup-compatibility/2006")) return "mc:";
        if (ns.endsWith("officeDocument/2006/math") || ns.endsWith("officeDocument/math")) return "m:";
        if (ns.endsWith("wordprocessingShape")) return "wps:";
        if (ns.equals("http://www.w3.org/XML/1998/namespace")) return "xml:";
        if (ns.equals("http://purl.org/dc/elements/1.1/")) return "dc:";
        if (ns.equals("http://www.idpf.org/2007/opf")) return "opf:";
        if (ns.equals("http://www.idpf.org/2007/ops")) return "epub:";
        return "";
    }

    public static String nom(XmlPullParser p) {
        return prefixe(p.getNamespace()) + p.getName();
    }

    /** Lit l'élément courant (le parseur est sur START_TAG) et tout son contenu. */
    public static El lire(XmlPullParser p) throws XmlPullParserException, IOException {
        El racine = elem(p);
        List<El> pile = new ArrayList<El>();
        pile.add(racine);
        while (!pile.isEmpty()) {
            int t = p.next();
            if (t == XmlPullParser.START_TAG) {
                El e = elem(p);
                pile.get(pile.size() - 1).kids.add(e);
                pile.add(e);
            } else if (t == XmlPullParser.END_TAG) {
                pile.remove(pile.size() - 1);
            } else if (t == XmlPullParser.TEXT) {
                String s = p.getText();
                if (s != null && !s.isEmpty()) pile.get(pile.size() - 1).kids.add(s);
            } else if (t == XmlPullParser.END_DOCUMENT) {
                break;
            }
        }
        return racine;
    }

    private static El elem(XmlPullParser p) {
        El e = new El(nom(p));
        int n = p.getAttributeCount();
        for (int i = 0; i < n; i++) {
            e.attrs.add(prefixe(p.getAttributeNamespace(i)) + p.getAttributeName(i));
            e.attrs.add(p.getAttributeValue(i));
        }
        return e;
    }

    /** Lit tout un document. */
    public static El document(InputStream in) throws XmlPullParserException, IOException {
        XmlPullParser p = parser(in);
        int t;
        while ((t = p.next()) != XmlPullParser.START_TAG) {
            if (t == XmlPullParser.END_DOCUMENT) return new El("");
        }
        return lire(p);
    }
}
