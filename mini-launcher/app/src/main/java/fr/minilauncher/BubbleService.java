package fr.minilauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

/**
 * Bulle flottante par-dessus toutes les applis. On pose le doigt dessus : la roue s'ouvre
 * sous le doigt, on glisse jusqu'à l'appli et on lâche — un seul geste.
 * Pour déplacer la bulle : maintenir le centre de la roue puis glisser.
 */
public final class BubbleService extends Service implements RadialView.Listener {

    private static final String CHANNEL = "bulle";

    /** Démarre ou arrête la bulle selon le réglage et l'autorisation. */
    static void sync(Context context) {
        Intent intent = new Intent(context, BubbleService.class);
        boolean wanted = Store.get(context).bubble() && Settings.canDrawOverlays(context);
        try {
            if (wanted) context.startForegroundService(intent);
            else context.stopService(intent);
        } catch (RuntimeException ignored) {
            // Démarrage refusé par le système (arrière-plan) : on réessaiera à la prochaine ouverture.
        }
    }

    private WindowManager wm;
    private BubbleView bubble;
    private RadialView wheel;
    private WindowManager.LayoutParams bubbleParams, wheelParams;
    private AppRepository repository;
    private boolean wheelShown;
    private float dp;

    private final AppRepository.Callback callback = new AppRepository.Callback() {
        @Override
        public void onAppsChanged(List<AppRepository.AppEntry> apps) {
            if (wheel != null) wheel.setApps(apps);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        dp = getResources().getDisplayMetrics().density;
        startInForeground();
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        repository = AppRepository.get(this);

        wheel = new RadialView(this);
        wheel.setListener(this);
        wheel.setMovable(true);
        wheel.setVisibility(View.INVISIBLE);
        wheel.dismissNow();
        wheelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wheelParams.gravity = Gravity.TOP | Gravity.START;
        wheelParams.alpha = 0f;
        if (Build.VERSION.SDK_INT >= 28) {
            wheelParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(wheel, wheelParams);

        int size = (int) (46 * dp);
        bubble = new BubbleView(this);
        bubbleParams = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        placeBubble();
        bubble.setOnTouchListener(this::onBubbleTouch);
        wm.addView(bubble, bubbleParams);

        wheel.setApps(repository.cached());
        repository.refresh(callback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wm != null) {
            try {
                wm.removeView(bubble);
                wm.removeView(wheel);
            } catch (RuntimeException ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (bubble != null) {
            hideWheel();
            placeBubble();
            wm.updateViewLayout(bubble, bubbleParams);
        }
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Bulle flottante",
                NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 1,
                new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Bulle Mini Launcher active")
                .setContentText("Toucher pour les réglages")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private DisplayMetrics screen() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    private void placeBubble() {
        DisplayMetrics m = screen();
        float[] p = Store.get(this).position();
        float x = p != null ? p[0] : 0.93f, y = p != null ? p[1] : 0.62f;
        bubbleParams.x = (int) (x * m.widthPixels) - bubbleParams.width / 2;
        bubbleParams.y = (int) (y * m.heightPixels) - bubbleParams.height / 2;
    }

    private boolean onBubbleTouch(View v, MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) showWheel(e);
        else forward(e);
        return true;
    }

    private void showWheel(MotionEvent down) {
        wheelShown = true;
        wheelParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wheelParams.alpha = 1f;
        wm.updateViewLayout(wheel, wheelParams);
        wheel.setVisibility(View.VISIBLE);
        int[] loc = new int[2];
        wheel.getLocationOnScreen(loc);
        float x = down.getRawX() - loc[0], y = down.getRawY() - loc[1];
        wheel.openAt(x, y, true);
        forward(down);
        bubble.setAlpha(0f);
        repository.refresh(callback);
    }

    private void forward(MotionEvent e) {
        if (!wheelShown) return;
        int[] loc = new int[2];
        wheel.getLocationOnScreen(loc);
        MotionEvent copy = MotionEvent.obtain(e);
        copy.setLocation(e.getRawX() - loc[0], e.getRawY() - loc[1]);
        wheel.dispatchTouchEvent(copy);
        copy.recycle();
    }

    private void hideWheel() {
        if (wheel == null) return;
        wheelShown = false;
        wheel.dismissNow();
        wheel.setVisibility(View.INVISIBLE);
        wheelParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        wheelParams.alpha = 0f;
        wm.updateViewLayout(wheel, wheelParams);
        bubble.setAlpha(1f);
    }

    // ------------------------------------------------------------ RadialView.Listener

    @Override
    public void onLaunch(AppRepository.AppEntry app, Rect bounds) {
        Launcher.launch(this, app, bounds, null);
        hideWheel();
    }

    @Override
    public void onAppMenu(AppRepository.AppEntry app, String group) {
        hideWheel();
        startActivity(ActionsActivity.appMenu(this, app, group));
    }

    @Override
    public void onGroupMenu(String group) {
        hideWheel();
        startActivity(ActionsActivity.groupMenu(this, group));
    }

    @Override
    public void onMoved(float cx, float cy) {
        // La roue a été déplacée (appui long sur la bulle) : la bulle suit.
        placeBubble();
        wm.updateViewLayout(bubble, bubbleParams);
        hideWheel();
    }

    @Override
    public void onClosed() {
        hideWheel();
    }

    /** La pastille : un rond sombre avec les points de l'icône. */
    private static final class BubbleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        BubbleView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), r = w / 2f;
            paint.setColor(0xCC1B1D26);
            canvas.drawCircle(r, r, r - 1, paint);
            for (int i = 0; i < 12; i++) {
                double a = -Math.PI / 2 + i * Math.PI / 6;
                paint.setColor(i == 0 ? 0xFF8FA6FF : Color.argb(200, 255, 255, 255));
                canvas.drawCircle(r + (float) (r * 0.55 * Math.cos(a)), r + (float) (r * 0.55 * Math.sin(a)),
                        r * (i == 0 ? 0.1f : 0.075f), paint);
            }
            paint.setColor(0xFF8FA6FF);
            canvas.drawCircle(r, r, r * 0.2f, paint);
        }
    }
}
