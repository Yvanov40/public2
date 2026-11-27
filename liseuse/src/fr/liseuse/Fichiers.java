package fr.liseuse;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Donne accès aux EPUB pour le partage : content://fr.liseuse.fichiers/<id>/<nom>.epub */
public final class Fichiers extends ContentProvider {

    /** Autorité du fournisseur : « identifiant de l'appli ».fichiers (deux éditions peuvent cohabiter). */
    public static String autorite(android.content.Context c) {
        return c.getPackageName() + ".fichiers";
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fichier(Uri uri) throws FileNotFoundException {
        List<String> s = uri.getPathSegments();
        if (s.size() == 2 && "maj".equals(s.get(0)) && "Liseuse.apk".equals(s.get(1))) {
            File m = new File(getContext().getCacheDir(), "maj/Liseuse.apk");
            if (!m.exists()) throw new FileNotFoundException();
            return m;
        }
        if (s.isEmpty() || !s.get(0).matches("[0-9a-f]{6,32}")) throw new FileNotFoundException();
        String nom = uri.getLastPathSegment();
        boolean pdf = nom != null && nom.toLowerCase().endsWith(".pdf");
        File f = new File(getContext().getFilesDir(), "livres/" + s.get(0) + (pdf ? ".pdf" : ".epub"));
        if (!f.exists() && pdf) f = new File(getContext().getFilesDir(), "sources/" + s.get(0) + ".pdf");
        if (!f.exists()) f = new File(getContext().getFilesDir(), "livres/" + s.get(0) + ".pdf");
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fichier(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] proj, String sel, String[] args, String tri) {
        File f;
        try {
            f = fichier(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        if (proj == null) proj = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(proj, 1);
        Object[] ligne = new Object[proj.length];
        for (int i = 0; i < proj.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(proj[i])) ligne[i] = uri.getLastPathSegment();
            else if (OpenableColumns.SIZE.equals(proj[i])) ligne[i] = f.length();
        }
        c.addRow(ligne);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        if (n != null && n.toLowerCase().endsWith(".pdf")) return "application/pdf";
        if (n != null && n.toLowerCase().endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/epub+zip";
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
