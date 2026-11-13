package io.github.madokatext.ecarxmod;

import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/** Small IPC snapshots. Never persist session tokens or put artwork bitmaps in Binder bundles. */
final class MediaBridgeState {
    static final String TAG = "EcarxMediaBridge";
    static final Uri URI = Uri.parse("content://io.github.madokatext.ecarxmod.media");
    static final String READ = "read_media_bridge";
    static final String REPORT = "report_media_bridge";
    static final String STATUS = "read_media_status";
    static final String MODULE = "io.github.madokatext.ecarxmod";
    static final String HOST = "ecarx.settings";
    static volatile boolean debug;

    private static Snapshot fallback;
    private static boolean listenerConnected;
    private static long listenerUpdated;
    private static String readerStatus = "通知监听未连接（系统宿主有权限时无需此备用入口）";
    private static String senderStatus = "等待原车设置进程启动";
    private static long senderUpdated;

    private MediaBridgeState() { }

    static synchronized void reader(boolean connected, Snapshot snapshot, String status) {
        listenerConnected = connected;
        listenerUpdated = SystemClock.elapsedRealtime();
        fallback = snapshot;
        readerStatus = status;
    }

    static synchronized Bundle readFallback() {
        Bundle out = fallback == null ? new Bundle() : fallback.toBundle();
        out.putBoolean("listener_connected", listenerConnected);
        out.putLong("listener_updated", listenerUpdated);
        return out;
    }

    static synchronized void report(String status) {
        senderStatus = safe(status, 2000);
        senderUpdated = SystemClock.elapsedRealtime();
    }

    static synchronized Bundle status() {
        Bundle out = new Bundle();
        out.putString("reader_status", readerStatus);
        out.putString("sender_status", senderStatus);
        out.putLong("sender_updated", senderUpdated);
        return out;
    }

    static void info(String message) { Log.i(TAG, message); }
    static void trace(String message) { if (debug) Log.d(TAG, message); }
    static void error(String message, Throwable error) {
        Log.e(TAG, message, error);
    }

    static String safe(String text, int limit) {
        if (text == null) return "";
        String value = text.replace('\n', ' ').replace('\r', ' ');
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    static final class Snapshot {
        final String packageName;
        final String appName;
        final String title;
        final String artist;
        final String album;
        final String mediaId;
        final String artwork;
        final long duration;
        final long position;
        final long positionAt;
        final float speed;
        final int state;
        final long actions;
        final MediaSession.Token token;

        Snapshot(String packageName, String appName, String title, String artist, String album,
                 String mediaId, String artwork, long duration, PlaybackState playback,
                 MediaSession.Token token) {
            this.packageName = safe(packageName, 256);
            this.appName = safe(appName, 256);
            this.title = safe(title, 4096);
            this.artist = safe(artist, 4096);
            this.album = safe(album, 4096);
            this.mediaId = safe(mediaId, 4096);
            this.artwork = safe(artwork, 8192);
            this.duration = Math.max(0, duration);
            this.position = Math.max(0, playback.getPosition());
            this.positionAt = playback.getLastPositionUpdateTime();
            this.speed = playback.getPlaybackSpeed();
            this.state = playback.getState();
            this.actions = playback.getActions();
            this.token = token;
        }

        private Snapshot(Bundle b) {
            packageName = safe(b.getString("package"), 256);
            appName = safe(b.getString("app"), 256);
            title = safe(b.getString("title"), 4096);
            artist = safe(b.getString("artist"), 4096);
            album = safe(b.getString("album"), 4096);
            mediaId = safe(b.getString("media_id"), 4096);
            artwork = safe(b.getString("artwork"), 8192);
            duration = Math.max(0, b.getLong("duration"));
            position = Math.max(0, b.getLong("position"));
            positionAt = b.getLong("position_at");
            speed = b.getFloat("speed");
            state = b.getInt("state");
            actions = b.getLong("actions");
            token = b.getParcelable("session_token");
        }

        static Snapshot fromBundle(Bundle b) {
            b.setClassLoader(MediaSession.Token.class.getClassLoader());
            if (!b.containsKey("session_token")) return null;
            Snapshot value = new Snapshot(b);
            return value.token == null || value.packageName.isEmpty() ? null : value;
        }

        Bundle toBundle() {
            Bundle b = new Bundle();
            b.putString("package", packageName);
            b.putString("app", appName);
            b.putString("title", title);
            b.putString("artist", artist);
            b.putString("album", album);
            b.putString("media_id", mediaId);
            b.putString("artwork", artwork);
            b.putLong("duration", duration);
            b.putLong("position", position);
            b.putLong("position_at", positionAt);
            b.putFloat("speed", speed);
            b.putInt("state", state);
            b.putLong("actions", actions);
            b.putParcelable("session_token", token);
            return b;
        }

        boolean playing() { return state == PlaybackState.STATE_PLAYING; }

        boolean sameSession(Snapshot other) {
            return other != null && packageName.equals(other.packageName) && token.equals(other.token);
        }

        boolean samePayload(Snapshot other) {
            return sameSession(other) && title.equals(other.title) && artist.equals(other.artist)
                    && album.equals(other.album) && mediaId.equals(other.mediaId)
                    && artwork.equals(other.artwork) && duration == other.duration && state == other.state;
        }

        long progress() {
            long result = position;
            if (playing() && positionAt > 0 && speed > 0 && Float.isFinite(speed)) {
                result += (long) (Math.max(0, SystemClock.elapsedRealtime() - positionAt) * (double) speed);
            }
            return duration > 0 ? Math.min(duration, Math.max(0, result)) : Math.max(0, result);
        }

        String describe() {
            return "package=" + packageName + " state=" + state + " title=" + safe(title, 160)
                    + " artist=" + safe(artist, 120) + " duration=" + duration
                    + " artworkUri=" + !artwork.isEmpty();
        }
    }
}
