package com.zealot.updater;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Task 47a: starts the updater on app launch. Android creates a ContentProvider before the app's own
 * Application.onCreate, so the library starts with no change to the app's code (the same trick as
 * com.zealot.proxy.ZealotProxyProvider). onCreate returns at once; the work runs on its own thread.
 */
public class ZealotUpdaterProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        if (getContext() != null) ZealotUpdater.start(getContext());
        return true;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
