package fr.minilauncher;

import android.app.Activity;
import android.appwidget.AppWidgetProviderInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Galerie des widgets, regroupés par appli : une ligne par appli (icône, nom, nombre de
 * widgets) ; la toucher déplie les aperçus de ses widgets, avec leur taille.
 */
final class WidgetPicker {

    interface Span {
        int[] of(AppWidgetProviderInfo info);
    }

    interface Pick {
        void on(AppWidgetProviderInfo info);
    }

    private WidgetPicker() {
    }

    static void show(Activity a, List<AppWidgetProviderInfo> providers, Span span, Pick pick) {
        PackageManager pm = a.getPackageManager();
        Collator collator = Collator.getInstance(Locale.getDefault());
        collator.setStrength(Collator.PRIMARY);

        // Regroupement par appli, dans l'ordre alphabétique.
        Map<String, List<AppWidgetProviderInfo>> byApp = new LinkedHashMap<>();
        List<AppWidgetProviderInfo> sorted = new ArrayList<>(providers);
        sorted.sort((x, y) -> collator.compare(appLabel(pm, x.provider.getPackageName()),
                appLabel(pm, y.provider.getPackageName())));
        for (AppWidgetProviderInfo p : sorted) {
            byApp.computeIfAbsent(p.provider.getPackageName(), k -> new ArrayList<>()).add(p);
        }

        Sheet s = new Sheet(a, "Ajouter un widget");
        EditText search = s.addSearch("Rechercher une appli ou un widget");
        List<View> blocks = new ArrayList<>();
        int dpi = a.getResources().getDisplayMetrics().densityDpi;

        for (Map.Entry<String, List<AppWidgetProviderInfo>> e : byApp.entrySet()) {
            String pkg = e.getKey();
            List<AppWidgetProviderInfo> list = e.getValue();
            String label = appLabel(pm, pkg);

            LinearLayout block = new LinearLayout(a);
            block.setOrientation(LinearLayout.VERTICAL);

            // En-tête de l'appli
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setMinimumHeight(s.px(64));
            head.setPadding(s.px(22), s.px(8), s.px(20), s.px(8));
            ImageView icon = new ImageView(a);
            try {
                icon.setImageDrawable(pm.getApplicationIcon(pkg));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(s.px(40), s.px(40));
            il.rightMargin = s.px(16);
            head.addView(icon, il);
            LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(s.text(label, 16, Sheet.TEXT));
            texts.addView(s.text(list.size() == 1 ? "1 widget" : list.size() + " widgets", 13, Sheet.MUTED));
            head.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView chevron = s.text("›", 24, 0xFF5C6273);
            head.addView(chevron);
            Sheet.ripple(head);
            block.addView(head);

            // Aperçus, créés au premier dépliage
            LinearLayout cards = new LinearLayout(a);
            cards.setOrientation(LinearLayout.VERTICAL);
            cards.setVisibility(View.GONE);
            cards.setPadding(s.px(18), 0, s.px(18), s.px(10));
            block.addView(cards);

            head.setOnClickListener(v -> {
                boolean open = cards.getVisibility() != View.VISIBLE;
                if (open && cards.getChildCount() == 0) {
                    for (AppWidgetProviderInfo info : list) cards.addView(card(s, a, pm, info, span, pick, dpi));
                }
                cards.setVisibility(open ? View.VISIBLE : View.GONE);
                chevron.animate().rotation(open ? 90 : 0).setDuration(150).start();
            });

            StringBuilder tag = new StringBuilder(label);
            for (AppWidgetProviderInfo info : list) tag.append(' ').append(info.loadLabel(pm));
            block.setTag(Sheet.fold(tag.toString()));
            blocks.add(block);
            s.content.addView(block);
        }
        if (blocks.isEmpty()) {
            TextView t = s.text("Aucun widget disponible", 15, Sheet.MUTED);
            t.setPadding(s.px(22), s.px(16), s.px(22), s.px(16));
            s.content.addView(t);
        }
        Sheet.filterOn(search, blocks);
        s.show();
    }

    private static View card(Sheet s, Activity a, PackageManager pm, AppWidgetProviderInfo info,
                             Span span, Pick pick, int dpi) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF242730);
        bg.setCornerRadius(s.px(18));
        c.setBackground(bg);
        c.setClipToOutline(true);
        c.setPadding(s.px(14), s.px(14), s.px(14), s.px(12));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cl.topMargin = s.px(10);
        c.setLayoutParams(cl);

        Drawable preview = null;
        try {
            preview = info.loadPreviewImage(a, dpi);
            if (preview == null) preview = info.loadIcon(a, dpi);
        } catch (RuntimeException ignored) {
        }
        if (preview != null) {
            ImageView iv = new ImageView(a);
            iv.setImageDrawable(preview);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setMaxHeight(s.px(150));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            pl.bottomMargin = s.px(10);
            c.addView(iv, pl);
        }

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = s.text(info.loadLabel(pm), 15, Sheet.TEXT);
        bottom.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        int[] sp = span.of(info);
        TextView chip = s.text(sp[0] + " × " + sp[1], 13, s.accent);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable cb = new GradientDrawable();
        cb.setCornerRadius(s.px(12));
        cb.setColor((s.accent & 0x00FFFFFF) | 0x26000000);
        chip.setBackground(cb);
        chip.setPadding(s.px(10), s.px(4), s.px(10), s.px(4));
        bottom.addView(chip);
        c.addView(bottom);

        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(s.px(18));
        mask.setColor(0xFFFFFFFF);
        c.setForeground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, mask));
        c.setOnClickListener(v -> {
            s.dialog.dismiss();
            pick.on(info);
        });
        return c;
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
