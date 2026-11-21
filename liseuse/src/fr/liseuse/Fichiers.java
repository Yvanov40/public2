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

    public static final String AUTORITE = "fr.liseuse.fichiers";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fichier(Uri uri) throws FileNotFoundException {
        List<String> s = uri.getPathSegments();
        if (s.isEmpty() || !s.get(0).matches("[0-9a-f]{6,32}")) throw new FileNotFoundException();
        File f = new File(getContext().getFilesDir(), "livres/" + s.get(0) + ".epub");
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
