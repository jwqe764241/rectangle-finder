package com.example.Utils;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;

public class MediaScanner
{
    private Context context;
    private final MediaScannerConnection.OnScanCompletedListener listener = new MediaScannerConnection.OnScanCompletedListener() {
        public void onScanCompleted(String path, Uri uri) {
        }
    };

    public MediaScanner(Context context)
    {
        this.context = context;

    }

    public void scan(String path, String mime)
    {
        MediaScannerConnection.scanFile(context, new String[] { path }, new String[] { mime }, listener);
    }
}
