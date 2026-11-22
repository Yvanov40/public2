package fr.minilauncher;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Widget 1×1. Contrairement à l'icône, c'est Android lui-même qui transmet la position
 * exacte du widget touché : la roue s'ouvre autour, quel que soit le launcher.
 */
public final class RadialWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        Intent intent = new Intent(context, RadialActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // Modifiable : Android y ajoute la position du widget au moment du toucher.
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent, flags);
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
            views.setOnClickPendingIntent(R.id.widget_icon, pending);
            manager.updateAppWidget(id, views);
        }
    }
}
