# Storage Isolation visibility fix (API 102)

Compatibility fix for the original Storage Isolation APK on the tested Android 17 Xiaomi device. Version 0.14 restored the application list on that device; version 0.15 removed visibility hooks too aggressively and showed fewer apps. Version 0.16 restores target-only application visibility while retaining the other cleanup.

## Minimal hooks

Three operations are required on the tested framework:

1. `ComputerEngine.getInstalledPackagesBody(long, int, int)`: for the Storage Isolation UID only, use UID 1000 for package enumeration.
2. `AppsFilter.shouldFilterApplication`: bypass filtering only for the Storage Isolation UID so its UI can resolve application resources and ApplicationInfo by package name.
3. `ef1.尾巴捏捏(int, int)` in Storage Isolation: keep normal results; if the service returns an empty list, enumerate package names and request each original record through `ef1.没收门(packageName, flags, userId)`.

Existing rule records come from the original service. The module does not fabricate rules or write the rule database. Root service process detection and verbose diagnostic tracing remain removed. A short fallback count is logged to distinguish package enumeration from server record retrieval.

## Install and build

Keep the original Storage Isolation APK. Install the module APK, enable it in LSPosed, and select the Android system / `system` scope plus `moe.shizuku.redirectstorage`. Reboot after updating. This uses modern libxposed API 102.

Version 0.17 resolves the Storage Isolation app ID dynamically from the system package snapshot using its package name. Package changes invalidate that snapshot, so reinstalling the app does not require changing a hardcoded UID. The same app ID is recognized across Android users. Failed lookups leave the original system behavior unchanged. App-side class and method names remain tied to the analyzed APK version.

Version 0.18 adds a PackageManagerInternal fallback when snapshot lookup is unavailable or throws, and retries failed identity resolution after 30 seconds using a monotonic clock. Successful identities remain bound to their package snapshot, preventing stale AppIDs after reinstall. Reflection uses public declaring types so private service implementations do not cause access failures. The three hook operations and native service-record recovery remain unchanged.

GitHub Actions builds the APK on pushes to main, or through **Actions → Build LSPosed module → Run workflow**. Download the `storage-isolation-visibility-fix-debug` artifact.

Version 0.19 adds a Material 3 settings screen with dark mode and supported-device dynamic colors. Exporting diagnostics saves a log to Download and copies a FileProvider file URI to the clipboard; pasting files requires a receiving app that supports URI clipboard items. Only the diagnostic read requires root. The clipboard copy is served from the app cache and can be removed by clearing the cache.

The **Hide launcher icon** switch disables only a launcher alias. Open the module settings from LSPosed to restore the icon. The settings activity remains enabled. The three runtime hooks are unchanged.

Normal changes trigger builds only. Release publishing is manual and requires an explicit request. APK version names use numeric versions such as `0.19`.

GitHub Actions validates compilation and packaging. Version 0.19 needs a device check for UI rendering, file clipboard paste, and launcher visibility on the installed framework.

## Downloads and license

Download the APK from [GitHub Releases](https://github.com/xxz3312/FixRedirectStorage/releases).

This project's code is licensed under the [MIT License](LICENSE). You may use, modify, redistribute, and sell it, including in closed-source software, provided the copyright and license notice are retained. This license applies to this module's code; the original Storage Isolation APK and third-party dependencies retain their own licenses.
