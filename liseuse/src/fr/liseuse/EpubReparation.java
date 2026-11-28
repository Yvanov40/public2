package fr.liseuse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Remet en forme un EPUB mal emballé : contenu rangé dans un sous-dossier de l'archive
 * (par exemple « 6/META-INF/container.xml » au lieu de « META-INF/container.xml »),
 * ou fichier « mimetype » absent ou mal placé. Le résultat est un EPUB standard.
 */
final class EpubReparation {

    private EpubReparation() {
    }

    /** Préfixe du dossier qui contient META-INF/container.xml (« » si l'EPUB est déjà correct), ou null. */
    static String prefixe(ZipFile z) {
        if (z.getEntry("META-INF/container.xml") != null) return "";
        String meilleur = null;
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.endsWith("META-INF/container.xml")) {
                String p = n.substring(0, n.length() - "META-INF/container.xml".length());
                if (meilleur == null || p.length() < meilleur.length()) meilleur = p;
            }
        }
        return meilleur;
    }

    /** Est-ce un EPUB, éventuellement mal emballé ? */
    static boolean estEpub(ZipFile z) {
        if (prefixe(z) != null) return true;
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) if (en.nextElement().getName().toLowerCase().endsWith(".opf")) return true;
        return false;
    }

    /** Copie l'EPUB en le remettant en forme si besoin. */
    static void copier(File src, File dest) throws IOException {
        ZipFile z = new ZipFile(src);
        try {
            String p = prefixe(z);
            if (p == null) p = "";
            ZipOutputStream o = new ZipOutputStream(new FileOutputStream(dest));
            try {
                // 1. « mimetype » en premier, non compressé (exigé par la norme EPUB)
                byte[] mt = "application/epub+zip".getBytes("US-ASCII");
                ZipEntry m = new ZipEntry("mimetype");
                m.setMethod(ZipEntry.STORED);
                m.setSize(mt.length);
                m.setCompressedSize(mt.length);
                CRC32 crc = new CRC32();
                crc.update(mt);
                m.setCrc(crc.getValue());
                o.putNextEntry(m);
                o.write(mt);
                o.closeEntry();
                // 2. le reste, ramené à la racine de l'archive
                Enumeration<? extends ZipEntry> en = z.entries();
                java.util.Set<String> vus = new java.util.HashSet<String>();
                vus.add("mimetype");
                byte[] b = new byte[65536];
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || !n.startsWith(p)) continue;
                    String nouveau = n.substring(p.length());
                    if (nouveau.isEmpty() || nouveau.equals("mimetype") || !vus.add(nouveau)) continue;
                    o.putNextEntry(new ZipEntry(nouveau));
                    InputStream in = z.getInputStream(e);
                    try {
                        int k;
                        while ((k = in.read(b)) > 0) o.write(b, 0, k);
                    } finally {
                        in.close();
                    }
                    o.closeEntry();
                }
            } finally {
                o.close();
            }
        } finally {
            z.close();
        }
    }
}
