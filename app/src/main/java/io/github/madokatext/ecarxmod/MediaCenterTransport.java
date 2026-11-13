package io.github.madokatext.ecarxmod;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Reflected ECARX MediaCenter contract: no vendor bytecode is bundled with the module. */
final class MediaCenterTransport {
    private static final String SDK = "com.ecarx.sdk.mediacenter.";
    private static final String PERMISSION = "ecarx.oem.permission.OPENAPI_MEDIACENTER_PERMISSION";
    private static final ComponentName COMPONENT = new ComponentName("ecarx.xsf.mediacenter",
            "ecarx.xsf.mediacenter.MediaCenterService");
    private static final int SOURCE = 6;

    interface Events {
        void connected();
        void disconnected();
        void command(String name, MediaBridgeState.Snapshot target);
        void focusLost();
    }

    private final Context context;
    private final Handler worker;
    private final Events events;
    private final ServiceConnection connection;
    private volatile Output output;
    private volatile boolean closed;
    private volatile boolean connected;
    private boolean bound;
    private Object service;
    private Object token;
    private boolean registrationUncertain;
    private Object playback;
    private Object media;
    private Object wirePlayback;
    private Class<?> clientType;
    private Class<?> playbackType;
    private Class<?> mediaType;
    private Constructor<?> clientConstructor;
    private Constructor<?> playbackConstructor;
    private Method asInterface;
    private Method register;
    private Method unregister;
    private Method request;
    private Method sourceList;
    private Method source;
    private Method playlist;
    private Method update;
    private Method progress;
    private Method listWrapper;

    MediaCenterTransport(Context context, Handler worker, Events events) {
        this.context = context;
        this.worker = worker;
        this.events = events;
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                worker.post(() -> {
                    if (closed) return;
                    try {
                        service = invoke(asInterface, null, binder);
                        connected = service != null;
                        if (!connected) throw new IllegalStateException("asInterface returned null");
                        MediaBridgeState.info("[transport] service connected " + name.flattenToShortString());
                        events.connected();
                    } catch (Throwable error) {
                        MediaBridgeState.error("[transport] service interface failed", error);
                        connected = false;
                        events.disconnected();
                    }
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) { lost("service disconnected"); }
            @Override public void onBindingDied(ComponentName name) { lost("binding died"); }
            @Override public void onNullBinding(ComponentName name) { lost("null binding"); }
        };
    }

    void connect() throws Exception {
        int permission = context.checkSelfPermission(PERMISSION);
        MediaBridgeState.info("[transport] probe uid=" + android.os.Process.myUid()
                + " mediaPermission=" + (permission == PackageManager.PERMISSION_GRANTED)
                + " component=" + COMPONENT.flattenToShortString());
        if (permission != PackageManager.PERMISSION_GRANTED) throw new SecurityException(PERMISSION + " denied");
        resolve();
        Intent intent = new Intent("ecarx.xsf.MEDIA_CENTER_SERVICE").setComponent(COMPONENT);
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        MediaBridgeState.info("[transport] bind requested accepted=" + bound);
        if (!bound) throw new IllegalStateException("MediaCenter bind rejected");
    }

    private void lost(String reason) {
        connected = false;
        worker.post(() -> {
            if (closed) return;
            MediaBridgeState.info("[transport] " + reason);
            // Android delivers these for an unavailable service. Old callbacks are invalidated on close.
            events.disconnected();
        });
    }

    boolean connected() { return connected && !closed; }
    boolean registered() { return token != null; }
    boolean registrationUncertain() { return registrationUncertain; }
    void snapshot(MediaBridgeState.Snapshot value, String vehicleStatus) {
        // A missing music session is a live vehicle-status card, not a cleared registration.
        output = new Output(value, vehicleStatus);
    }

    void clearSnapshot() { output = null; }

    static String displayTitle(MediaBridgeState.Snapshot value) {
        if (value == null) return "暂无播放";
        String artist = value.artist.trim();
        String title = value.title.trim();
        switch (artist.toLowerCase(Locale.ROOT).replace('_', ' ')) {
            case "":
            case "未知":
            case "未知歌手":
            case "未知艺术家":
            case "未知艺人":
            case "unknown":
            case "<unknown>":
            case "[unknown]":
            case "unknown artist":
            case "unknown singer": artist = ""; break;
            default: break;
        }
        if (artist.isEmpty()) return title.isEmpty() ? "暂无播放" : title;
        if (title.isEmpty()) return artist;
        return artist + " - " + title;
    }

    /** Atomically expose matching source metadata and display fields to Binder callbacks. */
    private static final class Output {
        final MediaBridgeState.Snapshot input;
        final String title;
        final String artist;

        Output(MediaBridgeState.Snapshot input, String vehicleStatus) {
            this.input = input;
            title = displayTitle(input);
            artist = vehicleStatus;
        }
    }

    void register() throws Exception {
        if (!connected() || token != null) throw new IllegalStateException("Invalid register state");
        Object client = proxy(clientType, true);
        playback = proxy(playbackType, false);
        media = proxy(mediaType, false);
        wirePlayback = playbackConstructor.newInstance(playback);
        Object wrappedClient = clientConstructor.newInstance(client);
        // If Binder fails after the remote side creates a token, do not register a second client.
        registrationUncertain = true;
        token = invoke(register, service, wrappedClient);
        if (token == null) throw new IllegalStateException("registerMusic returned no token");
        registrationUncertain = false;
        MediaBridgeState.info("[transport] registerMusic tokenPresent=true");
        invoke(sourceList, service, token, new int[]{SOURCE});
    }

    boolean requestPlay() throws Exception {
        boolean accepted = Boolean.TRUE.equals(invoke(request, service, token));
        if (accepted) invoke(source, service, token, SOURCE);
        MediaBridgeState.info("[transport] requestPlay accepted=" + accepted);
        return accepted;
    }

    boolean publish(boolean metadataChanged) throws Exception {
        if (!connected() || token == null) throw new IllegalStateException("No live media registration");
        Output current = output;
        boolean accepted = true;
        if (metadataChanged) {
            List<?> entries = current == null ? Collections.emptyList() : Collections.singletonList(media);
            Object wrapped = invoke(listWrapper, null, entries);
            invoke(playlist, service, token, SOURCE, wrapped);
            accepted = Boolean.TRUE.equals(invoke(update, service, token, wirePlayback));
            MediaBridgeState.info("[transport] updateMusicPlaybackState accepted=" + accepted);
        }
        invoke(progress, service, token, current == null || current.input == null ? 0L : current.input.progress());
        return accepted;
    }

    boolean release(boolean owned) throws Exception {
        // A token is never forgotten merely because cleanup threw or returned false.
        if (token == null) return true;
        if (!connected()) throw new IllegalStateException("Cleanup requires live service");
        output = null;
        if (owned) {
            try {
                invoke(playlist, service, token, SOURCE, Collections.emptyList());
                invoke(update, service, token, wirePlayback);
                invoke(progress, service, token, 0L);
            } catch (Exception error) {
                MediaBridgeState.error("[transport] clear failed; still attempting unregister", error);
            }
        }
        boolean accepted = Boolean.TRUE.equals(invoke(unregister, service, token));
        MediaBridgeState.info("[transport] unregister accepted=" + accepted);
        if (accepted) token = null;
        return accepted;
    }

    void close() {
        closed = true;
        connected = false;
        output = null;
        if (bound) {
            bound = false;
            try { context.unbindService(connection); }
            catch (Exception error) { MediaBridgeState.error("[transport] unbind failed", error); }
        }
    }

    private void resolve() throws Exception {
        ClassLoader loader = context.getClassLoader();
        try { Class.forName(SDK + "IMusicClient", false, loader); }
        catch (ClassNotFoundException absent) {
            loader = context.createPackageContext("com.ecarx.systemui.plugin",
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY).getClassLoader();
        }
        clientType = Class.forName(SDK + "IMusicClient", false, loader);
        playbackType = Class.forName(SDK + "IMusicPlaybackInfo", false, loader);
        mediaType = Class.forName(SDK + "IMediaInfo", false, loader);
        if (!clientType.isInterface() || !playbackType.isInterface() || !mediaType.isInterface()) {
            throw new IllegalStateException("Unsupported MediaCenter payload interfaces");
        }
        Class<?> wrapper = Class.forName(SDK + "MusicClientWrapper", false, loader);
        Class<?> playbackWrapper = Class.forName(SDK + "PlaybackInfoWrapper", false, loader);
        Class<?> svc = Class.forName("ecarx.xsf.mediacenter.IMediaCenterSvc", false, loader);
        Class<?> stub = Class.forName("ecarx.xsf.mediacenter.IMediaCenterSvc$Stub", false, loader);
        clientConstructor = wrapper.getDeclaredConstructor(clientType);
        playbackConstructor = playbackWrapper.getDeclaredConstructor(playbackType);
        clientConstructor.setAccessible(true);
        playbackConstructor.setAccessible(true);
        register = unique(svc, "registerMusic", wrapper);
        Class<?> tokenType = register.getReturnType();
        if (tokenType.isPrimitive()) throw new IllegalStateException("Unexpected register token type");
        unregister = svc.getMethod("unregister", tokenType);
        request = svc.getMethod("requestPlay", tokenType);
        sourceList = svc.getMethod("updateMediaSourceTypeList", tokenType, int[].class);
        source = svc.getMethod("updateCurrentSourceType", tokenType, int.class);
        playlist = svc.getMethod("updatePlaylist", tokenType, int.class, List.class);
        update = unique(svc, "updateMusicPlaybackState", tokenType, playbackWrapper);
        progress = svc.getMethod("updateCurrentProgress", tokenType, long.class);
        Class<?> helper = Class.forName(SDK + "MediaBeanHelper", false, loader);
        listWrapper = helper.getDeclaredMethod("getIMediaList", List.class);
        listWrapper.setAccessible(true);
        asInterface = stub.getMethod("asInterface", IBinder.class);
        if (!Modifier.isStatic(listWrapper.getModifiers()) || !Modifier.isStatic(asInterface.getModifiers())
                || update.getReturnType() != boolean.class || request.getReturnType() != boolean.class
                || unregister.getReturnType() != boolean.class) {
            throw new IllegalStateException("Unsupported MediaCenter method contract");
        }
        MediaBridgeState.info("[transport] contract resolved client=" + clientType.getName()
                + " tokenType=" + tokenType.getName() + " artwork=" + hasArtwork());
    }

    private boolean hasArtwork() {
        try { return playbackType.getMethod("getArtwork").getReturnType() == Uri.class; }
        catch (NoSuchMethodException absent) { return false; }
    }

    private Object proxy(Class<?> type, boolean client) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (object, method, args) -> {
            String name = method.getName();
            if (name.equals("equals")) return args != null && object == args[0];
            if (name.equals("hashCode")) return System.identityHashCode(object);
            if (name.equals("toString")) return "EcarxModMediaPayload";
            Output display = closed ? null : output;
            MediaBridgeState.Snapshot current = display == null ? null : display.input;
            if (client) {
                if (name.equals("getMusicPlaybackInfo")) return playback;
                if (name.equals("getCurrentProgress")) return number(method.getReturnType(), current == null ? 0 : current.progress());
                if (name.equals("getCurrentSourceType")) return number(method.getReturnType(), display == null ? -1 : SOURCE);
                if (name.equals("getMediaSourceTypeList")) return new int[]{SOURCE};
                if (name.equals("getPlaylist")) return display == null ? Collections.emptyList() : Collections.singletonList(media);
                if (name.equals("onMediaCenterFocusChanged")) {
                    worker.post(() -> { if (!closed) events.focusLost(); });
                    return defaultValue(method.getReturnType());
                }
                if (name.equals("onPlay") || name.equals("onPause") || name.equals("onNext")
                        || name.equals("onPrevious") || name.equals("onSourceSelected")) {
                    boolean validSource = !name.equals("onSourceSelected")
                            || (args != null && args.length > 0 && args[0] instanceof Number
                            && ((Number) args[0]).intValue() == SOURCE);
                    if (current != null && !closed && validSource) {
                        worker.post(() -> { if (!closed) events.command(name, current); });
                        return method.getReturnType() == boolean.class ? true : defaultValue(method.getReturnType());
                    }
                    if (display != null && current == null) {
                        if (name.equals("onSourceSelected") && validSource) {
                            worker.post(() -> { if (!closed) events.command(name, null); });
                            return method.getReturnType() == boolean.class ? true : defaultValue(method.getReturnType());
                        }
                        MediaBridgeState.trace("[callback] ignored " + name + " for status-only output");
                    }
                    return defaultValue(method.getReturnType());
                }
                if (name.startsWith("on")) MediaBridgeState.trace("[callback] unhandled " + name);
            }
            switch (name) {
                case "getTitle": return display == null ? "" : display.title;
                case "getArtist": return display == null ? "" : display.artist;
                case "getAlbum": return current == null ? "" : current.album;
                case "getDuration": return number(method.getReturnType(), current == null ? 0 : current.duration);
                case "getPlaybackStatus": return number(method.getReturnType(), current != null && current.playing() ? 1 : 0);
                case "getSourceType": return number(method.getReturnType(), display == null ? -1 : SOURCE);
                case "getPlayingItemPositionInQueue": return number(method.getReturnType(), display == null ? -1 : 0);
                case "getUUID":
                case "getUuid": return display == null ? "" : current == null ? MediaBridgeState.MODULE + ":vehicle-status"
                        : current.packageName + ":" + (current.mediaId.isEmpty() ? current.title : current.mediaId);
                case "getPackageName": return client ? context.getPackageName() : display == null ? ""
                        : current == null ? MediaBridgeState.MODULE : current.packageName;
                case "getAppName": return current == null ? "ECARX 车辆状态" : current.appName;
                case "getArtwork": return current == null || current.artwork.isEmpty() ? null : Uri.parse(current.artwork);
                case "getLaunchIntent":
                case "getPlayerIntent": return launch(current);
                default: return defaultValue(method.getReturnType());
            }
        });
    }

    private PendingIntent launch(MediaBridgeState.Snapshot current) {
        if (current == null) return null;
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(current.packageName);
        if (intent == null) return null;
        return PendingIntent.getActivity(context, current.packageName.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Method unique(Class<?> type, String name, Class<?>... supplied) throws NoSuchMethodException {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != supplied.length) continue;
            Class<?>[] expected = method.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < expected.length; i++) matches &= expected[i].isAssignableFrom(supplied[i]);
            if (!matches) continue;
            if (found != null && !found.equals(method)) throw new NoSuchMethodException("Ambiguous " + name);
            found = method;
        }
        if (found == null) throw new NoSuchMethodException(type.getName() + "." + name);
        return found;
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            if (!method.getName().equals("updateCurrentProgress")) {
                MediaBridgeState.trace("[ipc] begin " + method.getName());
            }
            Object result = method.invoke(receiver, args);
            if (!method.getName().equals("updateCurrentProgress")) {
                MediaBridgeState.trace("[ipc] end " + method.getName());
            }
            return result;
        }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Vendor call failed: " + method.getName(), cause);
        }
    }

    private static Object number(Class<?> type, long value) {
        if (type == long.class || type == Long.class) return value;
        return (int) value;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == String.class) return "";
        if (type == int[].class) return new int[0];
        if (List.class.isAssignableFrom(type)) return Collections.emptyList();
        return null;
    }
}
