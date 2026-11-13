package io.github.madokatext.ecarxmod;

import android.content.Context;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

/** Serializes selection, registration, focus claims, publication and cleanup outside the host UI. */
final class MediaBridgeController {
    private final Context context;
    private final HandlerThread thread = new HandlerThread("EcarxMediaBridge");
    private final MediaInfoFile mediaFile = new MediaInfoFile();
    private final MediaVehicleState vehicle;
    private Handler worker;
    private MediaSessionSource source;
    private MediaCenterTransport transport;
    private MediaBridgeState.Snapshot current;
    private MediaBridgeState.Snapshot observed;
    private MediaBridgeState.Snapshot published;
    private String publishedVehicleStatus;
    private boolean enabled;
    private boolean claimed;
    private boolean cleaning;
    private boolean clearOnRelease;
    private boolean explicitPlay;
    private int cleanupAttempts;
    private long nextCleanup;
    private long nextConnect;
    private long connectDeadline;
    private long nextDirectRead;
    private long lastHeartbeat;
    private long lastProgressLog;
    private long lastReport;
    private String status = "媒体转发关闭";
    private String reported;
    private String lastError;
    private long lastErrorAt;
    private String reader = "system";
    private String readerIssue;
    private String observedMode;

    MediaBridgeController(Context context) {
        this.context = context.getApplicationContext() == null ? context : context.getApplicationContext();
        vehicle = new MediaVehicleState(this.context);
    }

    void start() {
        thread.start();
        worker = new Handler(thread.getLooper());
        source = new MediaSessionSource(context);
        MediaBridgeState.info("[sender] started host=" + context.getPackageName()
                + " uid=" + android.os.Process.myUid());
        worker.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try {
                Bundle config = context.getContentResolver().call(MediaBridgeState.URI, MediaBridgeState.READ, null, null);
                if (config == null) throw new IllegalStateException("Module media provider returned null");
                MediaBridgeState.debug = config.getBoolean(BootSettings.MEDIA_DEBUG, true);
                boolean nextEnabled = config.getBoolean(BootSettings.MEDIA_FORWARD, false);
                if (enabled != nextEnabled) {
                    enabled = nextEnabled;
                    nextDirectRead = 0;
                    MediaBridgeState.info("[sender] forwarding enabled=" + enabled);
                }
                current = enabled ? readSource(config) : null;
                // Write on input changes even if the MediaCenter service is unavailable or declines focus.
                mediaFile.publish(current);
                boolean playEdge = current != null && current.playing()
                        && (!current.sameSession(observed) || !observed.playing());
                boolean idleEdge = enabled && current == null && observed != null;
                String mode = !enabled ? "off" : current == null ? "status-only" : "media";
                if (!mode.equals(observedMode)) {
                    observedMode = mode;
                    MediaBridgeState.info("[sender] output mode=" + mode);
                }
                if (current != null && !current.samePayload(observed)) {
                    MediaBridgeState.info("[sender] input " + current.describe());
                }
                observed = current;
                drive(playEdge, idleEdge);
            } catch (Throwable error) {
                failure("read-or-publish", error);
                beginCleanup(status + "；正在释放注册");
            } finally {
                if (cleaning) cleanup();
                report();
                worker.postDelayed(this, 1000);
            }
        }
    };

    private MediaBridgeState.Snapshot readSource(Bundle fallback) {
        long now = SystemClock.elapsedRealtime();
        if (now >= nextDirectRead) {
            try {
                MediaBridgeState.Snapshot selected = source.poll(null);
                reader = "system";
                readerIssue = null;
                return selected;
            } catch (SecurityException denied) {
                nextDirectRead = now + 30000;
                failure("MEDIA_CONTENT_CONTROL; trying notification listener", denied);
            } catch (Throwable error) {
                nextDirectRead = now + 5000;
                failure("media reader; continuing vehicle status", error);
            }
        }
        reader = "notification";
        if (fallback.getBoolean("listener_connected")
                && now - fallback.getLong("listener_updated") <= 5000) {
            try {
                MediaBridgeState.Snapshot selected = MediaBridgeState.Snapshot.fromBundle(fallback);
                readerIssue = null;
                return selected;
            } catch (Throwable error) { failure("notification snapshot; continuing vehicle status", error); }
        }
        readerIssue = "曲目读取暂不可用，继续发送车辆状态；请检查通知使用权和日志";
        return null;
    }

    private void drive(boolean playEdge, boolean idleEdge) throws Exception {
        if (!enabled) {
            beginCleanup("媒体转发关闭");
            return;
        }
        if (cleaning) return;
        String vehicleStatus = vehicle.poll();
        long now = SystemClock.elapsedRealtime();
        if (transport == null) {
            if (now < nextConnect) return;
            connect();
        }
        if (!transport.connected()) {
            status = "正在连接车机媒体中心";
            if (now >= connectDeadline) {
                throw new IllegalStateException("MediaCenter connection timeout after 5 seconds");
            }
            return;
        }
        transport.snapshot(current, vehicleStatus);
        boolean registeredNow = !transport.registered();
        if (registeredNow) transport.register();
        boolean statusOnly = current == null;
        boolean requestOwnership = statusOnly ? registeredNow || idleEdge || explicitPlay
                : current.playing() && (playEdge || registeredNow || explicitPlay);
        if (!claimed && requestOwnership) {
            MediaBridgeState.info("[sender] requesting ownership mode=" + (statusOnly ? "status-only" : "media"));
            claimed = transport.requestPlay();
            invalidatePublished();
        }
        explicitPlay = false;
        if (!claimed) {
            status = "媒体中心未授予显示归属或已让出：" + (statusOnly ? "车辆状态卡片" : current.appName)
                    + "；等待新曲目播放、进入无曲目模式或重新开启转发";
            return;
        }
        boolean changed = publishedVehicleStatus == null
                || (statusOnly ? published != null : !current.samePayload(published))
                || !vehicleStatus.equals(publishedVehicleStatus);
        boolean accepted = transport.publish(changed);
        if (accepted) {
            published = current;
            publishedVehicleStatus = vehicleStatus;
        } else throw new IllegalStateException("updateMusicPlaybackState returned false");
        if (changed) MediaBridgeState.info("[sender] published mode=" + (statusOnly ? "status-only" : "media")
                + (statusOnly ? " state=PAUSE progress=0" : " " + current.describe())
                + " displayTitle=\"" + MediaBridgeState.safe(MediaCenterTransport.displayTitle(current), 300)
                + "\" displayArtist=\"" + vehicleStatus + "\" sourceType=6");
        if (MediaBridgeState.debug && now - lastProgressLog >= 15000) {
            lastProgressLog = now;
            MediaBridgeState.trace("[sender] progress=" + (statusOnly ? 0 : current.progress()) + " reader=" + reader);
        }
        status = "已提交媒体中心（" + reader + "）："
                + (statusOnly ? "车辆状态 / 暂无播放" : current.appName + " / "
                + MediaBridgeState.safe(current.title, 100) + " / " + (current.playing() ? "播放" : "暂停"))
                + " / " + vehicleStatus
                + "；仪表实际显示请在车上确认" + (readerIssue == null ? "" : "；" + readerIssue);
    }

    private void invalidatePublished() {
        published = null;
        publishedVehicleStatus = null;
    }

    private void connect() throws Exception {
        MediaCenterTransport next = new MediaCenterTransport(context, worker, new MediaCenterTransport.Events() {
            @Override public void connected() { MediaBridgeState.trace("[sender] connection ready; next poll registers"); }
            @Override public void disconnected() {
                if (transport == null) return;
                transport.close();
                transport = null;
                claimed = false;
                cleaning = false;
                invalidatePublished();
                nextConnect = SystemClock.elapsedRealtime() + 5000;
                status = "媒体中心连接已断开，等待重连";
            }
            @Override public void command(String name, MediaBridgeState.Snapshot target) { control(name, target); }
            @Override public void focusLost() {
                claimed = false;
                invalidatePublished();
                MediaBridgeState.info("[sender] MediaCenter focus lost; no automatic reclaim while playback stays unchanged");
            }
        });
        transport = next;
        connectDeadline = SystemClock.elapsedRealtime() + 5000;
        try { next.connect(); }
        catch (Exception error) {
            next.close();
            transport = null;
            nextConnect = SystemClock.elapsedRealtime() + 15000;
            throw error;
        }
    }

    private void beginCleanup(String reason) {
        status = reason;
        if (cleaning) return;
        if (transport == null) return;
        transport.clearSnapshot();
        if (transport.registrationUncertain()) {
            cleaning = true;
            claimed = false;
            cleanupAttempts = 3;
            nextCleanup = Long.MAX_VALUE;
            MediaBridgeState.info("[sender] registration result uncertain; blocking duplicate registrations until host restart");
            return;
        }
        if (!transport.registered()) {
            transport.close();
            transport = null;
            nextConnect = SystemClock.elapsedRealtime() + 5000;
            return;
        }
        cleaning = true;
        clearOnRelease = claimed;
        claimed = false;
        cleanupAttempts = 0;
        nextCleanup = 0;
        MediaBridgeState.info("[sender] releasing: " + reason);
    }

    private void cleanup() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextCleanup) return;
        cleanupAttempts++;
        try {
            if (transport == null || transport.release(clearOnRelease)) {
                if (transport != null) transport.close();
                transport = null;
                cleaning = false;
                clearOnRelease = false;
                invalidatePublished();
                nextConnect = now + 5000;
                MediaBridgeState.info("[sender] release confirmed");
                return;
            }
        } catch (Throwable error) { failure("unregister attempt=" + cleanupAttempts, error); }
        if (cleanupAttempts >= 3) {
            nextCleanup = Long.MAX_VALUE;
            status = "媒体中心注销未确认，已停止重建客户端；请重启车机后检查日志";
            MediaBridgeState.info("[sender] cleanup unconfirmed after 3 attempts; retaining token and blocking new registrations");
        } else nextCleanup = now + 5000;
    }

    private void control(String command, MediaBridgeState.Snapshot target) {
        if (enabled && !cleaning && current == null && target == null && command.equals("onSourceSelected")) {
            explicitPlay = true;
            MediaBridgeState.info("[callback] selected vehicle status source; no player command");
            return;
        }
        if (!enabled || cleaning || current == null || !current.sameSession(target)) {
            MediaBridgeState.info("[callback] ignored stale " + command);
            return;
        }
        if (!claimed && !command.equals("onPlay") && !command.equals("onSourceSelected")) {
            MediaBridgeState.info("[callback] ignored " + command + " without media ownership");
            return;
        }
        try {
            MediaController controller = new MediaController(context, target.token);
            MediaController.TransportControls controls = controller.getTransportControls();
            switch (command) {
                case "onPlay":
                case "onSourceSelected": controls.play(); explicitPlay = true; break;
                case "onPause": controls.pause(); break;
                case "onNext": controls.skipToNext(); break;
                case "onPrevious": controls.skipToPrevious(); break;
                default: return;
            }
            MediaBridgeState.info("[callback] dispatched " + command + " package=" + target.packageName
                    + " declaredActions=" + target.actions);
        } catch (Throwable error) { failure("control " + command, error); }
    }

    private void failure(String stage, Throwable error) {
        long now = SystemClock.elapsedRealtime();
        String key = stage + ": " + error.getClass().getSimpleName() + ": "
                + MediaBridgeState.safe(error.getMessage(), 300);
        status = key;
        if (!key.equals(lastError) || now - lastErrorAt >= 30000) {
            lastError = key;
            lastErrorAt = now;
            MediaBridgeState.error("[sender] " + stage, error);
        }
    }

    private void report() {
        long now = SystemClock.elapsedRealtime();
        if (cleaning && cleanupAttempts >= 3) status = "媒体中心注销未确认，已停止重建客户端；请重启车机后检查日志";
        if (!status.equals(reported) || now - lastReport >= 15000) {
            try {
                Bundle report = new Bundle();
                report.putString("status", status);
                context.getContentResolver().call(MediaBridgeState.URI, MediaBridgeState.REPORT, null, report);
                reported = status;
                lastReport = now;
            } catch (Throwable error) { failure("status IPC", error); }
        }
        if (now - lastHeartbeat >= 30000) {
            lastHeartbeat = now;
            MediaBridgeState.info("[sender] heartbeat enabled=" + enabled + " reader=" + reader
                    + " mode=" + (!enabled ? "off" : current == null ? "status-only" : "media")
                    + " connected=" + (transport != null && transport.connected())
                    + " registered=" + (transport != null && transport.registered())
                    + " claimed=" + claimed + " cleaning=" + cleaning + " status=" + status);
        }
    }
}
