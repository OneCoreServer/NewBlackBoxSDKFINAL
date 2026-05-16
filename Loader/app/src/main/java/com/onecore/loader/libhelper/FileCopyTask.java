package com.onecore.loader.libhelper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.res.ResourcesCompat;

import com.onecore.loader.R;
import com.onecore.loader.utils.FLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Locale;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class FileCopyTask {

    private static final int COPY_RETRY_COUNT = 3;
    private static final long COPY_RETRY_DELAY_MS = 500L;

    private final Activity activity;
    private static LinearLayout copyOverlay = null;
    private TextView copyTitleText;
    private TextView copyMessageText;
    private TextView copyProgressText;
    private ProgressBar copyProgressBar;
    private ImageView copyIcon;
    private static boolean isCopying = false;
    private Handler handler;
    private Runnable dotRunnable;
    private long startTime = 0;
    private long copiedBytes = 0;
    private int currentProgress = 0;
    private long taskTotalBytes = 0;
    private long taskTotalCopied = 0;
    private long elapsedTime = 0;

    public FileCopyTask(Activity activity) {
        this.activity = activity;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public interface CopyCallback {
        void onCopyCompleted(boolean success);
    }

    public static File getExternalObbDir(String packageName) {
        return BEnvironment.getExternalObbDir(packageName, BlackBoxCore.getUserId());
    }

    public static File getExternalDataDir(String packageName) {
        return BEnvironment.getExternalDataDir(packageName, BlackBoxCore.getUserId());
    }

    private File getFallbackObbDir(String packageName) {
        File base = new File(activity.getFilesDir(), "virtual_storage");
        return new File(base, "Android/obb/" + packageName);
    }

    private File getSecondaryFallbackObbDir(String packageName) {
        File base = activity.getExternalFilesDir("virtual_storage");
        if (base == null) return null;
        return new File(base, "Android/obb/" + packageName);
    }


    private File getTertiaryFallbackObbDir(String packageName) {
        return new File(activity.getCacheDir(), "virtual_storage/Android/obb/" + packageName);
    }

    private File getFallbackDataDir(String packageName) {
        File base = new File(activity.getFilesDir(), "virtual_storage");
        return new File(base, "Android/data/" + packageName);
    }

    private File getSecondaryFallbackDataDir(String packageName) {
        File base = activity.getExternalFilesDir("virtual_storage");
        if (base == null) return null;
        return new File(base, "Android/data/" + packageName);
    }


    private File getTertiaryFallbackDataDir(String packageName) {
        return new File(activity.getCacheDir(), "virtual_storage/Android/data/" + packageName);
    }

    private boolean ensureDir(File dir) {
        if (dir == null) return false;
        try {
            if (dir.exists()) {
                if (!dir.isDirectory()) {
                    if (!dir.delete()) return false;
                } else {
                    ensureDirectoryPermissions(dir);
                    return dir.canWrite();
                }
            }
            boolean created = dir.mkdirs();
            if (!created && !dir.exists()) {
                return false;
            }
            ensureDirectoryPermissions(dir);
            return dir.exists() && dir.isDirectory() && dir.canWrite();
        } catch (SecurityException e) {
            FLog.warning("[DIR] security exception path=" + dir.getAbsolutePath() + ", error=" + e.getMessage());
            return false;
        } catch (Throwable e) {
            FLog.warning("[DIR] exception path=" + dir.getAbsolutePath() + ", error=" + e.getMessage());
            return false;
        }
    }

    private void ensureDirectoryPermissions(File dir) {
        if (dir == null) return;
        try {
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        } catch (SecurityException ignored) {
        }
    }

    private File resolveWritableDir(String type, String packageName, File... candidates) {
        for (int index = 0; index < candidates.length; index++) {
            File candidate = candidates[index];
            if (candidate == null) {
                continue;
            }
            if (ensureDir(candidate)) {
                if (index == 0) {
                    FLog.info("[" + type + "] using primary dir package=" + packageName + ", path=" + candidate.getAbsolutePath());
                } else {
                    FLog.info("[" + type + "] using fallback #" + index + " package=" + packageName + ", path=" + candidate.getAbsolutePath());
                }
                return candidate;
            }
            if (index == 0) {
                FLog.warning("[" + type + "] primary dir create failed package=" + packageName + ", path=" + candidate.getAbsolutePath());
            } else {
                FLog.warning("[" + type + "] fallback #" + index + " dir create failed package=" + packageName + ", path=" + candidate.getAbsolutePath());
            }
        }
        FLog.error("[" + type + "] all directory candidates failed package=" + packageName);
        return null;
    }

    public boolean isObbCopied(String packageName) {
        File primary = getExternalObbDir(packageName);
        File[] files = primary.listFiles();
        if (primary.exists() && primary.isDirectory() && files != null && files.length > 0) return true;

        File fallback = getFallbackObbDir(packageName);
        if (fallback != null) {
            File[] fallbackFiles = fallback.listFiles();
            if (fallback.exists() && fallback.isDirectory() && fallbackFiles != null && fallbackFiles.length > 0) return true;
        }

        File secondaryFallback = getSecondaryFallbackObbDir(packageName);
        if (secondaryFallback == null) return false;
        File[] secondaryFiles = secondaryFallback.listFiles();
        return secondaryFallback.exists() && secondaryFallback.isDirectory() && secondaryFiles != null && secondaryFiles.length > 0;
    }

    public boolean isDataCopied(String packageName) {
        File primary = getExternalDataDir(packageName);
        File[] files = primary.listFiles();
        if (primary.exists() && primary.isDirectory() && files != null && files.length > 0) return true;

        File fallback = getFallbackDataDir(packageName);
        if (fallback != null) {
            File[] fallbackFiles = fallback.listFiles();
            if (fallback.exists() && fallback.isDirectory() && fallbackFiles != null && fallbackFiles.length > 0) return true;
        }

        File secondaryFallback = getSecondaryFallbackDataDir(packageName);
        if (secondaryFallback == null) return false;
        File[] secondaryFiles = secondaryFallback.listFiles();
        return secondaryFallback.exists() && secondaryFallback.isDirectory() && secondaryFiles != null && secondaryFiles.length > 0;
    }

    public void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                intent.setData(uri);
                try {
                    activity.startActivity(intent);
                } catch (Exception e) {
                    FLog.warning("[OBB] app-specific files access settings unavailable, opening global settings: " + e.getMessage());
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 101);
        }
    }

    private Typeface getPremiumFont() {
        try {
            return ResourcesCompat.getFont(activity, R.font.acme);
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return Typeface.create("sans-serif-condensed", Typeface.BOLD);
            } else {
                return Typeface.DEFAULT_BOLD;
            }
        }
    }

    private void showCopyAnimation(String message, String packageName) {
        if (isCopying) return;
        isCopying = true;
        
        activity.runOnUiThread(() -> {
            if (copyOverlay != null && copyOverlay.getParent() != null) {
                try {
                    ((FrameLayout) copyOverlay.getParent()).removeView(copyOverlay);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                copyOverlay = null;
            }
            
            copyOverlay = new LinearLayout(activity);
            copyOverlay.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));
            copyOverlay.setGravity(Gravity.CENTER);
            copyOverlay.setBackgroundColor(Color.parseColor("#CC000000"));
            copyOverlay.setOrientation(LinearLayout.VERTICAL);
            copyOverlay.setClickable(true);
            copyOverlay.setFocusable(true);
            
            Typeface premiumFont = getPremiumFont();
            
            copyIcon = new ImageView(activity);
            copyIcon.setImageResource(android.R.drawable.stat_sys_download);
            copyIcon.setColorFilter(Color.parseColor("#FFD700"));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(70, 70);
            iconParams.bottomMargin = 20;
            copyIcon.setLayoutParams(iconParams);
            
            copyTitleText = new TextView(activity);
            copyTitleText.setText("✦ COPYING FILES ✦");
            copyTitleText.setTextColor(Color.parseColor("#FFD700"));
            copyTitleText.setTextSize(18);
            copyTitleText.setTypeface(premiumFont);
            copyTitleText.setGravity(Gravity.CENTER);
            copyTitleText.setPadding(0, 10, 0, 10);
            
            copyMessageText = new TextView(activity);
            copyMessageText.setText("");
            copyMessageText.setTextColor(Color.parseColor("#CCFFD700"));
            copyMessageText.setTextSize(12);
            copyMessageText.setTypeface(premiumFont);
            copyMessageText.setGravity(Gravity.CENTER);
            copyMessageText.setPadding(0, 5, 0, 5);
            
            copyProgressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            copyProgressBar.setMax(100);
            copyProgressBar.setProgress(0);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(250, 6);
            progressParams.topMargin = 15;
            progressParams.bottomMargin = 10;
            copyProgressBar.setLayoutParams(progressParams);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                copyProgressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FFD700")));
                copyProgressBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFD700")));
            }
            
            copyProgressText = new TextView(activity);
            copyProgressText.setText("0% • 0.00 MB / 0.00 MB");
            copyProgressText.setTextColor(Color.parseColor("#FFD700"));
            copyProgressText.setTextSize(11);
            copyProgressText.setTypeface(premiumFont);
            copyProgressText.setGravity(Gravity.CENTER);
            copyProgressText.setPadding(0, 5, 0, 0);
            
            copyOverlay.addView(copyIcon);
            copyOverlay.addView(copyTitleText);
            copyOverlay.addView(copyMessageText);
            copyOverlay.addView(copyProgressBar);
            copyOverlay.addView(copyProgressText);
            
            activity.addContentView(copyOverlay, copyOverlay.getLayoutParams());
            
            copyOverlay.setAlpha(0f);
            copyOverlay.animate().alpha(1f).setDuration(300).start();
            
            RotateAnimation rotateAnim = new RotateAnimation(0f, 360f, 
                    Animation.RELATIVE_TO_SELF, 0.5f, 
                    Animation.RELATIVE_TO_SELF, 0.5f);
            rotateAnim.setDuration(1500);
            rotateAnim.setRepeatCount(Animation.INFINITE);
            rotateAnim.setInterpolator(new LinearInterpolator());
            copyIcon.startAnimation(rotateAnim);
            
            ScaleAnimation scaleAnim = new ScaleAnimation(
                    1f, 1.05f, 1f, 1.05f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f);
            scaleAnim.setDuration(800);
            scaleAnim.setRepeatCount(Animation.INFINITE);
            scaleAnim.setRepeatMode(Animation.REVERSE);
            copyTitleText.startAnimation(scaleAnim);
            
            startDotsAnimation();
        });
    }
    
    private void startDotsAnimation() {
        final int[] dotCount = {0};
        final String[] dotPattern = {"", ".", "..", "..."};
        
        dotRunnable = new Runnable() {
            @Override
            public void run() {
                if (copyTitleText != null && isCopying) {
                    copyTitleText.setText("✦ COPYING FILES" + dotPattern[dotCount[0]] + " ✦");
                    dotCount[0] = (dotCount[0] + 1) % dotPattern.length;
                    handler.postDelayed(this, 400);
                }
            }
        };
        handler.post(dotRunnable);
    }
    
    private void notifyProgressUpdated(int progress) {
        currentProgress = progress;
        updateCopyProgress(progress, "", copiedBytes, taskTotalBytes);
    }

    private void updateCopyProgress(int progress, String message, long copied, long total) {
        activity.runOnUiThread(() -> {
            if (copyProgressBar != null && isCopying) {
                copyProgressBar.setProgress(progress);
                
                String progressText = String.format(Locale.getDefault(), 
                        "%d%% • %.2f MB / %.2f MB", 
                        progress, copied / (1024.0 * 1024.0), total / (1024.0 * 1024.0));
                copyProgressText.setText(progressText);
                
                String timeMessage = String.format(Locale.getDefault(),
                        "⏱️ Time: %d ms", System.currentTimeMillis() - startTime);
                copyMessageText.setText(timeMessage);
                
                AlphaAnimation fadeAnim = new AlphaAnimation(0.5f, 1f);
                fadeAnim.setDuration(300);
                copyProgressText.startAnimation(fadeAnim);
            }
        });
    }
    
    private void hideCopyAnimation(boolean success, String resultMessage) {
        if (!isCopying) return;
        isCopying = false;
        
        if (handler != null && dotRunnable != null) {
            handler.removeCallbacks(dotRunnable);
        }
        
        activity.runOnUiThread(() -> {
            if (copyOverlay != null) {
                copyOverlay.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                    if (copyOverlay.getParent() != null) {
                        ((FrameLayout) copyOverlay.getParent()).removeView(copyOverlay);
                    }
                    if (copyIcon != null) copyIcon.clearAnimation();
                    if (copyTitleText != null) copyTitleText.clearAnimation();
                    copyOverlay = null;
                    copyIcon = null;
                    copyTitleText = null;
                    copyMessageText = null;
                    copyProgressBar = null;
                    copyProgressText = null;
                    
                    showResultDialog(success, resultMessage);
                }).start();
            }
        });
    }
    
    private void showResultDialog(boolean success, String message) {
        activity.runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            LinearLayout dialogLayout = new LinearLayout(activity);
            dialogLayout.setOrientation(LinearLayout.VERTICAL);
            dialogLayout.setPadding(40, 40, 40, 40);
            dialogLayout.setBackgroundColor(Color.parseColor("#1A1A1A"));
            
            android.graphics.drawable.GradientDrawable bgShape = new android.graphics.drawable.GradientDrawable();
            bgShape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bgShape.setCornerRadius(16);
            bgShape.setColor(Color.parseColor("#1A1A1A"));
            dialogLayout.setBackground(bgShape);
            
            TextView titleText = new TextView(activity);
            titleText.setText(success ? "✓ SUCCESS ✓" : "✗ FAILED ✗");
            titleText.setTextSize(20);
            titleText.setTypeface(getPremiumFont(), Typeface.BOLD);
            titleText.setGravity(Gravity.CENTER);
            titleText.setTextColor(success ? Color.parseColor("#FFD700") : Color.parseColor("#FF4444"));
            titleText.setPadding(0, 0, 0, 20);
            
            TextView messageText = new TextView(activity);
            messageText.setText(message);
            messageText.setTextSize(14);
            messageText.setTypeface(getPremiumFont());
            messageText.setGravity(Gravity.CENTER);
            messageText.setTextColor(Color.parseColor("#FFFFFF"));
            messageText.setPadding(0, 0, 0, 30);
            
            TextView buttonText = new TextView(activity);
            buttonText.setText("OK");
            buttonText.setTextSize(16);
            buttonText.setTypeface(getPremiumFont(), Typeface.BOLD);
            buttonText.setGravity(Gravity.CENTER);
            buttonText.setTextColor(Color.parseColor("#000000"));
            buttonText.setPadding(50, 15, 50, 15);
            buttonText.setClickable(true);
            buttonText.setFocusable(true);
            
            android.graphics.drawable.GradientDrawable buttonShape = new android.graphics.drawable.GradientDrawable();
            buttonShape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            buttonShape.setCornerRadius(25);
            buttonShape.setColor(Color.parseColor("#FFD700"));
            buttonText.setBackground(buttonShape);
            
            dialogLayout.addView(titleText);
            dialogLayout.addView(messageText);
            dialogLayout.addView(buttonText);
            
            builder.setView(dialogLayout);
            builder.setCancelable(false);
            
            AlertDialog dialog = builder.create();
            dialog.show();
            
            buttonText.setOnClickListener(v -> dialog.dismiss());
            
            dialogLayout.setAlpha(0f);
            dialogLayout.animate().alpha(1f).setDuration(300).start();
        });
    }


    private boolean hasEnoughFreeSpace(File destinationDir, long requiredBytes) {
        try {
            File base = destinationDir;
            while (base != null && !base.exists()) {
                base = base.getParentFile();
            }
            if (base == null) return false;
            long usable = base.getUsableSpace();
            return usable > requiredBytes + (50L * 1024L * 1024L);
        } catch (Throwable e) {
            FLog.error("[OBB] disk space check failed: " + e.getMessage());
            return false;
        }
    }

    private boolean verifyCopiedFiles(File[] sourceFiles, File destDir) {
        if (sourceFiles == null) return false;
        for (File source : sourceFiles) {
            File dest = new File(destDir, source.getName());
            if (!dest.exists()) return false;
            if (source.isFile() && source.length() != dest.length()) {
                return false;
            }
        }
        return true;
    }

    public void copyObbFolderAsync(final String packageName, final CopyCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestStoragePermission();
            return;
        }

        File sourceDataDirCheck = new File(Environment.getExternalStorageDirectory(), "Android/data/" + packageName);
        boolean dataSourceAvailable = sourceDataDirCheck.exists();

        if (isObbCopied(packageName) && (!dataSourceAvailable || isDataCopied(packageName))) {
            if (callback != null) callback.onCopyCompleted(true);
            return;
        }

        showCopyAnimation("", packageName);
        
        new AsyncTask<Void, Integer, Boolean>() {

            private String errorMsg = "";
            private String copiedToPath = "";
            private String dataCopyWarning = "";

            @Override
            protected void onPreExecute() {
                startTime = System.currentTimeMillis();
                copiedBytes = 0;
                taskTotalBytes = 0;
                taskTotalCopied = 0;
            }

            @Override
            protected Boolean doInBackground(Void... params) {
                File rootStorage = Environment.getExternalStorageDirectory();
                File sourceObbDir = new File(rootStorage, "Android/obb/" + packageName);
                File sourceDataDir = new File(rootStorage, "Android/data/" + packageName);
                File destObbDir = resolveWritableDir("OBB", packageName, getExternalObbDir(packageName), getFallbackObbDir(packageName), getSecondaryFallbackObbDir(packageName), getTertiaryFallbackObbDir(packageName));
                File destDataDir = resolveWritableDir("DATA", packageName, getExternalDataDir(packageName), getFallbackDataDir(packageName), getSecondaryFallbackDataDir(packageName), getTertiaryFallbackDataDir(packageName));

                if (destObbDir == null) {
                    errorMsg = "Destination OBB folder creation failed!";
                    FLog.error("[OBB] " + errorMsg + " package=" + packageName + ", both primary and fallback paths unavailable");
                    return false;
                }
                copiedToPath = destObbDir.getAbsolutePath();

                if (!sourceObbDir.exists() || !sourceObbDir.canRead()) {
                    errorMsg = "Source OBB not found or unreadable!";
                    FLog.error("[OBB] " + errorMsg + " package=" + packageName + ", src=" + sourceObbDir.getAbsolutePath());
                    return false;
                }

                if (!ensureDir(destObbDir)) {
                    errorMsg = "Destination OBB folder creation failed!";
                    FLog.error("[OBB] " + errorMsg + " package=" + packageName + ", dest=" + destObbDir.getAbsolutePath());
                    return false;
                }

                File[] obbFiles = sourceObbDir.listFiles();
                if (obbFiles == null || obbFiles.length == 0) {
                    errorMsg = "No OBB files to copy!";
                    FLog.error("[OBB] " + errorMsg + " package=" + packageName + ", src=" + sourceObbDir.getAbsolutePath());
                    return false;
                }

                for (File file : obbFiles) taskTotalBytes += getDirectorySize(file);
                if (taskTotalBytes <= 0) {
                    errorMsg = "OBB files size is zero or unreadable!";
                    FLog.error("[OBB] " + errorMsg + " package=" + packageName + " src=" + sourceObbDir.getAbsolutePath());
                    return false;
                }

                if (!destObbDir.canWrite()) {
                    errorMsg = "Destination OBB path not writable";
                    FLog.error("[OBB] " + errorMsg + " package=" + packageName + ", dest=" + destObbDir.getAbsolutePath());
                    return false;
                }

                if (!hasEnoughFreeSpace(destObbDir, taskTotalBytes)) {
                    errorMsg = "Insufficient storage for OBB copy";
                    FLog.error("[OBB] " + errorMsg + " package=" + packageName + ", requiredBytes=" + taskTotalBytes);
                    return false;
                }

                boolean copySuccess = false;
                for (int attempt = 1; attempt <= COPY_RETRY_COUNT; attempt++) {
                    try {
                        taskTotalCopied = 0;
                        copiedBytes = 0;
                        FLog.info("[OBB] copy start package=" + packageName + ", attempt=" + attempt + ", src=" + sourceObbDir.getAbsolutePath() + ", dest=" + destObbDir.getAbsolutePath() + ", totalBytes=" + taskTotalBytes);
                        for (File file : obbFiles) {
                            copyFileOrDirectoryWithProgress(file, new File(destObbDir, file.getName()));
                        }
                        copySuccess = verifyCopiedFiles(obbFiles, destObbDir);
                        if (copySuccess) {
                            FLog.info("[OBB] integrity check passed package=" + packageName + ", attempt=" + attempt);
                            break;
                        }
                        FLog.warning("[OBB] integrity check failed package=" + packageName + ", attempt=" + attempt);
                    } catch (IOException e) {
                        FLog.error("[OBB] copy attempt failed package=" + packageName + ", attempt=" + attempt + ", error=" + e.getMessage());
                    }

                    if (attempt < COPY_RETRY_COUNT) {
                        try { Thread.sleep(COPY_RETRY_DELAY_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                }

                if (!copySuccess) {
                    errorMsg = "OBB copy failed after retries";
                    return false;
                }

                try {
                    if (sourceDataDir.exists()) {
                        File[] sourceDataChildren = sourceDataDir.listFiles();
                        if (sourceDataChildren == null) {
                            dataCopyWarning = "Android/data access denied by system; continuing with OBB only";
                        } else {
                            long sourceDataFiles = countFilesRecursive(sourceDataDir);
                            if (destDataDir != null) {
                                copyDirectory(sourceDataDir, destDataDir);
                            } else {
                                dataCopyWarning = "Virtual Android/data destination unavailable; skipped data copy";
                                FLog.warning("[DATA] destination unavailable package=" + packageName + ", skipping Android/data copy");
                            }
                            long destDataFiles = destDataDir != null ? countFilesRecursive(destDataDir) : 0;
                            if (sourceDataFiles > 0 && destDataFiles == 0) {
                                dataCopyWarning = "Android/data appears empty in virtual storage after copy";
                            }
                        }
                    }
                } catch (IOException e) {
                    errorMsg = "Error: " + e.getMessage();
                    FLog.error("[OBB] copy failed package=" + packageName + ", error=" + e.getMessage());
                    return false;
                }
                return true;
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                currentProgress = values[0];
                updateCopyProgress(currentProgress, "", copiedBytes, taskTotalBytes);
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (success) {
                    String successMsg = "✓ Files copied successfully!\n✓ Virtual storage: " + copiedToPath;
                    if (!dataCopyWarning.isEmpty()) {
                        successMsg += "\n⚠ " + dataCopyWarning;
                    }
                    FLog.info("[OBB] copy success package=" + packageName + ", dest=" + copiedToPath + (dataCopyWarning.isEmpty() ? "" : ", warning=" + dataCopyWarning));
                    hideCopyAnimation(true, successMsg);
                } else {
                    FLog.error("[OBB] copy failed package=" + packageName + ", message=" + errorMsg);
                    hideCopyAnimation(false, errorMsg);
                }
                
                if (callback != null) {
                    callback.onCopyCompleted(success);
                }
            }
        }.execute();
    }

    private void copyDirectory(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw new IOException("Failed to create directory: " + destination.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                copyDirectory(child, new File(destination, child.getName()));
            }
            return;
        }

        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(destination);
             FileChannel sourceChannel = inputStream.getChannel();
             FileChannel destChannel = outputStream.getChannel()) {
            long size = sourceChannel.size();
            long transferred = 0;
            while (transferred < size) {
                long bytes = sourceChannel.transferTo(transferred, size - transferred, destChannel);
                if (bytes <= 0) break;
                transferred += bytes;
            }
        }
    }


    private long getDirectorySize(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();

        long size = 0;
        File[] children = file.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            size += getDirectorySize(child);
        }
        return size;
    }

    private void copyFileOrDirectoryWithProgress(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists() && !destination.mkdirs()) {
                throw new IOException("Failed to create directory: " + destination.getAbsolutePath());
            }
            File[] children = source.listFiles();
            if (children == null) {
                return;
            }
            for (File child : children) {
                copyFileOrDirectoryWithProgress(child, new File(destination, child.getName()));
            }
            return;
        }

        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
        }

        try (FileChannel sourceChannel = new FileInputStream(source).getChannel();
             FileChannel destChannel = new FileOutputStream(destination).getChannel()) {
            long size = sourceChannel.size();
            long transferred = 0;
            while (transferred < size) {
                long bytes = sourceChannel.transferTo(transferred, size - transferred, destChannel);
                if (bytes <= 0) break;
                transferred += bytes;
                taskTotalCopied += bytes;
                copiedBytes = taskTotalCopied;
                elapsedTime = System.currentTimeMillis() - startTime;
                int progress = (int) ((taskTotalCopied * 100) / taskTotalBytes);
                notifyProgressUpdated(progress);
            }
        }
    }

    private long countFilesRecursive(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return 1;

        File[] children = dir.listFiles();
        if (children == null) return 0;

        long count = 0;
        for (File child : children) {
            count += countFilesRecursive(child);
        }
        return count;
    }

    public static boolean deleteObbFolder(String packageName) {
        File obbDestDir = getExternalObbDir(packageName);
        return deleteDirectory(obbDestDir);
    }

    private static boolean deleteDirectory(File dir) {
        if (dir != null && dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectory(child);
                }
            }
        }
        return dir != null && dir.delete();
    }
}