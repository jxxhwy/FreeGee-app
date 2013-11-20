package edu.shell.freegee;

/*
 * Copyright (C) 2013 Seth Shelnutt
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */


import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class DownloadReceiver extends BroadcastReceiver{
    private static final String TAG = "DownloadReceiver";

    public static final String ACTION_START_DOWNLOAD = "edu.shell.freegee.action.START_DOWNLOAD";
    public static final String DEVICE_ACTION = "device_action";

    public static final String ACTION_DOWNLOAD_STARTED = "edu.shell.freegee.action.DOWNLOAD_STARTED";

    private static final String ACTION_INSTALL_UPDATE = "edu.shell.freegee.action.INSTALL_UPDATE";
    private static final String EXTRA_FILENAME = "filename";

    public static final String DOWNLOAD_ID = "download_id";
    public static final String DOWNLOAD_MD5 = "download_md5";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (ACTION_START_DOWNLOAD.equals(action)) {
            Action daction = (Action) intent.getSerializableExtra(DEVICE_ACTION);
            handleStartDownload(context, prefs, daction);
        } else if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            handleDownloadComplete(context, prefs, id);
        } else if (ACTION_INSTALL_UPDATE.equals(action)) {
            String fileName = intent.getStringExtra(EXTRA_FILENAME);
       /*     try {
                Utils.triggerUpdate(context, fileName);
            } catch (IOException e) {
                Log.e(TAG, "Unable to reboot into recovery mode", e);
                Toast.makeText(context, R.string.apply_unable_to_reboot_toast,
                            Toast.LENGTH_SHORT).show();
                Utils.cancelNotification(context);
            }*/
        }
    }

    private void handleStartDownload(Context context, SharedPreferences prefs, Action action) {
        // If directory doesn't exist, create it
        File directory = new File(Environment.getExternalStorageDirectory() + "/freegee");
        if (!directory.exists()) {
            directory.mkdirs();
            Log.d(TAG, "Freegee created");
        }

        // Build the name of the file to download, adding .partial at the end.  It will get
        // stripped off when the download completes
        String fullFilePath = "file://" +  Environment.getExternalStorageDirectory() + "/freegee" + "/" + action.getZipFile();

        Request request = new Request(Uri.parse("http://downloads.codefi.re/direct.php?file=shelnutt2/freegee/" + action.getZipFileLocation()));
        request.addRequestHeader("Cache-Control", "no-cache");

        File temp = new File(Environment.getExternalStorageDirectory() + "/freegee" + "/" + action.getZipFile());
        if(temp.exists())
        	temp.delete();
        
        request.setTitle(context.getString(R.string.app_name));
        request.setDestinationUri(Uri.parse(fullFilePath));
        request.setAllowedOverRoaming(false);
        request.setVisibleInDownloadsUi(false);

        // Start the download
        final DownloadManager dm =
                (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(request);

        // Store in shared preferences
        prefs.edit()
                .putLong(DOWNLOAD_ID, downloadId)
                .putString(DOWNLOAD_MD5, action.getMd5sum())
                .apply();
        Log.v("Freegee","Download id from start is: "+Long.toString(downloadId));
        Intent intent = new Intent(ACTION_DOWNLOAD_STARTED);
        intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
        context.sendBroadcast(intent);
    }

    private void handleDownloadComplete(Context context, SharedPreferences prefs, long id) {
        long enqueued = prefs.getLong(DOWNLOAD_ID, -1);

        if (enqueued < 0 || id < 0 || id != enqueued) {
            return;
        }

        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Query query = new Query();
        query.setFilterById(id);

        Cursor c = dm.query(query);
        if (c == null) {
            return;
        }

        if (!c.moveToFirst()) {
            c.close();
            return;
        }

        final int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
        int failureMessageResId = -1;
        File updateFile = null;

        Intent updateIntent = new Intent(context, FreeGee.class);
        updateIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP |
                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            // Get the full path name of the downloaded file and the MD5

            // Strip off the .partial at the end to get the completed file
            String partialFileFullPath = c.getString(
                    c.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME));
            String completedFileFullPath = partialFileFullPath.replace(".partial", "");

            File partialFile = new File(partialFileFullPath);
            updateFile = new File(completedFileFullPath);
            partialFile.renameTo(updateFile);
            
            String downloadedMD5 = prefs.getString(DOWNLOAD_MD5, "");

            // Start the MD5 check of the downloaded file
            if (downloadedMD5.equals("nono") || checkMD5(downloadedMD5, updateFile)) {
                // We passed. Bring the main app to the foreground and trigger download completed
            	Log.v("Freegee","Download id from comp is: "+Long.toString(id));
                updateIntent.putExtra(FreeGee.EXTRA_FINISHED_DOWNLOAD_ID, id);
                updateIntent.putExtra(FreeGee.EXTRA_FINISHED_DOWNLOAD_PATH, completedFileFullPath);
            } else {
                // We failed. Clear the file and reset everything
                dm.remove(id);

                if (updateFile.exists()) {
                    updateFile.delete();
                }

                failureMessageResId = R.string.md5_verification_failed;
            }

        } else if (status == DownloadManager.STATUS_FAILED) {
            // The download failed, reset
            dm.remove(id);

            failureMessageResId = R.string.download_failed;
        }

        // Clear the shared prefs
        prefs.edit()
        		.remove(DOWNLOAD_MD5)
                .remove(DOWNLOAD_ID)
                .apply();

        c.close();

        //final FreeGee app = (FreeGee) context.getApplicationContext();
        if (FreeGee.isMainActivityActive()) {
            if (failureMessageResId >= 0) {
                Toast.makeText(context, failureMessageResId, Toast.LENGTH_LONG).show();
            } else {
                context.startActivity(updateIntent);
            }
        } else {
            // Get the notification ready
            PendingIntent contentIntent = PendingIntent.getActivity(context, 1,
                    updateIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder = new Notification.Builder(context)
                    .setSmallIcon(R.drawable.freegee_app2)
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true);

            if (failureMessageResId >= 0) {
                builder.setContentTitle(context.getString(R.string.download_failed));
                builder.setContentText(context.getString(failureMessageResId));
                builder.setTicker(context.getString(R.string.download_failed));
            } else {
                String zipName = updateFile.getName();

                builder.setContentTitle(context.getString(R.string.download_success));
                builder.setContentText(zipName);
                builder.setTicker(context.getString(R.string.download_success));

                Notification style = new Notification();
                //style.(context.getString(R.string.download_success));
                /*style.bigText(context.getString(R.string.not_download_install_notice, updateUiName));
                builder.(style);*/

                Intent installIntent = new Intent(context, DownloadReceiver.class);
                installIntent.setAction(ACTION_INSTALL_UPDATE);
                installIntent.putExtra(EXTRA_FILENAME, updateFile.getName());

/*                PendingIntent installPi = PendingIntent.getBroadcast(context, 0,
                        installIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);
                builder.addAction(R.drawable.ic_tab_install,
                        context.getString(R.string.not_action_install_update), installPi);*/
            }

            final NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(R.string.download_success, builder.getNotification());
        }
    }
    
    public static boolean checkMD5(String md5, File updateFile) {
        if (TextUtils.isEmpty(md5) || updateFile == null) {
            Log.e(TAG, "MD5 string empty or updateFile null");
            return false;
        }

        String calculatedDigest = calculateMD5(updateFile);
        if (calculatedDigest == null) {
            Log.e(TAG, "calculatedDigest null");
            return false;
        }

        Log.v(TAG, "Calculated digest: " + calculatedDigest);
        Log.v(TAG, "Provided digest: " + md5);

        return calculatedDigest.equalsIgnoreCase(md5);
    }

    public static String calculateMD5(File updateFile) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Exception while getting digest", e);
            return null;
        }

        InputStream is;
        try {
            is = new FileInputStream(updateFile);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Exception while getting FileInputStream", e);
            return null;
        }

        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = is.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] md5sum = digest.digest();
            BigInteger bigInt = new BigInteger(1, md5sum);
            String output = bigInt.toString(16);
            // Fill to 32 chars
            output = String.format("%32s", output).replace(' ', '0');
            return output;
        } catch (IOException e) {
            throw new RuntimeException("Unable to process file for MD5", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                Log.e(TAG, "Exception on closing MD5 input stream", e);
            }
        }
    }
}