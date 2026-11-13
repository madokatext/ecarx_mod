package io.github.madokatext.ecarxmod;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.text.TextUtils;

import java.util.List;

/** Polling also covers players that forget to send metadata callbacks. Called on a worker only. */
final class MediaSessionSource {
    private final Context context;
    private final MediaSessionManager manager;
    private MediaBridgeState.Snapshot previous;
    private String lastSummary;

    MediaSessionSource(Context context) {
        this.context = context;
        manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    MediaBridgeState.Snapshot poll(ComponentName listener) {
        if (manager == null) throw new IllegalStateException("MediaSessionManager unavailable");
        List<MediaController> sessions = manager.getActiveSessions(listener);
        MediaController selected = null;
        MediaController paused = null;
        for (MediaController controller : sessions) {
            String pkg = controller.getPackageName();
            if (MediaBridgeState.MODULE.equals(pkg) || pkg.startsWith("com.hyphp.hywebview")) continue;
            PlaybackState state = controller.getPlaybackState();
            if (state == null) continue;
            boolean stock = pkg.startsWith("com.ecarx.") || pkg.startsWith("ecarx.");
            if (state.getState() == PlaybackState.STATE_PLAYING) {
                // Android orders these by priority. A foreground stock source wins, too.
                if (stock) { selected = null; paused = null; break; }
                if (hasMetadata(controller)) { selected = controller; break; }
            }
            if (!stock && previous != null && controller.getSessionToken().equals(previous.token)
                    && (state.getState() == PlaybackState.STATE_PAUSED
                    || state.getState() == PlaybackState.STATE_BUFFERING
                    || state.getState() == PlaybackState.STATE_CONNECTING)) paused = controller;
        }
        if (selected == null) selected = paused;
        MediaBridgeState.Snapshot result = selected == null ? null : snapshot(selected);
        String summary = result == null ? "none; sessions=" + sessions.size()
                : result.packageName + " state=" + result.state + " sessions=" + sessions.size();
        if (!summary.equals(lastSummary)) {
            MediaBridgeState.info("[reader] selected " + summary);
            lastSummary = summary;
        }
        previous = result;
        return result;
    }

    private boolean hasMetadata(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        return metadata != null && (!TextUtils.isEmpty(title(metadata))
                || !TextUtils.isEmpty(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)));
    }

    private MediaBridgeState.Snapshot snapshot(MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState playback = controller.getPlaybackState();
        if (metadata == null || playback == null) return null;
        String pkg = controller.getPackageName();
        String app = pkg;
        try {
            app = context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) { }
        String artwork = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (TextUtils.isEmpty(artwork)) artwork = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
        if (TextUtils.isEmpty(artwork)) artwork = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        return new MediaBridgeState.Snapshot(pkg, app, title(metadata),
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), artwork,
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION), playback, controller.getSessionToken());
    }

    private static String title(MediaMetadata metadata) {
        String value = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        return TextUtils.isEmpty(value) ? metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) : value;
    }
}
