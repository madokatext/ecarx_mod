package io.github.madokatext.ecarxmod;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.NotificationListenerService;

/** Optional standard-Android reader when the injected settings UID cannot read sessions itself. */
public final class MediaNotificationListener extends NotificationListenerService {
    private volatile HandlerThread thread;
    private volatile Handler worker;
    private MediaSessionSource source;
    private long lastError;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            Handler currentWorker = worker;
            MediaSessionSource currentSource = source;
            if (Thread.currentThread() != thread || currentWorker == null) return;
            try {
                Bundle settings = BootSettings.readLocal(MediaNotificationListener.this);
                MediaBridgeState.debug = settings.getBoolean(BootSettings.MEDIA_DEBUG, true);
                if (settings.getBoolean(BootSettings.MEDIA_FORWARD, false)) {
                    MediaBridgeState.Snapshot current = currentSource.poll(
                            new ComponentName(MediaNotificationListener.this, MediaNotificationListener.class));
                    if (worker == currentWorker) MediaBridgeState.reader(true, current, current == null
                            ? "通知监听已连接，等待第三方播放器开始播放" : "通知监听：" + current.packageName);
                } else if (worker == currentWorker) MediaBridgeState.reader(true, null, "通知监听已连接，转发开关关闭");
            } catch (Throwable error) {
                if (worker == currentWorker) MediaBridgeState.reader(true, null, "通知监听读取失败：" + error.getClass().getSimpleName());
                long now = android.os.SystemClock.elapsedRealtime();
                if (lastError == 0 || now - lastError >= 30000) {
                    lastError = now;
                    MediaBridgeState.error("[reader] notification listener read failed", error);
                }
            } finally {
                if (worker == currentWorker) currentWorker.postDelayed(this, 1000);
            }
        }
    };

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        stopWorker();
        thread = new HandlerThread("EcarxMediaReader");
        thread.start();
        worker = new Handler(thread.getLooper());
        source = new MediaSessionSource(this);
        MediaBridgeState.info("[reader] notification listener connected");
        worker.post(poll);
    }

    @Override public void onListenerDisconnected() {
        stopWorker();
        MediaBridgeState.reader(false, null, "通知监听已断开，请检查通知使用权");
        MediaBridgeState.info("[reader] notification listener disconnected");
        super.onListenerDisconnected();
    }

    @Override public void onDestroy() {
        stopWorker();
        MediaBridgeState.reader(false, null, "通知监听服务已停止");
        super.onDestroy();
    }

    private void stopWorker() {
        if (worker != null) worker.removeCallbacksAndMessages(null);
        worker = null;
        if (thread != null) thread.quitSafely();
        thread = null;
    }
}
