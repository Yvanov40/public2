package fr.luxmetre;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/** Fenêtre noire semi-transparente posée sur tout l'écran ; les touchers passent à travers. */
final class Voile {
    private final WindowManager windowManager;
    private final Context ctx;
    private final int type;
    private View vue;
    private WindowManager.LayoutParams params;

    Voile(Context ctx, int type) {
        this.ctx = ctx;
        this.type = type;
        this.windowManager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    void afficher(int niveau) {
        float alpha = niveau / 100f;
        if (vue == null) {
            vue = new View(ctx);
            vue.setBackgroundColor(Color.BLACK);
            int taille = 2 * Math.max(ctx.getResources().getDisplayMetrics().widthPixels,
                    ctx.getResources().getDisplayMetrics().heightPixels);
            params = new WindowManager.LayoutParams(taille, taille, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.CENTER;
            if (Build.VERSION.SDK_INT >= 30) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                params.setFitInsetsTypes(0);
            } else if (Build.VERSION.SDK_INT >= 28) {
                params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            params.alpha = alpha;
            windowManager.addView(vue, params);
        } else {
            params.alpha = alpha;
            windowManager.updateViewLayout(vue, params);
        }
    }

    void cacher() {
        if (vue != null) {
            windowManager.removeView(vue);
            vue = null;
        }
    }
}
