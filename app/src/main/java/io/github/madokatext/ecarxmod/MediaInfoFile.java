package io.github.madokatext.ecarxmod;

import android.os.SystemClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Publishes the latest title/artist pair on the media worker, independently of instrument IPC. */
final class MediaInfoFile {
    private final Path directory = Paths.get("/sdcard/ecarx_mod");
    private final Path destination = directory.resolve("media.txt");
    private final Path temporary = directory.resolve(".media.tmp");
    private String written;
    private long retryAfter;
    private long lastError;
    private boolean failed;

    void publish(MediaBridgeState.Snapshot snapshot) {
        if (snapshot == null) return;
        String content = oneLine(snapshot.title) + "\n" + oneLine(snapshot.artist) + "\n";
        if (content.equals(written) || SystemClock.elapsedRealtime() < retryAfter) return;
        try {
            Files.createDirectories(directory);
            // Match the existing state-file publication scheme; never follow an old temp symlink.
            Files.deleteIfExists(temporary);
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            written = content;
            retryAfter = 0;
            failed = false;
            MediaBridgeState.info("[file] media.txt updated title=" + MediaBridgeState.safe(snapshot.title, 160)
                    + " artist=" + MediaBridgeState.safe(snapshot.artist, 120));
        } catch (IOException | RuntimeException error) {
            long now = SystemClock.elapsedRealtime();
            retryAfter = now + 5000;
            if (!failed || now - lastError >= 30000) {
                lastError = now;
                failed = true;
                MediaBridgeState.error("[file] cannot write " + destination + "; retry in 5 seconds", error);
            }
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException ignored) { }
        }
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ')
                .replace('\u0085', ' ').replace('\u2028', ' ').replace('\u2029', ' ');
    }
}
