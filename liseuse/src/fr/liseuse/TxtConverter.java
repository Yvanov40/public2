package fr.liseuse;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.regex.Pattern;

/** Conversion d'un simple fichier texte en EPUB. */
public final class TxtConverter {

    private static final Pattern P_CHAPITRE = Pattern.compile(
            "^(chapitre|chapter|cap[ií]tulo|partie|part|livre|prologue|[ée]pilogue|epilogue|introduction|conclusion|pr[ée]face)\\b.{0,60}$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static DocxConverter.Resultat convertir(File f, OutputStream sortie, String nom, DocxConverter.Images images) throws Exception {
        byte[] data;
        InputStream in = new FileInputStream(f);
        try {
            data = EpubBuilder.lireTout(in);
        } finally {
            in.close();
        }
        String txt = decoder(data);
        if (txt.startsWith("﻿")) txt = txt.substring(1);
        txt = txt.replace("\r\n", "\n").replace('\r', '\n');
        String[] lignes = txt.split("\n", -1);
        int vides = 0;
        for (String l : lignes) if (l.trim().isEmpty()) vides++;
        // texte « coupé à la ligne » : les paragraphes sont séparés par des lignes vides
        boolean blocs = vides > lignes.length / 8 && lignes.length > 20;

        EpubBuilder ep = new EpubBuilder(sortie);
        ep.titre = DocxConverter.sansExtension(nom);
        StringBuilder para = new StringBuilder();
        int n = 0;
        for (int i = 0; i <= lignes.length; i++) {
            String l = i < lignes.length ? lignes[i] : "";
            boolean vide = l.trim().isEmpty();
            if (blocs && !vide) {
                if (para.length() > 0) para.append(' ');
                para.append(l.trim());
                continue;
            }
            String p = blocs ? para.toString() : l.trim();
            para.setLength(0);
            if (p.isEmpty()) continue;
            if (P_CHAPITRE.matcher(p).matches()) {
                if (!ep.chapitreVide()) ep.nouveauChapitre(null);
                n++;
                ep.entreeToc(1, p, "t" + n);
                ep.html().append("<h1 id=\"t").append(n).append("\">");
                EpubBuilder.echapper(p, ep.html());
                ep.html().append("</h1>\n");
            } else {
                if (ep.tailleChapitre() > 160000) ep.nouveauChapitre(null);
                ep.html().append("<p>");
                EpubBuilder.echapper(p, ep.html());
                ep.html().append("</p>\n");
            }
            ep.marquerContenu();
        }
        ep.finir(images.couverture(ep.titre, ""));
        DocxConverter.Resultat r = new DocxConverter.Resultat();
        r.titre = ep.titre;
        r.auteur = "";
        r.chapitres = ep.chapitres.size();
        return r;
    }

    static String decoder(byte[] data) {
        try {
            return Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("windows-1252"));
        }
    }
}
