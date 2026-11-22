package fr.minilauncher;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

/** Lancement des applis, partagé par l'écran de la roue et la bulle flottante. */
final class Launcher {

    private Launcher() {
    }

    static boolean launch(Context context, AppRepository.AppEntry app, Rect bounds, View from) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        intent.setSourceBounds(bounds);
        Bundle options = null;
        if (from != null && bounds != null) {
            int[] loc = new int[2];
            from.getLocationOnScreen(loc);
            options = ActivityOptions.makeScaleUpAnimation(from, bounds.left - loc[0], bounds.top - loc[1],
                    bounds.width(), bounds.height()).toBundle();
        }
        try {
            context.startActivity(intent, options);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(context, "Impossible d'ouvrir " + app.label, Toast.LENGTH_SHORT).show();
            return false;
        }
        Store.get(context).recordLaunch(Store.key(app.component));
        return true;
    }

    static void appInfo(Context context, ComponentName cn) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", cn.getPackageName(), null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
        }
    }
}
