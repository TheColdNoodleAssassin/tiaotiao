package com.pcxx.tiaotiao;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import android.view.animation.DecelerateInterpolator;

/**
 * 把 {@link FloatingBallView} 挂到 WindowManager 上的常驻服务。
 *
 * <p>关键点：
 * <ul>
 *   <li>窗口类型用 {@link WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY}，
 *       这是 Android 8.0 起三方应用「覆盖在其他应用之上」的<b>正规通道</b>，
 *       只需 {@code SYSTEM_ALERT_WINDOW} 由用户在系统设置里点一下授权，<b>不需要系统签名、不需要厂商审批</b>。</li>
 *   <li>{@code FLAG_NOT_FOCUSABLE}：不抢焦点，否则会在别的应用输入时顶掉软键盘。</li>
 *   <li>松手后自动吸附到最近的一侧边缘，并把位置写进 SharedPreferences。</li>
 *   <li>坐标全部是<b>屏幕物理像素</b>，用 {@code getCurrentWindowMetrics()} 取屏宽（含刘海的安全区域以 LayoutParams 的 x/y 为准）。</li>
 * </ul>
 */
public class FloatService extends Service implements FloatingBallView.Listener {

    public static final String ACTION_SHOW = "com.pcxx.tiaotiao.action.SHOW";
    public static final String ACTION_HIDE = "com.pcxx.tiaotiao.action.HIDE";

    private static final String CHANNEL_ID = "tiaotiao_float";
    private static final int NOTIFICATION_ID = 1001;
    private static final String PREFS = "tiaotiao";
    private static final String KEY_X = "ball_x";
    private static final String KEY_Y = "ball_y";

    /** 悬浮球直径，56dp 在 6.8 寸屏上约 2.6mm 半径，手指点得准又不挡内容 */
    private static final int BALL_DP = 56;

    private WindowManager windowManager;
    private FloatingBallView ballView;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;

    private int screenW;
    private int screenH;
    private int dragStartX;
    private int dragStartY;

    private boolean showing;

    /** 悬浮球是否真的挂在窗口上，供首页显示「开启 / 关闭」用 */
    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        measureScreen();
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            hideBall();
            stopSelf();
            return START_NOT_STICKY;
        }
        showBall();
        // START_STICKY：被系统回收后尽量自动恢复，避免用户以为球丢了
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        hideBall();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 前台通知

    /**
     * Android 8.0 起悬浮窗这类「长期在后台运行」的服务必须升为前台服务，
     * 否则进程随时可能被回收，球就会莫名消失。
     */
    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_desc));
            channel.setShowBadge(false);
            channel.enableVibration(false);
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent hide = new Intent(this, FloatService.class).setAction(ACTION_HIDE);
        PendingIntent hideIntent = PendingIntent.getService(
                this, 1, hide, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_next)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.notif_action_hide), hideIntent).build())
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ------------------------------------------------------------ 悬浮球

    private void measureScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            screenW = bounds.width();
            screenH = bounds.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
        }
    }

    private void showBall() {
        if (showing) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("还没有悬浮窗权限，请先回到跳跳里授权");
            stopSelf();
            return;
        }
        measureScreen();

        ballView = new FloatingBallView(this);
        ballView.setListener(this);

        int size = dp(BALL_DP);
        params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        // 恢复上次位置；首次启动放到右侧偏上，避开拇指常用区域
        int defX = screenW - size - dp(8);
        int defY = (int) (screenH * 0.42f);
        params.x = clamp(prefs.getInt(KEY_X, defX), 0, Math.max(0, screenW - size));
        params.y = clamp(prefs.getInt(KEY_Y, defY), 0, Math.max(0, screenH - size));

        try {
            windowManager.addView(ballView, params);
            showing = true;
            running = true;
        } catch (Throwable t) {
            toast("悬浮球创建失败：" + t.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void hideBall() {
        if (!showing || ballView == null) {
            return;
        }
        try {
            windowManager.removeView(ballView);
        } catch (Throwable ignored) {
            // 已经移除过就忽略
        }
        ballView = null;
        showing = false;
        running = false;
    }

    // ------------------------------------------------------------ 手势回调

    @Override
    public void onBallTap() {
        NextTrackController.Result result = NextTrackController.sendNext(this);
        if (result.dispatched) {
            if (ballView != null) {
                ballView.pulseSuccess();
            }
            if (!result.audioActive) {
                toast("已发送，但当前没有检测到正在播放的音频");
            }
        } else {
            toast("发送失败，请重试");
        }
    }

    @Override
    public void onBallLongPress() {
        // 长按回到 App，那里有关闭开关 —— 避免用户找不到退出方式
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(open);
        toast("长按已回到跳跳，可在首页关闭悬浮球");
    }

    @Override
    public void onBallDragStart() {
        dragStartX = params.x;
        dragStartY = params.y;
    }

    @Override
    public void onBallDrag(int dx, int dy) {
        if (!showing || ballView == null) {
            return;
        }
        params.x = clamp(dragStartX + dx, 0, Math.max(0, screenW - params.width));
        params.y = clamp(dragStartY + dy, 0, Math.max(0, screenH - params.height));
        try {
            windowManager.updateViewLayout(ballView, params);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onBallDragEnd() {
        if (!showing || ballView == null) {
            return;
        }
        int targetX = params.x + params.width / 2 < screenW / 2 ? 0 : screenW - params.width;
        int fromX = params.x;
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(180);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            params.x = (int) (fromX + (targetX - fromX) * t);
            try {
                if (ballView != null) {
                    windowManager.updateViewLayout(ballView, params);
                }
            } catch (Throwable ignored) {
            }
        });
        animator.start();
        prefs.edit().putInt(KEY_X, targetX).putInt(KEY_Y, params.y).apply();
    }

    // ------------------------------------------------------------ 工具

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
