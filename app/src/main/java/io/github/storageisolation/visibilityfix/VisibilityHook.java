package io.github.storageisolation.visibilityfix;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

/**
 * Storage Isolation (moe.shizuku.redirectstorage) 可見性修正。
 *
 * 三個 hook（行為與前一版相同）：
 *
 * 1. ComputerEngine.getInstalledPackagesBody(long flags, int userId, int callingUid)
 *    當呼叫者是 SI 時把 callingUid 換成 SYSTEM_UID(1000)，
 *    讓 getInstalledPackages() 取得未經可見性過濾的清單。
 *
 * 2. AppsFilterBase.shouldFilterApplication(PackageDataSnapshot, int callingUid,
 *    Object callingSetting, PackageStateInternal target, int userId)
 *    當呼叫者的 AppId 等於 SI 的 AppId 時回傳 false，
 *    讓 SI 查詢元件 / ApplicationInfo / 資源時不被過濾。
 *
 * 3. SI 的 ef1.尾巴捏捏(int, int)：結果為空清單時，
 *    用 PackageManager 重新組出 service records。
 *
 * 與前一版的差異：
 *
 * - 以 AppId（而非 user 0 的完整 UID）比對 → 同名套件在任何使用者下都成立。
 * - AppId 於執行期解析，且不使用 getPackageUid()（它內部會再進入
 *   shouldFilterApplication，造成遞迴）；改用 ComputerEngine /
 *   PackageManagerInternal.getPackageStateInternal()，該路徑不做可見性過濾。
 * - 解析失敗時有退避重試 + 一次性 WARN，避免高頻路徑每次重跑反射與搶同一把鎖。
 * - 不使用 UserHandle.getAppId()（@hide / @SystemApi，公開 SDK stub 沒有），
 *   改用 uid % PER_USER_RANGE，語意完全相同。
 */
public final class VisibilityHook extends XposedModule {
    private static final String TAG = "SIVisibilityFix";
    private static final String TARGET_PACKAGE = "moe.shizuku.redirectstorage";

    /**
     * UserHandle.PER_USER_RANGE 的值。
     *
     * uid = userId * PER_USER_RANGE + appId，
     * 所以 AppId = uid % PER_USER_RANGE（等同 UserHandle.getAppId(uid)），
     * UserId = uid / PER_USER_RANGE（等同 UserHandle.getUserId(uid)）。
     */
    private static final int PER_USER_RANGE = 100000;

    /**
     * 解析失敗後多久才再試一次。
     *
     * 只在「解析不到」時生效：拿到 AppId 後一律走快取。
     */
    private static final long RESOLVE_RETRY_INTERVAL_MS = 30_000L;

    /*
     * 只快取 AppId，不快取完整 UID：
     *
     * 同一個套件在所有使用者共用同一個 AppId（user 0 -> appId、
     * user 10 -> 1000000 + appId），AppsFilter 內部也是用 AppId 比較。
     */
    private volatile int targetAppId = -1;

    /** 退避用；下次可嘗試解析的時間點（System.currentTimeMillis 基準）。 */
    private volatile long nextResolveAttemptAt = 0L;

    /** 只警告一次，避免洗版。 */
    private volatile boolean resolveFailureWarned = false;

    private static int appIdOf(int uid) {
        return uid % PER_USER_RANGE;
    }

    private static int userIdOf(int uid) {
        return uid / PER_USER_RANGE;
    }

    private int getOrFetchTargetAppId(ClassLoader loader, Object computerEngine) {
        final int cached = targetAppId;
        if (cached > 0) {
            return cached;
        }

        final long now = System.currentTimeMillis();
        if (now < nextResolveAttemptAt) {
            return cached;
        }

        synchronized (this) {
            if (targetAppId > 0) {
                return targetAppId;
            }
            if (System.currentTimeMillis() < nextResolveAttemptAt) {
                return targetAppId;
            }

            final int appId = resolveAppId(loader, computerEngine);
            if (appId > 0) {
                targetAppId = appId;
                log(Log.INFO, TAG, "Resolved target AppId dynamically: " + appId);
                return appId;
            }

            /*
             * 解析失敗（例如 SI 還沒安裝、或 ROM 上沒有
             * getPackageStateInternal）。退避重試，避免這個高頻路徑
             * 每次呼叫都重跑反射、並且讓所有人卡在同一把鎖上。
             */
            nextResolveAttemptAt = System.currentTimeMillis() + RESOLVE_RETRY_INTERVAL_MS;
            if (!resolveFailureWarned) {
                resolveFailureWarned = true;
                log(Log.WARN, TAG, "Cannot resolve AppId of " + TARGET_PACKAGE
                        + "; visibility fix is inactive until it succeeds (retry every "
                        + (RESOLVE_RETRY_INTERVAL_MS / 1000L) + "s)");
            } else {
                log(Log.DEBUG, TAG, "AppId still unresolved: " + TARGET_PACKAGE);
            }
            return targetAppId;
        }
    }

    /**
     * 只用「不會觸發可見性過濾」的查詢方式解析 AppId。
     *
     * 刻意不使用 getPackageUid()：ComputerEngine.getPackageUidInternal() 內部會呼叫
     * shouldFilterApplication()，也就是本模組 hook 的同一個方法，
     * 在 hook 內再呼叫它就會遞迴。
     */
    private int resolveAppId(ClassLoader loader, Object computerEngine) {
        // 1) 直接問 ComputerEngine（hook 1 的 getThisObject() 就是它）。
        int appId = readAppId(computerEngine, "getPackageStateInternal");
        if (appId > 0) {
            log(Log.INFO, TAG, "Resolved via ComputerEngine.getPackageStateInternal");
            return appId;
        }

        // 2) LocalServices -> PackageManagerInternal（退回 ComputerEngine 實例）。
        if (loader != null) {
            try {
                Class<?> localServicesClass = Class.forName(
                        "com.android.server.LocalServices", false, loader);
                Method getServiceMethod = localServicesClass.getDeclaredMethod(
                        "getService", Class.class);
                getServiceMethod.setAccessible(true);

                Class<?> pmiClass = Class.forName(
                        "android.content.pm.PackageManagerInternal", false, loader);
                Object pmi = getServiceMethod.invoke(null, pmiClass);
                if (pmi != null) {
                    appId = readAppId(pmi, "getPackageStateInternal");
                    if (appId > 0) {
                        log(Log.INFO, TAG,
                                "Resolved via PackageManagerInternal.getPackageStateInternal");
                        return appId;
                    }
                }
            } catch (Throwable error) {
                log(Log.DEBUG, TAG, "PackageManagerInternal AppId lookup failed", error);
            }
        }

        return -1;
    }

    /**
     * target.getPackageStateInternal(TARGET_PACKAGE).getAppId()
     *
     * getPackageStateInternal() 只做「名稱解析 + 查 map」
     * （resolveInternalPackageNameInternalLocked + mSettings.getPackage），
     * 不做可見性過濾，因此在 shouldFilterApplication 內呼叫是安全的。
     */
    private int readAppId(Object target, String methodName) {
        if (target == null) {
            return -1;
        }
        try {
            Method method = target.getClass().getMethod(methodName, String.class);
            return extractAppId(method.invoke(target, TARGET_PACKAGE));
        } catch (Throwable error) {
            log(Log.DEBUG, TAG, "AppId lookup via " + methodName + " failed", error);
            return -1;
        }
    }

    private int extractAppId(Object packageState) {
        if (packageState == null) {
            return -1;
        }
        try {
            // PackageSetting.getAppId()（Android 13/14/15 皆為 public）。
            Method getAppId = packageState.getClass().getMethod("getAppId");
            Object result = getAppId.invoke(packageState);
            if (result instanceof Number) {
                int appId = ((Number) result).intValue();
                return appId > 0 ? appId : -1;
            }
        } catch (Throwable error) {
            log(Log.DEBUG, TAG, "Cannot read PackageStateInternal.getAppId()", error);
        }
        return -1;
    }

    private boolean isTargetUid(ClassLoader loader, Object computerEngine, int uid) {
        if (uid < 0) {
            return false;
        }

        final int resolvedAppId = getOrFetchTargetAppId(loader, computerEngine);
        if (resolvedAppId <= 0) {
            return false;
        }

        /*
         * 比對 AppId 而不是完整 UID，因此 user 0 / user 10 / … 都成立。
         */
        return appIdOf(uid) == resolvedAppId;
    }

    // ------------------------------------------------------------------
    // Hook 1 + Hook 2：system_server
    // ------------------------------------------------------------------

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        final ClassLoader loader = param.getClassLoader();

        hookApplicationVisibility(loader);

        try {
            Class<?> type = Class.forName(
                    "com.android.server.pm.ComputerEngine", false, loader);

            Method method = type.getDeclaredMethod(
                    "getInstalledPackagesBody", long.class, int.class, int.class);

            hook(method).intercept(chain -> {
                int callingUid = ((Number) chain.getArg(2)).intValue();

                /*
                 * AppId 由 getPackageStateInternal() 取得；
                 * 原始的 userId 參數保持不動，只把呼叫者換成 SYSTEM_UID。
                 */
                if (!isTargetUid(loader, chain.getThisObject(), callingUid)) {
                    return chain.proceed();
                }

                Object[] args = chain.getArgs().toArray();
                args[2] = Integer.valueOf(1000);

                return chain.proceed(args);
            });

            log(Log.INFO, TAG, "Package enumeration hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Cannot hook package enumeration", error);
        }
    }

    private void hookApplicationVisibility(ClassLoader loader) {
        int count = 0;

        for (String name : new String[]{
                "com.android.server.pm.AppsFilterBase",
                "com.android.server.pm.AppsFilterImpl",
                "com.android.server.pm.AppsFilterSnapshotImpl"
        }) {
            try {
                Class<?> type = Class.forName(name, false, loader);

                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals("shouldFilterApplication")
                            || method.getReturnType() != boolean.class
                            || Modifier.isAbstract(method.getModifiers())) {
                        continue;
                    }

                    Class<?>[] args = method.getParameterTypes();

                    /*
                     * Android 13/14/15:
                     *   shouldFilterApplication(PackageDataSnapshot, int callingUid,
                     *                           Object, PackageStateInternal, int userId)
                     * -> callingUid 在 index 1。
                     *
                     * Android 12 的 AppsFilter.shouldFilterApplication(int callingUid, …)
                     * -> callingUid 在 index 0（該類別不在上面的清單，僅為相容判斷）。
                     */
                    final int uidIndex = args.length >= 5 && args[1] == int.class ? 1
                            : args.length >= 4 && args[0] == int.class ? 0 : -1;

                    if (uidIndex < 0) {
                        log(Log.WARN, TAG, "Unsupported signature, skipped: "
                                + name + "." + method.getName()
                                + Arrays.toString(args));
                        continue;
                    }

                    /*
                     * 只有列舉還不夠：UI 還會依套件名稱查 ApplicationInfo / 資源，
                     * 所以保留原本的 AppsFilter hook。
                     */
                    hook(method).intercept(chain -> {
                        Object arg = chain.getArg(uidIndex);
                        if (arg instanceof Number) {
                            int uid = ((Number) arg).intValue();

                            /*
                             * 第一參數就是 ComputerEngine，直接拿它解析可以少繞
                             * 一次 LocalServices（失敗時仍會退回路徑 2）。
                             */
                            if (isTargetUid(loader, chain.getArg(0), uid)) {
                                return false;
                            }
                        }
                        return chain.proceed();
                    });

                    count++;
                }
            } catch (ClassNotFoundException ignored) {
                // 這個 ROM 沒有該實作類別。
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Cannot hook " + name, error);
            }
        }

        log(count == 0 ? Log.WARN : Log.INFO, TAG,
                "Application visibility hooks installed: " + count);
    }

    // ------------------------------------------------------------------
    // Hook 3：Storage Isolation 程序
    // ------------------------------------------------------------------

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            ClassLoader loader = param.getDefaultClassLoader();

            Class<?> type = Class.forName(TARGET_PACKAGE + ".ef1", false, loader);

            Method method = type.getDeclaredMethod("尾巴捏捏", int.class, int.class);

            hook(method).intercept(chain -> {
                Object result = chain.proceed();

                if (result instanceof List && ((List<?>) result).isEmpty()) {
                    return recoverServiceRecords(
                            loader, type, (Integer) chain.getArg(0), result);
                }

                return result;
            });

            log(Log.INFO, TAG, "Empty service list fallback installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Cannot hook Storage Isolation service list", error);
        }
    }

    /**
     * 與前一版完全相同（只把 100000 換成常數）。
     *
     * 注意：getInstalledPackages(0) 只涵蓋 SI 程序自己所在的 user，
     * 且只看得到它可見的套件；這是原本就有的限制。
     */
    private Object recoverServiceRecords(
            ClassLoader loader,
            Class<?> serviceType,
            int flags,
            Object original
    ) {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication")
                    .invoke(null);

            if (!(app instanceof Context)) {
                return original;
            }

            List<PackageInfo> installed =
                    ((Context) app)
                            .getPackageManager()
                            .getInstalledPackages(0);

            Class<?> singleton = Class.forName(
                    TARGET_PACKAGE + ".g10", false, loader);

            Method getService = singleton.getDeclaredMethod("嘟嘟噜");
            getService.setAccessible(true);

            Object service = getService.invoke(null);

            Method getOne = serviceType.getDeclaredMethod(
                    "没收门", String.class, int.class, int.class);
            getOne.setAccessible(true);

            List<Object> recovered = new ArrayList<>();
            int failures = 0;

            for (PackageInfo info : installed) {
                if (info == null) {
                    continue;
                }

                int userId = info.applicationInfo == null
                        ? 0
                        : userIdOf(info.applicationInfo.uid);

                try {
                    /*
                     * IService 參數：packageName, record flags, userId。
                     * 沿用伺服器現有的規則資料，而不是建立預設紀錄。
                     */
                    Object record = getOne.invoke(service, info.packageName, flags, userId);

                    if (record != null) {
                        recovered.add(record);
                    }
                } catch (Throwable error) {
                    if (++failures == 1) {
                        log(Log.WARN, TAG, "Single package lookup failed", error);
                    }
                    if (failures >= 5) {
                        break;
                    }
                }
            }

            log(Log.INFO, TAG, "fallback installed=" + installed.size()
                    + " nativeRecords=" + recovered.size() + " failures=" + failures
                    + " flags=" + flags);

            return recovered.isEmpty() ? original : recovered;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Cannot recover service records", error);
            return original;
        }
    }
}
