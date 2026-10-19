package com.pcxx.tiaotiao;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 首页：一句话说清用途 + 三个状态 + 一个按钮。
 *
 * <p>设计上刻意做减法：能自动判断的不让用户点，缺权限时把「状态行」本身变成入口，
 * 所以页面上永远只有一个主按钮（开启 / 关闭悬浮球）。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATION = 101;

    private View dotOverlay;
    private View dotNotify;
    private View dotAudio;
    private TextView tvOverlayState;
    private TextView tvNotificationState;
    private TextView tvAudioState;
    private TextView tvHint;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dotOverlay = findViewById(R.id.dot_overlay);
        dotNotify = findViewById(R.id.dot_notify);
        dotAudio = findViewById(R.id.dot_audio);
        tvOverlayState = findViewById(R.id.tv_overlay_state);
        tvNotificationState = findViewById(R.id.tv_notification_state);
        tvAudioState = findViewById(R.id.tv_audio_state);
        tvHint = findViewById(R.id.tv_hint);
        btnToggle = findViewById(R.id.btn_toggle);

        applyInsets(findViewById(R.id.root));

        btnToggle.setOnClickListener(v -> toggleBall());
        // 缺权限时整行就是入口，少两个按钮
        findViewById(R.id.row_overlay).setOnClickListener(v -> {
            if (Settings.canDrawOverlays(this)) {
                toast(getString(R.string.state_granted));
            } else {
                requestOverlayPermission();
            }
        });
        findViewById(R.id.row_notify).setOnClickListener(v -> {
            if (hasNotificationPermission()) {
                toast(getString(R.string.state_granted));
            } else {
                requestNotificationPermission();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** targetSdk 36 强制 edge-to-edge，必须自己让出状态栏和导航栏 */
    private void applyInsets(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean notify = hasNotificationPermission();
        boolean audio = NextTrackController.isAudioActive(this);
        boolean running = FloatService.isRunning();

        bindState(tvOverlayState, dotOverlay, overlay,
                getString(R.string.state_granted), getString(R.string.state_go_grant),
                R.color.accent_text, R.drawable.dot_off);
        bindState(tvNotificationState, dotNotify, notify,
                getString(R.string.state_granted), getString(R.string.state_go_grant),
                R.color.accent_text, R.drawable.dot_off);
        // 「没有音频」不是错误，只是安静，所以用中性灰而不是红色
        bindState(tvAudioState, dotAudio, audio,
                getString(R.string.state_playing), getString(R.string.state_no_audio),
                R.color.text_secondary, R.drawable.dot_idle);

        btnToggle.setText(running ? R.string.btn_close : R.string.btn_open);

        if (!overlay) {
            tvHint.setText(R.string.hint_need_overlay);
        } else if (!notify) {
            tvHint.setText(R.string.hint_need_notify);
        } else {
            tvHint.setText(R.string.hint_default);
        }
    }

    /** 一行状态 = 一个圆点 + 一句话，颜色只有「好 / 缺 / 静」三种 */
    private void bindState(TextView text, View dot, boolean ok, String okText, String badText,
                           int badColorRes, int badDotRes) {
        text.setText(ok ? okText : badText);
        text.setTextColor(ok ? getColor(R.color.state_ok) : getColor(badColorRes));
        dot.setBackgroundResource(ok ? R.drawable.dot_ok : badDotRes);
    }

    private void toggleBall() {
        if (FloatService.isRunning()) {
            stopService(new Intent(this, FloatService.class).setAction(FloatService.ACTION_HIDE));
            toast(getString(R.string.toast_hidden));
            refresh();
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_need_overlay));
            requestOverlayPermission();
            return;
        }
        Intent intent = new Intent(this, FloatService.class).setAction(FloatService.ACTION_SHOW);
        startForegroundService(intent);
        toast(getString(R.string.toast_shown));
        refresh();
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Throwable t) {
            // 个别 ROM 没有这个页面，退回到应用详情页
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        } else {
            toast("当前系统版本不需要单独申请通知权限");
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
