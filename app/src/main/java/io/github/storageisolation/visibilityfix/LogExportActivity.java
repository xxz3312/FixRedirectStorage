package io.github.storageisolation.visibilityfix;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.view.View;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.appbar.MaterialToolbar;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LogExportActivity extends AppCompatActivity {
    private TextView status;
    private MaterialButton export;
    private TextView versionWarning;
    private boolean exporting;
    private boolean targetSupported;

    @Override protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.app_name);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(panel);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        TextView version = text(getString(R.string.version_label, BuildConfig.VERSION_NAME));
        panel.addView(version);
        LinearLayout logs = card(panel);
        TextView heading = text(getString(R.string.diagnostics));
        heading.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        logs.addView(heading);
        status = text(getString(R.string.ready));
        status.setTextIsSelectable(true);
        logs.addView(status);
        export = new MaterialButton(this);
        export.setText(R.string.export_logs);
        logs.addView(export, new LinearLayout.LayoutParams(-1, -2));
        export.setOnClickListener(v -> saveLogs());
        versionWarning = text("");
        versionWarning.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        versionWarning.setTextColor(MaterialColors.getColor(export, com.google.android.material.R.attr.colorError));
        versionWarning.setVisibility(View.GONE);
        logs.addView(versionWarning, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout settings = card(panel);
        MaterialSwitch hide = new MaterialSwitch(this);
        hide.setText(R.string.hide_icon);
        hide.setChecked(isLauncherHidden());
        settings.addView(hide, new LinearLayout.LayoutParams(-1, -2));
        settings.addView(text(getString(R.string.hide_icon_hint)));
        hide.setOnCheckedChangeListener((button, checked) -> {
            try {
                getPackageManager().setComponentEnabledSetting(launcher(),
                        checked ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
            } catch (RuntimeException error) {
                button.setChecked(isLauncherHidden());
                status.setText(getString(R.string.setting_failed, error.toString()));
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refreshTargetVersion();
    }

    private boolean refreshTargetVersion() {
        boolean supported = false;
        String warning;
        String detail;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(TargetVersion.PACKAGE, 0);
            supported = TargetVersion.supported(info.getLongVersionCode(), info.versionName);
            warning = getString(TargetVersion.older(info.getLongVersionCode(), info.versionName)
                    ? R.string.target_outdated : R.string.target_unsupported);
            detail = getString(R.string.target_version_detail,
                    info.versionName == null ? getString(R.string.unknown_version) : info.versionName,
                    TargetVersion.LABEL);
        } catch (PackageManager.NameNotFoundException error) {
            warning = getString(R.string.target_missing);
            detail = getString(R.string.target_required, TargetVersion.LABEL);
        } catch (RuntimeException error) {
            warning = getString(R.string.target_check_failed);
            detail = getString(R.string.target_check_retry);
        }
        export.setVisibility(supported ? View.VISIBLE : View.GONE);
        export.setEnabled(supported && !exporting);
        versionWarning.setVisibility(supported ? View.GONE : View.VISIBLE);
        if (!supported) {
            versionWarning.setText(warning);
            status.setText(detail);
        } else if (!targetSupported && !exporting) {
            status.setText(R.string.ready);
        }
        targetSupported = supported;
        return supported;
    }

    private ComponentName launcher() {
        return new ComponentName(this, getPackageName() + ".Launcher");
    }

    private boolean isLauncherHidden() {
        return getPackageManager().getComponentEnabledSetting(launcher())
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        view.setText(value);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private LinearLayout card(LinearLayout parent) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        parent.addView(card, params);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), dp(16));
        card.addView(body);
        return body;
    }

    private void saveLogs() {
        if (exporting || !refreshTargetVersion()) return;
        exporting = true;
        export.setEnabled(false);
        status.setText(R.string.working);
        String filename = "FixRedirectStorage-" +
                new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date()) + ".log";
        new Thread(() -> {
            File cached = null;
            try {
                // Root is used only to read diagnostic sources. The app owns the
                // exported file and the provider URI, so clipboard readers need no root.
                String script = "{ "
                        + "echo '=== Device ==='; date; cat /proc/uptime; getprop ro.build.version.sdk; getprop ro.build.fingerprint; "
                        + "echo '=== Installed APK ==='; pm path io.github.storageisolation.visibilityfix; "
                        + "echo '=== Target and module versions ==='; "
                        + "dumpsys package moe.shizuku.redirectstorage | grep -E 'versionName=|versionCode=|userId='; "
                        + "dumpsys package io.github.storageisolation.visibilityfix | grep -E 'versionName=|versionCode=|userId='; "
                        + "echo '=== LSPosed ==='; cat /data/adb/modules/zygisk_lsposed/module.prop; "
                        + "echo '=== LSPosed module entries ==='; "
                        + "grep -iE -A 24 'SIVisibilityFix' "
                        + "/data/adb/lspd/log/modules_*.log /data/adb/lspd/log/verbose_*.log 2>/dev/null; "
                        + "echo '=== Android logcat entries ==='; "
                        + "logcat -b all -d 2>/dev/null | grep -iE -A 24 'SIVisibilityFix|PackageManagerApisWrapper|StorageRedirect|AndroidRuntime'; "
                        + "echo '=== End ==='; }";
                Process process = new ProcessBuilder("su", "-c", script)
                        .redirectErrorStream(true).start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int size;
                    while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                }
                int exit = process.waitFor();
                byte[] data = output.toByteArray();
                if (exit != 0 || data.length == 0) {
                    throw new IllegalStateException("root exit=" + exit + " "
                            + new String(data, StandardCharsets.UTF_8).trim());
                }
                File directory = new File(getCacheDir(), "logs");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IllegalStateException("Cannot create log directory");
                }
                cached = new File(directory, filename);
                try (OutputStream file = new FileOutputStream(cached)) {
                    file.write(data);
                }
                saveToDownloads(filename, data);
                Uri clipboardFile = FileProvider.getUriForFile(this,
                        getPackageName() + ".logs", cached);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    exporting = false;
                    if (!refreshTargetVersion()) return;
                    try {
                        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                        if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
                        clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(),
                                filename, clipboardFile));
                        status.setText(getString(R.string.export_success, filename));
                    } catch (RuntimeException error) {
                        status.setText(getString(R.string.copy_failed, filename, error.toString()));
                    }
                });
            } catch (Exception error) {
                if (cached != null) cached.delete();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    exporting = false;
                    if (!refreshTargetVersion()) return;
                    status.setText(getString(R.string.export_failed, error.toString()));
                });
            }
        }, "log-export").start();
    }

    private void saveToDownloads(String filename, byte[] data) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Cannot create Download file");
        try {
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                if (stream == null) throw new IllegalStateException("Cannot open Download file");
                stream.write(data);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (getContentResolver().update(uri, values, null, null) != 1) {
                throw new IllegalStateException("Cannot publish Download file");
            }
        } catch (Exception error) {
            getContentResolver().delete(uri, null, null);
            throw error;
        }
    }
}
