package fr.minilauncher;

import android.content.Context;
import android.gesture.Gesture;
import android.gesture.GestureLibraries;
import android.gesture.GestureLibrary;
import android.gesture.GesturePoint;
import android.gesture.GestureStroke;
import android.gesture.Prediction;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lettres dessinées : chaque tracé appris est rangé sous la clé de l'appli qu'il ouvre.
 * Plusieurs tracés pour une même appli = meilleure reconnaissance.
 */
final class Drawings {

    /** Score minimal pour accepter une reconnaissance (plus haut = plus strict). */
    private static final double MIN_SCORE = 3.0;

    private static GestureLibrary library;

    private Drawings() {
    }

    private static synchronized GestureLibrary lib(Context c) {
        if (library == null) {
            library = GestureLibraries.fromFile(new File(c.getFilesDir(), "dessins"));
            library.setOrientationStyle(android.gesture.GestureStore.ORIENTATION_SENSITIVE);
            library.setSequenceType(android.gesture.GestureStore.SEQUENCE_SENSITIVE);
            library.load();
        }
        return library;
    }


    /** Fabrique un tracé à partir des points du doigt : {x, y, temps}. */
    static Gesture fromPoints(List<float[]> points) {
        ArrayList<GesturePoint> gp = new ArrayList<>();
        for (float[] p : points) gp.add(new GesturePoint(p[0], p[1], (long) p[2]));
        Gesture g = new Gesture();
        g.addStroke(new GestureStroke(gp));
        return g;
    }

    /** Clé de l'appli reconnue, ou null. */
    static String recognize(Context c, Gesture g) {
        GestureLibrary l = lib(c);
        if (l.getGestureEntries().isEmpty()) return null;
        ArrayList<Prediction> ps = l.recognize(g);
        if (ps == null || ps.isEmpty()) return null;
        Prediction best = ps.get(0);
        return best.score >= MIN_SCORE ? best.name : null;
    }

    static void learn(Context c, Gesture g, String appKey) {
        GestureLibrary l = lib(c);
        l.addGesture(appKey, g);
        l.save();
    }

    static void forget(Context c, String appKey) {
        GestureLibrary l = lib(c);
        l.removeEntry(appKey);
        l.save();
    }

    /** Applis qui ont au moins un tracé, et le nombre de tracés de chacune. */
    static List<Object[]> entries(Context c) {
        GestureLibrary l = lib(c);
        List<Object[]> out = new ArrayList<>();
        Set<String> names = l.getGestureEntries();
        for (String n : names) {
            ArrayList<Gesture> gs = l.getGestures(n);
            out.add(new Object[]{n, gs == null ? 0 : gs.size(), gs == null || gs.isEmpty() ? null : gs.get(0)});
        }
        return out;
    }
}
