package fr.minilauncher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Écran transparent qui affiche les menus d'appui long demandés depuis la bulle. */
public final class ActionsActivity extends Activity {

    static Intent appMenu(Context c, AppRepository.AppEntry app, String group) {
        return new Intent(c, ActionsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("component", app.component.flattenToString())
                .putExtra("label", app.label)
                .putExtra("group", group);
    }

    static Intent groupMenu(Context c, String group) {
        return new Intent(c, ActionsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("group", group);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String flat = getIntent().getStringExtra("component");
        String group = getIntent().getStringExtra("group");
        Runnable none = () -> {
        };
        Runnable done = this::finish;
        if (flat != null) {
            ComponentName cn = ComponentName.unflattenFromString(flat);
            String label = getIntent().getStringExtra("label");
            if (cn == null) {
                finish();
                return;
            }
            Menus.appMenu(this, cn, label == null ? "" : label, group, none, done);
        } else if (group != null) {
            Menus.groupMenu(this, group, none, done);
        } else {
            finish();
        }
    }
}
