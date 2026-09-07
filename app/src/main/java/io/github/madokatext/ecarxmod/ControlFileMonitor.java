package io.github.madokatext.ecarxmod;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;

/** Owns disk IO: consumes control values and separately publishes vehicle state. */
final class ControlFileMonitor {
    interface Listener {
        void onEdit(VehicleControl control, Snapshot snapshot, Integer value);
    }

    private static final String TAG = "EcarxSocFix";
    private static final int MAX_BYTES = 128;
    private static final long POLL_MS = 500;
    private final Path directory = Paths.get("/sdcard/ecarx_mod");
    private final HandlerThread thread = new HandlerThread("EcarxControlFiles");
    private final EnumMap<VehicleControl, Entry> entries = new EnumMap<>(VehicleControl.class);
    private final AtomicLongArray revisions = new AtomicLongArray(VehicleControl.values().length);
    private final Listener listener;
    private Handler handler;
    private volatile boolean stopped;
    private long lastError;

    ControlFileMonitor(Listener listener, VehicleControl... controls) {
        this.listener = listener;
        for (VehicleControl control : controls) entries.put(control, new Entry());
    }

    void start() {
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::poll);
    }

    private void poll() {
        if (stopped) return;
        try {
            Files.createDirectories(directory);
            for (Map.Entry<VehicleControl, Entry> item : entries.entrySet()) {
                VehicleControl control = item.getKey();
                Entry entry = item.getValue();
                try {
                    Path path = controlPath(control);
                    if (!entry.initialized) {
                        // CREATE_NEW is atomic; never truncate a pre-existing user file.
                        try {
                            Files.createFile(path);
                        } catch (java.nio.file.FileAlreadyExistsException ignored) {
                            // Existing content becomes a baseline, never a startup command.
                        }
                        entry.snapshot = read(path);
                        entry.initialized = true;
                        entry.failed = false;
                        continue;
                    }
                    Snapshot snapshot = read(path);
                    entry.failed = false;
                    if (!snapshot.equals(entry.snapshot)) {
                        entry.snapshot = snapshot;
                        long revision = revisions.incrementAndGet(control.ordinal());
                        listener.onEdit(control, snapshot, null); // Cancel stale retries now.
                        Integer value = decode(snapshot.bytes);
                        if (value != null) {
                            // Clear immediately after accepting one valid value. Keep the empty
                            // snapshot as our own baseline so this write cannot trigger a command.
                            Snapshot empty = consume(path, snapshot);
                            empty.revision = revision;
                            entry.snapshot = empty;
                            listener.onEdit(control, empty, value);
                        }
                    }
                } catch (IOException | RuntimeException error) {
                    if (!entry.failed) {
                        revisions.incrementAndGet(control.ordinal());
                        listener.onEdit(control, null, null);
                    }
                    entry.failed = true;
                    report(error);
                }
            }
        } catch (IOException | RuntimeException error) {
            for (VehicleControl control : entries.keySet()) {
                Entry entry = entries.get(control);
                if (!entry.failed) {
                    revisions.incrementAndGet(control.ordinal());
                    listener.onEdit(control, null, null);
                }
                entry.failed = true;
            }
            report(error);
        }
        if (!stopped) handler.postDelayed(this::poll, POLL_MS);
    }

    boolean isCurrent(VehicleControl control, Snapshot expected) {
        if (stopped || expected == null
                || expected.revision != revisions.get(control.ordinal())) return false;
        try {
            return expected.equals(read(controlPath(control)))
                    && expected.revision == revisions.get(control.ordinal());
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    void publishStates(Map<VehicleControl, String> values) {
        if (handler == null || stopped) return;
        handler.post(() -> {
            if (stopped) return;
            try {
                Files.createDirectories(directory);
                for (Map.Entry<VehicleControl, String> item : values.entrySet()) {
                    if (!entries.containsKey(item.getKey())) continue;
                    Path destination = directory.resolve(item.getKey().name + "_state.txt");
                    byte[] bytes = item.getValue().getBytes(StandardCharsets.US_ASCII);
                    // Repair removed/externally modified state files without changing controls.
                    Snapshot current = read(destination);
                    if (current.exists && Arrays.equals(current.bytes, bytes)) continue;
                    Path temporary = directory.resolve("." + item.getKey().name + "_state.tmp");
                    // Remove an abandoned temp entry, including a possible symlink, first.
                    Files.deleteIfExists(temporary);
                    Files.write(temporary, bytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE);
                    try {
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (IOException | RuntimeException error) {
                report(error);
            }
        });
    }

    private Path controlPath(VehicleControl control) {
        return directory.resolve(control.name + ".txt");
    }

    private static Snapshot consume(Path path, Snapshot expected) throws IOException {
        // Open without TRUNCATE_EXISTING, then check again before consuming. If an editor
        // replaced/changed the file meanwhile, leave that new command for the next poll.
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(path,
                java.nio.file.StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            if (!expected.equals(read(path))) throw new IOException("Control changed before consume");
            channel.truncate(0);
        }
        Snapshot empty = read(path);
        if (!empty.exists || empty.bytes == null || empty.bytes.length != 0) {
            throw new IOException("Control changed while being consumed");
        }
        return empty;
    }

    private static Snapshot read(Path path) throws IOException {
        BasicFileAttributes before;
        try {
            before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ignored) {
            return new Snapshot(false, null, null);
        }
        byte[] bytes = null;
        if (before.isRegularFile() && before.size() <= MAX_BYTES) {
            // Bounded even if another process enlarges the file during the read.
            try (java.io.InputStream stream = Files.newInputStream(path)) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                for (int i = 0; i <= MAX_BYTES; i++) {
                    int next = stream.read();
                    if (next < 0) break;
                    output.write(next);
                }
                if (output.size() <= MAX_BYTES) bytes = output.toByteArray();
            }
        }
        BasicFileAttributes after = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!sameAttributes(before, after)) throw new IOException("File changed during read");
        return new Snapshot(true, after, bytes);
    }

    private static boolean sameAttributes(BasicFileAttributes a, BasicFileAttributes b) {
        return a.size() == b.size() && a.isRegularFile() == b.isRegularFile()
                && a.lastModifiedTime().equals(b.lastModifiedTime())
                && Objects.equals(a.fileKey(), b.fileKey());
    }

    private static Integer decode(byte[] bytes) {
        if (bytes == null) return null;
        String value;
        try {
            value = decode(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException utf8Error) {
            try {
                value = decode(bytes, Charset.forName("GBK"));
            } catch (CharacterCodingException | RuntimeException gbkError) {
                return null;
            }
        }
        if (value.startsWith("\ufeff")) value = value.substring(1);
        value = value.trim();
        if ("0".equals(value)) return 0;
        if ("1".equals(value)) return 1;
        return null;
    }

    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private void report(Throwable error) {
        long now = SystemClock.elapsedRealtime();
        if (lastError == 0 || now - lastError >= 30000) {
            lastError = now;
            Log.w(TAG, "File access unavailable; will try again", error);
        }
    }

    void stop() {
        stopped = true;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    static final class Snapshot {
        final boolean exists;
        final BasicFileAttributes attributes;
        final byte[] bytes;
        long revision;

        Snapshot(boolean exists, BasicFileAttributes attributes, byte[] bytes) {
            this.exists = exists;
            this.attributes = attributes;
            this.bytes = bytes;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Snapshot)) return false;
            Snapshot snapshot = (Snapshot) other;
            return exists == snapshot.exists && (!exists || sameAttributes(attributes, snapshot.attributes))
                    && Arrays.equals(bytes, snapshot.bytes);
        }

        @Override public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private static final class Entry {
        boolean initialized;
        boolean failed;
        Snapshot snapshot;
    }
}
