package fr.minilauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Reçoit de l'appli « Pastilles » le nombre de notifications de chaque appli. */
public final class BadgeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ArrayList<String> packages = intent.getStringArrayListExtra("packages");
        ArrayList<Integer> counts = intent.getIntegerArrayListExtra("counts");
        Map<String, Integer> map = new HashMap<>();
        if (packages != null && counts != null) {
            for (int i = 0; i < Math.min(packages.size(), counts.size()); i++) map.put(packages.get(i), counts.get(i));
        }
        Badges.set(map, true);
    }
}
