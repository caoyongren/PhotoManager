/*
 * Copyright (c) 2015-2017 by k3b.
 *
 * This file is part of AndroFotoFinder / #APhotoManager.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>
 */

package de.k3b.android.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import java.io.File;
import java.util.Date;

import de.k3b.android.GuiUtil;
import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.R;
import de.k3b.android.androFotoFinder.directory.DirectoryPickerFragment;
import de.k3b.android.androFotoFinder.queries.DatabaseHelper;
import de.k3b.android.androFotoFinder.queries.FotoSql;
import de.k3b.android.androFotoFinder.transactionlog.TransactionLogSql;
import de.k3b.database.QueryParameter;
import de.k3b.database.SelectedFiles;
import de.k3b.io.DirectoryFormatter;
import de.k3b.io.FileCommands;
import de.k3b.io.IDirectory;
import de.k3b.io.OSDirectory;
import de.k3b.transactionlog.MediaTransactionLogEntryType;

/**
 * Api to manipulate files/photos.
 * Same as FileCommands with update media database.
 *
 * Created by k3b on 03.08.2015.
 */
public class AndroidFileCommands extends FileCommands {
    private static final String SETTINGS_KEY_LAST_COPY_TO_PATH = "last_copy_to_path";
    private static final String mDebugPrefix = "AndroidFileCommands.";
    protected Activity mContext;
    private AlertDialog mActiveAlert = null;
    private boolean mHasNoMedia = false;
    private MediaScanner mScanner = null;

    public AndroidFileCommands() {
        // setLogFilePath(getDefaultLogFile());
        setContext(null);
    }

    public void closeAll() {
        closeLogFile();
        if (mActiveAlert != null) {
            mActiveAlert.dismiss();
            mActiveAlert = null;
        }
    }
    public String getDefaultLogFile() {
        Boolean isSDPresent = true; // Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED);

        // since android 4.4 Evnvironment.getDataDirectory() and .getDownloadCacheDirectory ()
        // is protected by android-os :-(
        // app will not work on devices with no external storage (sdcard)
        final File rootDir = (isSDPresent) ? Environment.getExternalStorageDirectory() : Environment.getRootDirectory();
        final String zipfile = rootDir.getAbsolutePath() + "/" + mContext.getString(R.string.global_log_file_path);
        return zipfile;
    }

    /** called before copy/move/rename/delete */
    @Override
    protected void onPreProcess(String what, String[] oldPathNames, String[] newPathNames, int opCode) {
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, mDebugPrefix + "onPreProcess('" + what + "')");
        }

        // a nomedia file is affected => must update gui
        this.mHasNoMedia = mScanner.isNoMedia(22, oldPathNames) || MediaScanner.isNoMedia(22, newPathNames);
        super.onPreProcess(what, oldPathNames, newPathNames, opCode);
    }

    /** called for each modified/deleted file */
    @Override
    protected void onPostProcess(String what, String[] oldPathNames, String[] newPathNames, int modifyCount, int itemCount, int opCode) {
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, mDebugPrefix
                    + "onPostProcess('" + what + "') => " + modifyCount + "/" + itemCount);
        }
        super.onPostProcess(what, oldPathNames, newPathNames, modifyCount, itemCount, opCode);

        int resId = getResourceId(opCode);
        String message = mContext.getString(resId, Integer.valueOf(modifyCount), Integer.valueOf(itemCount));
        if ((itemCount > 0) && (mScanner != null)) {
            MediaScannerAsyncTask.updateMediaDBInBackground(mScanner, mContext, message, oldPathNames, newPathNames);
        }

        if (false && this.mHasNoMedia && (mContext != null)) {
            // a nomedia file is affected => must update gui
            this.mContext.getContentResolver().notifyChange(FotoSql.SQL_TABLE_EXTERNAL_CONTENT_URI_FILE, null, false);
            this.mHasNoMedia = false;
        }
        Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
    }

    /** called for every cath(Exception...). Version with Android specific logging */
    @Override
    protected void onException(final Throwable e, Object... params) {
        StringBuffer message = new StringBuffer();
        message.append(mDebugPrefix).append("onException(");
        for (Object param : params) {
            if (param != null) {
                message.append(param).append(" ");
            }
        }
        message.append("): ").append(e.getMessage());

        Log.e(Global.LOG_CONTEXT, message.toString(), e);
        // e.printStackTrace();
    }

    private int getResourceId(int opCode) {
        switch (opCode) {
            case OP_COPY: return R.string.copy_result_format;
            case OP_MOVE: return R.string.move_result_format;
            case OP_DELETE: return R.string.delete_result_format;
            case OP_RENAME: return R.string.rename_result_format;
            case OP_UPDATE: return R.string.update_result_format;
            default:break;
        }
        return 0;

    }

    public boolean onOptionsItemSelected(final MenuItem item, final SelectedFiles selectedFileNames) {
        if ((selectedFileNames != null) && (selectedFileNames.size() > 0)) {
            // Handle item selection
            switch (item.getItemId()) {
                case R.id.cmd_delete:
                    return cmdDeleteFileWithQuestion(selectedFileNames);
                default:break;
            }
        }
        return false;
    }

    /**
     * Check if all files in selectedFileNamesToBeModified are not write protected.
     *
     * @return null if no error. else formated error message.
     */
    public String checkWriteProtected(int resIdAction, final File... filesToBeModified) {
        //   <string name="file_err_writeprotected">\'%1$s\' ist schreibgeschützt. \'%2$s\' ist nicht möglich.</string>
        if (filesToBeModified != null) {
            for (File file : filesToBeModified) {
                if ((file != null) && (file.exists()) && (!file.canWrite())) {
                    String action = (resIdAction == 0) ? "" : mContext.getString(resIdAction);
                    // file_err_writeprotected="writeprotected \'%1$s\'.\n\n \'%2$s\' is not possible."
                    return mContext.getString(R.string.file_err_writeprotected, file.getAbsolutePath(), action);
                }
            }
        }
        return null;
    }

    public boolean rename(Long fileId, File dest, File src) {
        int result = moveOrCopyFiles(true, "rename", new Long[]{fileId}, new File[]{dest}, new File[]{src});
        return (result != 0);
    }

    public void onMoveOrCopyDirectoryPick(boolean move, IDirectory destFolder, SelectedFiles srcFotos) {
        if (destFolder != null) {
            String copyToPath = destFolder.getAbsolute();
            File destDirFolder = new File(copyToPath);

            setLastCopyToPath(copyToPath);

            String[] selectedFileNames = srcFotos.getFileNames();
            moveOrCopyFilesTo(move, destDirFolder, srcFotos.getIds(), SelectedFiles.getFiles(selectedFileNames));
        }
    }

    @NonNull
    public String getLastCopyToPath() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(mContext);
        return sharedPref.getString(SETTINGS_KEY_LAST_COPY_TO_PATH, "/");
    }

    private void setLastCopyToPath(String copyToPath) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor edit = sharedPref.edit();
        edit.putString(SETTINGS_KEY_LAST_COPY_TO_PATH, copyToPath);
        edit.apply();
    }

    public boolean cmdDeleteFileWithQuestion(final SelectedFiles fotos) {
        String[] pathNames = fotos.getFileNames();
        String errorMessage = checkWriteProtected(R.string.delete_menu_title, SelectedFiles.getFiles(pathNames));

        if (errorMessage != null) {
            Toast.makeText(this.mContext, errorMessage, Toast.LENGTH_LONG).show();
        } else {
            StringBuffer names = new StringBuffer();
            for (String name : pathNames) {
                names.append(name).append("\n");
            }
            final String message = mContext
                    .getString(R.string.delete_question_message_format, names.toString());

            final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
            final String title = mContext.getText(R.string.delete_question_title)
                    .toString();

            builder.setTitle(title + pathNames.length);
            builder.setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.btn_yes,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(
                                        final DialogInterface dialog,
                                        final int id) {
                                    mActiveAlert = null;
                                    deleteFiles(fotos);
                                }
                            }
                    )
                    .setNegativeButton(R.string.btn_no,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(
                                        final DialogInterface dialog,
                                        final int id) {
                                    mActiveAlert = null;
                                    dialog.cancel();
                                }
                            }
                    );

            final AlertDialog alert = builder.create();
            mActiveAlert = alert;
            alert.show();
        }
        return true;
    }

    private int deleteFiles(SelectedFiles fotos) {
        int nameCount = fotos.getNonEmptyNameCount();
        int deleteCount = 0;
        if (nameCount > 0) {
            String[] fileNames = fotos.getFileNames();
            deleteCount = super.deleteFiles(fileNames);
            long now = new Date().getTime();
            for(int i = 0; i < nameCount; i++) {
                addTransactionLog(fotos.getId(i), fotos.getFileName(i), now, MediaTransactionLogEntryType.DELETE, null);
            }
        }

        if ((nameCount == 0) || (nameCount == deleteCount)) {
            // no delete file error so also delete media-items
            QueryParameter where = new QueryParameter();
            FotoSql.setWhereSelectionPks (where, fotos.toIdString());

            FotoSql.deleteMedia("AndroidFileCommands.deleteFiles", mContext, where.toAndroidWhere(), null, true);
        }
        return deleteCount;
    }

    @SuppressLint("ValidFragment")
    class MediaScannerDirectoryPickerFragment extends DirectoryPickerFragment {
        /** do not use activity callback */
        @Override protected void setDirectoryListener(Activity activity) {}

        @Override
        protected void onDirectoryPick(IDirectory selection) {
            dismiss();
            if (selection != null) {
                onMediaScannerAnswer(selection.getAbsolute());
            }
        }
    }

    public boolean cmdMediaScannerWithQuestion() {
        final RecursiveMediaScannerAsyncTask scanner = RecursiveMediaScannerAsyncTask.sScanner;

        if (scanner != null) {
            // connect gui to already running scanner if possible
            scanner.resumeIfNeccessary(); // if paused resume it.
            showMediaScannerStatus(scanner);
            return true;
        } else if (AndroidFileCommands.canProcessFile(mContext)) {
            // show dialog to get start parameter
            DirectoryPickerFragment destDir = new MediaScannerDirectoryPickerFragment() {
                /** do not use activity callback */
                @Override protected void setDirectoryListener(Activity activity) {}

                @Override
                protected void onDirectoryPick(IDirectory selection) {
                    dismiss();
                    if (selection != null) {
                        onMediaScannerAnswer(selection.getAbsolute());
                    }
                }

                @Override
                public void onPause() {
                    super.onPause();

                    // else the java.lang.InstantiationException: can't instantiate
                    // class de.k3b.android.util.AndroidFileCommands$MediaScannerDirectoryPickerFragment;
                    // no empty constructor
                    // on orientation change
                    dismiss();
                }
            };

            destDir.setTitleId(R.string.scanner_dir_question);
            destDir.defineDirectoryNavigation(new OSDirectory("/", null),
                    FotoSql.QUERY_TYPE_UNDEFINED,
                    getLastCopyToPath());
            destDir.setContextMenuId(R.menu.menu_context_osdir);
            destDir.show(mContext.getFragmentManager(), "scannerPick");

            return true;
        }
        return false;
    }

    /** answer from "which directory to start scanner from"? */
    private void onMediaScannerAnswer(String scanRootDir) {
        if  ((AndroidFileCommands.canProcessFile(mContext)) || (RecursiveMediaScannerAsyncTask.sScanner == null)){

            // remove ".nomedia" file from scan root
            File nomedia = new File(scanRootDir, MediaScanner.MEDIA_IGNORE_FILENAME);
            if (nomedia.exists()) {
                if (Global.debugEnabled) {
                    Log.i(Global.LOG_CONTEXT, mDebugPrefix + "onMediaScannerAnswer deleting " + nomedia);
                }
                nomedia.delete();
            }
            if (Global.debugEnabled) {
                Log.i(Global.LOG_CONTEXT, mDebugPrefix + "onMediaScannerAnswer start scanning " + scanRootDir);
            }

            final String message = mContext.getString(R.string.scanner_menu_title);
            final RecursiveMediaScannerAsyncTask scanner = (RecursiveMediaScannerAsyncTask.sScanner != null)
                    ? RecursiveMediaScannerAsyncTask.sScanner :
                    new RecursiveMediaScannerAsyncTask(mScanner, mContext, message);
            synchronized (this) {
                if (RecursiveMediaScannerAsyncTask.sScanner == null) {
                    RecursiveMediaScannerAsyncTask.sScanner = scanner;
                    scanner.execute(new String[]{scanRootDir});
                } // else scanner is already running
            }

            showMediaScannerStatus(RecursiveMediaScannerAsyncTask.sScanner);
        }
    }

    private void showMediaScannerStatus(RecursiveMediaScannerAsyncTask mediaScanner) {
        if (mediaScanner != null) {
            mediaScanner.showStatusDialog(mContext);
        }
    }

    /**
     * Write geo data (lat/lon) to photo, media database and log.
     */
    public int setGeo(double latitude, double longitude, SelectedFiles selectedItems, int itemsPerProgress) {
        if (!Double.isNaN(latitude) && !Double.isNaN(longitude) && (selectedItems != null) && (selectedItems.size() > 0)) {
            // in case that current activity is destroyed while running async, applicationContext will allow to finish database operation
            int itemcount = 0;
            int countdown = 0;
            File[] files = SelectedFiles.getFiles(selectedItems.getFileNames());
            String errorMessage = checkWriteProtected(R.string.geo_edit_menu_title, files);

            if (errorMessage != null) {
                Toast.makeText(this.mContext, errorMessage, Toast.LENGTH_LONG).show();
            } else if (files != null) {
                Context applicationContext = this.mContext.getApplicationContext();
                int maxCount = files.length+1;
                openLogfile();
                int resultFile = 0;

                String latLong = DirectoryFormatter.parseLatLon(latitude) + " " + DirectoryFormatter.parseLatLon(longitude);
                for (File file : files) {
                    countdown--;
                    if (countdown <= 0) {
                        countdown = itemsPerProgress;
                        onProgress(itemcount, maxCount);
                    }
                    if (JpgMetaWorkflow.saveLatLon(file, latitude, longitude, mContext.getString(R.string.app_name), GuiUtil.getAppVersionName(mContext))) {
                        resultFile++;
                    }
                    itemcount++;
                    log("CALL apmGps.cmd ", getFilenameForLog(file),
                            " ", latLong);
                }
                onProgress(itemcount, maxCount);
                int resultSql = FotoSql.execUpdateGeo(applicationContext, latitude, longitude, selectedItems);

                long now = new Date().getTime();
                for(int i = 0; i < selectedItems.size(); i++) {
                    addTransactionLog(selectedItems.getId(i), selectedItems.getFileName(i), now, MediaTransactionLogEntryType.GPS, latLong);
                }

                closeLogFile();
                onProgress(++itemcount, maxCount);

                return Math.max(resultFile,resultSql);
            }
        }
        return 0;
    }

    /** called every time when command makes some little progress. Can be mapped to async progress-bar */
    protected void onProgress(int itemcount, int size) {
    }

    public AndroidFileCommands setContext(Activity mContext) {
        this.mContext = mContext;
        if (mContext != null) {
            closeLogFile();
            mScanner = MediaScanner.getInstance(mContext);
        }

        return this;
    }

    @NonNull
    public static AndroidFileCommands createFileCommand(Activity context) {
        AndroidFileCommands cmd = new AndroidFileCommands().setContext(context);
        cmd.setLogFilePath(cmd.getDefaultLogFile());
        cmd.openLogfile();
        return cmd;
    }

    @Override
    protected boolean canProcessFile(int opCode) {
        if (opCode != OP_UPDATE) {
            return AndroidFileCommands.canProcessFile(this.mContext);
        }
        return true;
    }

    public static boolean canProcessFile(Context context) {
        if (!Global.mustCheckMediaScannerRunning) return true; // always allowed. DANGEROUS !!!

        if (MediaScanner.isScannerActive(context.getContentResolver())) {
            Toast.makeText(context, R.string.scanner_err_busy, Toast.LENGTH_LONG).show();
            return false;
        }

        return (RecursiveMediaScannerAsyncTask.getBusyScanner() == null);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();

        if (mContext != null) {
            result.append(mContext).append("->");
        }
        result.append(mDebugPrefix);
        return result.toString();
    }

    @Override
    public void addTransactionLog(
            long currentMediaID, String fileFullPath, long modificationDate,
            MediaTransactionLogEntryType mediaTransactionLogEntryType, String commandData) {
        if (fileFullPath != null) {
            super.addTransactionLog(currentMediaID, fileFullPath, modificationDate,
                    mediaTransactionLogEntryType, commandData);
            SQLiteDatabase db = DatabaseHelper.getWritableDatabase(mContext);
            ContentValues values = TransactionLogSql.set(null, currentMediaID, fileFullPath, modificationDate,
                    mediaTransactionLogEntryType, commandData);
            db.insert(TransactionLogSql.TABLE, null, values);
        }
    }
}

