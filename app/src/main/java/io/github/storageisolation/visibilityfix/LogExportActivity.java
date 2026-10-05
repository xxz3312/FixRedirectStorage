package io.github.storageisolation.visibilityfix;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.pm.PackageManager;
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
                        + "echo '=== Device ==='; date; cat /proc/uptime; "
                        + "echo '=== Installed APK ==='; pm path io.github.storageisolation.visibilityfix; "
                        + "echo '=== LSPosed ==='; cat /data/adb/modules/zygisk_lsposed/module.prop; "
                        + "echo '=== LSPosed module entries ==='; "
                        + "grep -iE 'SIVisibilityFix|visibilityfix|FixRedirectStorage' "
                        + "/data/adb/lspd/log/modules_*.log /data/adb/lspd/log/verbose_*.log 2>/dev/null; "
                        + "echo '=== Android logcat entries ==='; "
                        + "logcat -b all -d 2>/dev/null | grep -iE 'SIVisibilityFix|visibilityfix|FixRedirectStorage'; "
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
                    export.setEnabled(true);
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
                    export.setEnabled(true);
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
