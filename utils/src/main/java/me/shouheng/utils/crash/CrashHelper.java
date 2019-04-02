package me.shouheng.utils.crash;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresPermission;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

/**
 * Utils to handle uncaught exceptions runtime.
 *
 * @author WngShhng 2019-04-02 15:03
 */
public final class CrashHelper {

    private static String defaultDir;
    private static String dir;

    private static String versionName;
    private static int    versionCode;

    private static final String FILE_SEP = System.getProperty("file.separator");
    @SuppressLint("SimpleDateFormat")
    private static final Format FORMAT = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss");
    @SuppressLint("SimpleDateFormat")
    private static final Format FILE_NAME_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss");

    private static UncaughtExceptionHandler defaultUncaughtExceptionHandler;

    private static OnCrashListener onCrashListener;

    @RequiresPermission(WRITE_EXTERNAL_STORAGE)
    public static void init(Application application) {
        init(application, "");
    }

    @RequiresPermission(WRITE_EXTERNAL_STORAGE)
    public static void init(Application application, @NonNull final File crashDir) {
        init(application, crashDir.getAbsolutePath());
    }

    @RequiresPermission(WRITE_EXTERNAL_STORAGE)
    public static void init(Application application, final String crashDirPath) {
        init(application, crashDirPath, null);
    }

    @RequiresPermission(WRITE_EXTERNAL_STORAGE)
    public static void init(Application application, @NonNull final File crashDir, final OnCrashListener onCrashListener) {
        init(application, crashDir.getAbsolutePath(), onCrashListener);
    }

    @RequiresPermission(WRITE_EXTERNAL_STORAGE)
    public static void init(Application application, final String crashDirPath, final OnCrashListener onCrashListener) {
        initCacheDir(application, crashDirPath);
        initAppInfo(application);
        initExceptionHandler();
        CrashHelper.onCrashListener = onCrashListener;
    }

    /**
     * List all crash logs.
     *
     * @return crash logs
     */
    public static List<File> listCrashFiles() {
        File directory = new File(dir == null ? defaultDir : dir);
        if (!directory.isDirectory() || !directory.exists()) {
            return Collections.emptyList();
        }
        return Arrays.asList(directory.listFiles());
    }

    /**
     * Init crash log cache directory.
     *
     * @param application app
     * @param crashDirPath crash log file cache directory
     */
    private static void initCacheDir(Application application, final String crashDirPath) {
        if (isSpace(crashDirPath)) {
            dir = null;
        } else {
            dir = crashDirPath.endsWith(FILE_SEP) ? crashDirPath : crashDirPath + FILE_SEP;
        }
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                && application.getExternalCacheDir() != null)
            // store under /android/data/< package name >/cache/crash/...
            defaultDir = application.getExternalCacheDir() + FILE_SEP + "crash" + FILE_SEP;
        else {
            // store under /data/data/< package name >/cache/crash/...
            defaultDir = application.getCacheDir() + FILE_SEP + "crash" + FILE_SEP;
        }
    }

    /**
     * Init app information.
     *
     * @param application app
     */
    private static void initAppInfo(Application application) {
        try {
            PackageInfo pi = application.getPackageManager().getPackageInfo(application.getPackageName(), 0);
            if (pi != null) {
                versionName = pi.versionName;
                versionCode = pi.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
    }

    /**
     * Init exception handler
     */
    private static void initExceptionHandler() {
        defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        UncaughtExceptionHandler uncaughtExceptionHandler = new UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                if (e == null) {
                    if (defaultUncaughtExceptionHandler != null) {
                        defaultUncaughtExceptionHandler.uncaughtException(t, e);
                    } else {
                        Process.killProcess(Process.myPid());
                        System.exit(1);
                    }
                    return;
                }

                final Date crashTime = new Date();
                final String time = FORMAT.format(crashTime);
                final StringBuilder sb = new StringBuilder();
                final String head = "************* uncaught exception *************" +
                        "\nTime Of Crash      : " + time +
                        "\nDevice Manufacturer: " + Build.MANUFACTURER +
                        "\nDevice Model       : " + Build.MODEL +
                        "\nAndroid Version    : " + Build.VERSION.RELEASE +
                        "\nAndroid SDK        : " + Build.VERSION.SDK_INT +
                        "\nApp VersionName    : " + versionName +
                        "\nApp VersionCode    : " + versionCode + "\n\n";
                sb.append(head);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                Throwable cause = e.getCause();
                while (cause != null) {
                    cause.printStackTrace(pw);
                    cause = cause.getCause();
                }
                pw.flush();
                sb.append(sw.toString()).append("\n").append("\n");
                final String crashInfo = sb.toString();
                final String fullPath = (dir == null ? defaultDir : dir) + "crash_" + FILE_NAME_FORMAT.format(crashTime) + ".log";
                if (createOrExistsFile(fullPath)) {
                    input2File(crashInfo, fullPath);
                } else {
                    Log.e("CrashHelper", "create " + fullPath + " failed!");
                }
                if (onCrashListener != null) {
                    onCrashListener.onCrash(crashInfo, e);
                }
                if (defaultUncaughtExceptionHandler != null) {
                    defaultUncaughtExceptionHandler.uncaughtException(t, e);
                }
            }
        };
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
    }

    private static void input2File(final String input, final String filePath) {
        Future<Boolean> submit = Executors.newSingleThreadExecutor().submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                BufferedWriter bw = null;
                try {
                    bw = new BufferedWriter(new FileWriter(filePath, true));
                    bw.write(input);
                    return true;
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                } finally {
                    try {
                        if (bw != null) {
                            bw.close();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        try {
            if (submit.get()) return;
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        Log.e("CrashHelper", "write crash info to " + filePath + " failed!");
    }

    private static boolean createOrExistsFile(final String filePath) {
        File file = new File(filePath);
        if (file.exists()) return file.isFile();
        if (!createOrExistsDir(file.getParentFile())) return false;
        try {
            return file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean createOrExistsDir(final File file) {
        return file != null && (file.exists() ? file.isDirectory() : file.mkdirs());
    }

    private static boolean isSpace(final String s) {
        if (s == null) return true;
        for (int i = 0, len = s.length(); i < len; ++i) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public interface OnCrashListener {
        void onCrash(String crashInfo, Throwable e);
    }

    private CrashHelper() {
        throw new UnsupportedOperationException("U can't initialize me!");
    }
}
