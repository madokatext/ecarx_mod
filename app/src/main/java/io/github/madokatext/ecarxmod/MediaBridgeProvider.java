package io.github.madokatext.ecarxmod;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Session tokens are only shared with this module and the system-UID settings host. */
public final class MediaBridgeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid() && uid != Process.SYSTEM_UID) {
            throw new SecurityException("Media bridge is restricted to module and system UID");
        }
        if (MediaBridgeState.READ.equals(method)) {
            Bundle result = MediaBridgeState.readFallback();
            result.putAll(BootSettings.readLocal(getContext()));
            return result;
        }
        if (MediaBridgeState.REPORT.equals(method)) {
            if (extras != null) MediaBridgeState.report(extras.getString("status"));
            return Bundle.EMPTY;
        }
        if (MediaBridgeState.STATUS.equals(method)) return MediaBridgeState.status();
        throw new IllegalArgumentException("Unknown media bridge method");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
