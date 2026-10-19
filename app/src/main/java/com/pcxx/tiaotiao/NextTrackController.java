package com.pcxx.tiaotiao;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * 「下一曲」下发器。
 *
 * <p>Android 上这件事非常简单，而且语义正好就是需求想要的：
 * 调用 {@link AudioManager#dispatchMediaKeyEvent(KeyEvent)} 向系统广播一次
 * {@link KeyEvent#KEYCODE_MEDIA_NEXT}，等价于蓝牙耳机上按一下「下一曲」键。
 *
 * <p>它与「向某个已配对的蓝牙设备发 AVRCP 指令」不是同一件事，也不需要蓝牙权限：
 * <ul>
 *   <li>命令目标是<b>系统当前活跃的媒体会话</b>，由系统路由；</li>
 *   <li>音频输出到哪里（扬声器 / 有线 / 蓝牙耳机）完全不影响下发；</li>
 *   <li>该 API 自 API 19 起公开，<b>不需要任何运行时权限</b>。</li>
 * </ul>
 *
 * <p>唯一的失败模式是「当前没有活跃媒体会话」——此时系统会静默丢弃按键，
 * 所以这里额外用 {@link AudioManager#isMusicActive()} 给出可执行的提示。
 */
public final class NextTrackController {

    private NextTrackController() {
    }

    /** 下发结果，交给 UI 决定提示文案 */
    public static final class Result {
        public final boolean dispatched;
        public final boolean audioActive;

        Result(boolean dispatched, boolean audioActive) {
            this.dispatched = dispatched;
            this.audioActive = audioActive;
        }

        /** 系统里确实有音频在播，命令很可能已生效 */
        public boolean looksEffective() {
            return dispatched && audioActive;
        }
    }

    /**
     * 发送一次「下一曲」。
     *
     * <p>按下 + 抬起必须成对发送，只发 ACTION_DOWN 有些播放器不认。
     */
    public static Result sendNext(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return new Result(false, false);
        }
        boolean active = false;
        try {
            active = am.isMusicActive();
        } catch (Throwable ignored) {
            // 某些定制 ROM 上可能抛异常，不影响主流程
        }
        try {
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now,
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now,
                    KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT, 0));
            return new Result(true, active);
        } catch (Throwable t) {
            return new Result(false, active);
        }
    }

    /** 当前是否有音频在播，用于首页状态展示 */
    public static boolean isAudioActive(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return false;
        }
        try {
            return am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }
}
