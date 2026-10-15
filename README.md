# Storage Isolation visibility fix (API 102)

Compatibility fix for the original Storage Isolation APK on the tested Android 17 Xiaomi device. Version 0.14 restored the application list on that device; version 0.15 removed visibility hooks too aggressively and showed fewer apps. Version 0.16 restores target-only application visibility while retaining the other cleanup.

## Minimal hooks

Three operations are required on the tested framework:

1. `ComputerEngine.getInstalledPackagesBody(long, int, int)`: for the Storage Isolation UID only, use UID 1000 for package enumeration.
2. `AppsFilter.shouldFilterApplication`: bypass filtering only for the Storage Isolation UID so its UI can resolve application resources and ApplicationInfo by package name.
3. `ef1.尾巴捏捏(int, int)` in Storage Isolation: keep normal results; if the service returns an empty list, enumerate package names and request each original record through `ef1.没收门(packageName, flags, userId)`.

Existing rule records come from the original service. The module does not fabricate rules or write the rule database. Root service process detection and verbose diagnostic tracing remain removed. A short fallback count is logged to distinguish package enumeration from server record retrieval.

## Version 0.24: incomplete package enumeration

An app-side PackageManager query can return a nonempty list while still hiding installed packages. Empty service-list recovery now unions those names with a read-only root package catalog, then requests every isolation record from the original service with the original flags. It does not enable isolation, grant permissions, modify other modules, or write rules.

The catalog respects explicit user IDs and enumerates existing users for `user=-1`; candidates are deduplicated by package name and user ID. Root enumeration runs only off the main thread, has an eight-second deadline and an output limit, caches successful catalogs for five seconds, and backs off failures for thirty seconds. A denied, failed, or incomplete root query leaves app-side recovery available. Root access is subject to the Storage Isolation app's existing root-manager authorization.

Build-only validation is performed on the fix branch. Device verification remains pending; publishing a Release requires a separate explicit request.

## Supported target version (0.23)

The supported Storage Isolation build is 8.8.1 r3684, verified from the originally supplied APK (ARM64 versionCode 36842, versionName 8.8.1.r3684.1ff94a9be-arm64-v8a). The settings screen checks both version name and build revision. Unsupported installations replace the log export button with a red notice and show installed/supported versions. Missing and unreadable installations are reported separately. Returning to the screen rechecks the version so upgrading restores the log button.

The app-side hook uses the verified ef1/g10 method names and validates the full method contract. The experimental APK-wide discovery from 0.22 is removed; this module targets the final known app version.

Package enumeration includes disabled components and permission metadata. Per-package exceptions no longer stop recovery after five failures. Empty service records remain omitted according to the original flags; no default rules are fabricated. Logs now report null-record counts, omitted samples, the list's second argument, application user, versions, and exception context. The supplied logs prove hook installation failure in one case and flag-dependent null records in the other; they do not prove that all omitted records should appear in the UI.

## Install and build

Keep the original Storage Isolation APK. Install the module APK, enable it in LSPosed, and select the Android system / `system` scope plus `moe.shizuku.redirectstorage`. Reboot after updating. This uses modern libxposed API 102.

Version 0.17 resolves the Storage Isolation app ID dynamically from the system package snapshot using its package name. Package changes invalidate that snapshot, so reinstalling the app does not require changing a hardcoded UID. The same app ID is recognized across Android users. Failed lookups leave the original system behavior unchanged. App-side class and method names remain tied to the analyzed APK version.

Version 0.18 adds a PackageManagerInternal fallback when snapshot lookup is unavailable or throws, and retries failed identity resolution after 30 seconds using a monotonic clock. Successful identities remain bound to their package snapshot, preventing stale AppIDs after reinstall. Reflection uses public declaring types so private service implementations do not cause access failures. The three hook operations and native service-record recovery remain unchanged.

GitHub Actions builds the APK on pushes to main and the `fix/root-package-candidates` branch, or through **Actions → Build LSPosed module → Run workflow**. Download the `storage-isolation-visibility-fix-release` artifact for installation. The build runs unit tests and uploads only the Release APK.

Version 0.19 adds a Material 3 settings screen with dark mode and supported-device dynamic colors. Exporting diagnostics saves a log to Download and copies a FileProvider file URI to the clipboard; pasting files requires a receiving app that supports URI clipboard items. Diagnostic export requires root. Empty-list recovery also attempts a bounded, read-only root package-name query from the Storage Isolation process. The clipboard copy is served from the app cache and can be removed by clearing the cache.

The **Hide launcher icon** switch disables only a launcher alias. Open the module settings from LSPosed to restore the icon. The settings activity remains enabled. The three runtime hooks are unchanged.

Version 0.21 enables separate non-debuggable Release builds, with R8 code and resource shrinking in both variants. CI verifies the Release signature and rejects a debuggable APK. The project currently uses the existing development signing configuration; a stable production signing key has not been configured.

Normal changes trigger builds only. Release publishing is manual and requires an explicit request. APK version names use numeric versions such as `0.19`.

GitHub Actions validates compilation and packaging. Version 0.19 needs a device check for UI rendering, file clipboard paste, and launcher visibility on the installed framework.

## Downloads and license

Download the APK from [GitHub Releases](https://github.com/xxz3312/FixRedirectStorage/releases).

This project's code is licensed under the [MIT License](LICENSE). You may use, modify, redistribute, and sell it, including in closed-source software, provided the copyright and license notice are retained. This license applies to this module's code; the original Storage Isolation APK and third-party dependencies retain their own licenses.
