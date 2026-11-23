package fr.minilauncher;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applis récentes, pour basculer de l'une à l'autre. Avec l'« accès aux données
 * d'utilisation », la liste vient d'Android (toutes les applis utilisées) ; sinon, de
 * l'historique des lancements faits depuis le launcher.
 */
final class Recents {

    private static final int MAX = 12;

    private Recents() {
    }

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        } else {
            mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** {appli, heure de dernière utilisation}, de la plus récente à la plus ancienne. */
    static List<Object[]> load(Context c, List<AppRepository.AppEntry> apps) {
        Map<String, AppRepository.AppEntry> byPackage = new HashMap<>();
        Map<String, AppRepository.AppEntry> byKey = new HashMap<>();
        for (AppRepository.AppEntry e : apps) {
            byPackage.putIfAbsent(e.component.getPackageName(), e);
            byKey.put(Store.key(e.component), e);
        }
        Set<String> skip = launchers(c);
        skip.add(c.getPackageName());
        skip.add("com.android.systemui");

        LinkedHashMap<AppRepository.AppEntry, Long> out = new LinkedHashMap<>();
        if (hasUsageAccess(c)) {
            UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            Map<String, Long> last = new HashMap<>();
            if (usm != null) {
                UsageEvents events = usm.queryEvents(now - 3 * DateUtils.DAY_IN_MILLIS, now);
                UsageEvents.Event ev = new UsageEvents.Event();
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev);
                    if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        last.put(ev.getPackageName(), ev.getTimeStamp());
                    }
                }
            }
            List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
            sorted.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
            for (Map.Entry<String, Long> en : sorted) {
                if (out.size() >= MAX) break;
                if (skip.contains(en.getKey())) continue;
                AppRepository.AppEntry app = byPackage.get(en.getKey());
                if (app != null && !out.containsKey(app)) out.put(app, en.getValue());
            }
        } else {
            for (Object[] r : Store.get(c).recentLaunches()) {
                if (out.size() >= MAX) break;
                AppRepository.AppEntry app = byKey.get((String) r[0]);
                if (app != null && !out.containsKey(app)) out.put(app, (Long) r[1]);
            }
        }
        List<Object[]> list = new ArrayList<>();
        for (Map.Entry<AppRepository.AppEntry, Long> en : out.entrySet()) list.add(new Object[]{en.getKey(), en.getValue()});
        return list;
    }

    private static Set<String> launchers(Context c) {
        Set<String> out = new HashSet<>();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
            out.add(ri.activityInfo.packageName);
        }
        return out;
    }

    /** Le panneau : une rangée de cartes à faire défiler, toucher = basculer. */
    static void show(Activity a, List<AppRepository.AppEntry> apps, Runnable changed) {
        Sheet s = new Sheet(a, "Applis récentes");
        List<Object[]> recent = load(a, apps);
        long now = System.currentTimeMillis();

        if (recent.isEmpty()) {
            TextView t = s.text("Aucune appli récente pour l'instant.", 15, Sheet.MUTED);
            t.setPadding(s.px(22), s.px(8), s.px(22), s.px(16));
            s.content.addView(t);
        } else {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipToPadding(false);
            hs.setPadding(s.px(14), s.px(4), s.px(14), s.px(14));
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            hs.addView(row);
            for (Object[] r : recent) {
                AppRepository.AppEntry app = (AppRepository.AppEntry) r[0];
                long time = (Long) r[1];
                row.addView(card(s, a, app, time, now));
            }
            s.content.addView(hs);
        }

        if (!hasUsageAccess(a)) {
            Sheet.Item it = new Sheet.Item("Voir toutes les applis utilisées");
            it.subtitle = "Autoriser « Accès aux données d'utilisation ». Sinon : seulement celles "
                    + "ouvertes depuis le launcher.";
            View r = s.row(it);
            ((TextView) ((LinearLayout) ((LinearLayout) r).getChildAt(0)).getChildAt(0)).setTextColor(s.accent);
            r.setOnClickListener(v -> {
                s.dialog.dismiss();
                Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                if (Build.VERSION.SDK_INT >= 29) i.setData(Uri.parse("package:" + a.getPackageName()));
                try {
                    a.startActivity(i);
                } catch (ActivityNotFoundException e) {
                    a.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                }
            });
            s.addDivider();
            s.content.addView(r);
        }
        s.show();
    }

    private static View card(Sheet s, Activity a, AppRepository.AppEntry app, long time, long now) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Sheet.FIELD);
        bg.setCornerRadius(s.px(22));
        c.setBackground(bg);
        c.setClipToOutline(true);
        c.setPadding(s.px(10), s.px(18), s.px(10), s.px(14));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(s.px(112), LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.setMargins(s.px(6), 0, s.px(6), 0);
        c.setLayoutParams(cl);

        ImageView icon = new ImageView(a);
        icon.setImageDrawable(app.icon);
        c.addView(icon, new LinearLayout.LayoutParams(s.px(60), s.px(60)));

        TextView name = s.text(app.label, 14, Sheet.TEXT);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, s.px(12), 0, 0);
        c.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        CharSequence ago = now - time < DateUtils.MINUTE_IN_MILLIS ? "à l'instant"
                : DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE);
        TextView when = s.text(ago.toString(), 12, Sheet.MUTED);
        when.setGravity(Gravity.CENTER);
        when.setSingleLine(true);
        when.setPadding(0, s.px(2), 0, 0);
        c.addView(when, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(s.px(22));
        mask.setColor(0xFFFFFFFF);
        c.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, mask));
        c.setOnClickListener(v -> {
            s.dialog.dismiss();
            int[] loc = new int[2];
            icon.getLocationOnScreen(loc);
            Launcher.launch(a, app, new Rect(loc[0], loc[1], loc[0] + icon.getWidth(), loc[1] + icon.getHeight()), null);
        });
        c.setOnLongClickListener(v -> {
            s.dialog.dismiss();
            Menus.appMenu(a, app.component, app.label, null, () -> {
            });
            return true;
        });
        return c;
    }
}
