package io.github.storageisolation.visibilityfix;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import dalvik.system.DexFile;
import java.util.Enumeration;
import android.util.Log;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public final class VisibilityHook extends XposedModule {
    private static final String TAG = "SIVisibilityFix";
    private static final String TARGET_PACKAGE = "moe.shizuku.redirectstorage";
    private volatile TargetIdentity targetIdentity;
    private Method packageStateLookup;
    private Method packageAppId;
    private final Object identityLock = new Object();
    private long nextResolveAttemptAt;
    private Method localServiceLookup;
    private Class<?> packageManagerInternal;
    private Method internalPackageStateLookup;
    private boolean uidLookupErrorLogged;

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            Class<?> type = Class.forName("com.android.server.pm.ComputerEngine",
                    false, param.getClassLoader());
            initializeIdentityLookup(type, param.getClassLoader());
            hookApplicationVisibility(param.getClassLoader());
            Method method = type.getDeclaredMethod("getInstalledPackagesBody",
                    long.class, int.class, int.class);
            hook(method).intercept(chain -> {
                if (!isTargetUid(chain.getThisObject(),
                        ((Number) chain.getArg(2)).intValue())) {
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

    private void initializeIdentityLookup(Class<?> computer, ClassLoader loader)
            throws ReflectiveOperationException {
        // Invoke through public declaring types, not private implementation classes.
        packageAppId = Class.forName("com.android.server.pm.pkg.PackageStateInternal",
                false, loader).getMethod("getAppId");
        try {
            packageStateLookup = computer.getMethod("getPackageStateInternal",
                    String.class, int.class);
        } catch (NoSuchMethodException error) {
            log(Log.WARN, TAG, "Snapshot lookup unavailable; using internal service", error);
        }
        try {
            packageManagerInternal = Class.forName(
                    "android.content.pm.PackageManagerInternal", false, loader);
            internalPackageStateLookup = packageManagerInternal.getMethod(
                    "getPackageStateInternal", String.class);
            localServiceLookup = Class.forName("com.android.server.LocalServices",
                    false, loader).getMethod("getService", Class.class);
        } catch (ReflectiveOperationException error) {
            log(Log.WARN, TAG, "Internal service fallback unavailable", error);
        }
    }

    private int resolveAppId(Object snapshot) throws ReflectiveOperationException {
        if (packageStateLookup != null) {
            try {
                // Unfiltered lookup avoids re-entering AppsFilter. A null state is
                // authoritative for this snapshot: do not mix it with live state.
                Object state = packageStateLookup.invoke(snapshot, TARGET_PACKAGE, 1000);
                return state == null ? -1 : ((Number) packageAppId.invoke(state)).intValue();
            } catch (ReflectiveOperationException | RuntimeException error) {
                if (localServiceLookup == null) throw error;
            }
        }
        if (localServiceLookup == null || internalPackageStateLookup == null) return -1;
        Object service = localServiceLookup.invoke(null, packageManagerInternal);
        if (service == null) return -1;
        Object state = internalPackageStateLookup.invoke(service, TARGET_PACKAGE);
        return state == null ? -1 : ((Number) packageAppId.invoke(state)).intValue();
    }

    private boolean isTargetUid(Object snapshot, int uid) {
        if (snapshot == null || uid < 10000) return false;
        TargetIdentity identity = targetIdentity;
        if (identity == null || identity.snapshot != snapshot) {
            synchronized (identityLock) {
                identity = targetIdentity;
                if (identity == null || identity.snapshot != snapshot) {
                    // Never apply an AppID cached from an obsolete package snapshot.
                    targetIdentity = null;
                    long now = SystemClock.elapsedRealtime();
                    if (now < nextResolveAttemptAt) return false;
                    try {
                        int appId = resolveAppId(snapshot);
                        if (appId < 10000) {
                            nextResolveAttemptAt = now + 30000;
                            return false;
                        }
                        identity = new TargetIdentity(snapshot, appId);
                        targetIdentity = identity;
                        nextResolveAttemptAt = 0;
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        nextResolveAttemptAt = now + 30000;
                        if (!uidLookupErrorLogged) {
                            uidLookupErrorLogged = true;
                            log(Log.ERROR, TAG, "Cannot resolve Storage Isolation UID", error);
                        }
                        return false;
                    }
                }
            }
        }
        return uid % 100000 == identity.appId;
    }

    private static final class TargetIdentity {
        final Object snapshot;
        final int appId;

        TargetIdentity(Object snapshot, int appId) {
            this.snapshot = snapshot;
            this.appId = appId;
        }
    }

    private void hookApplicationVisibility(ClassLoader loader) {
        int count = 0;
        for (String name : new String[]{
                "com.android.server.pm.AppsFilterBase",
                "com.android.server.pm.AppsFilterImpl",
                "com.android.server.pm.AppsFilterSnapshotImpl"}) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals("shouldFilterApplication")
                            || method.getReturnType() != boolean.class
                            || Modifier.isAbstract(method.getModifiers())) continue;
                    Class<?>[] args = method.getParameterTypes();
                    final int uidIndex = args.length >= 5 && args[1] == int.class ? 1
                            : args.length >= 4 && args[0] == int.class ? 0 : -1;
                    if (uidIndex != 1) continue; // Snapshot followed by the calling UID.
                    // Enumeration is not enough: the UI also looks up application
                    // resources and ApplicationInfo by package name.
                    hook(method).intercept(chain ->
                            isTargetUid(chain.getArg(0),
                                    ((Number) chain.getArg(uidIndex)).intValue())
                                    ? false : chain.proceed());
                    count++;
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable error) {
                log(Log.ERROR, TAG, "Cannot hook " + name, error);
            }
        }
        log(count == 0 ? Log.WARN : Log.INFO, TAG,
                "Application visibility hooks installed: " + count);
    }

    @Override public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        try {
            ClassLoader loader = param.getDefaultClassLoader();
            ServiceBinding binding = resolveServiceBinding(loader,
                    param.getApplicationInfo().sourceDir,
                    param.getApplicationInfo().splitSourceDirs);
            hook(binding.list).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof List && ((List<?>) result).isEmpty()) {
                    return recoverServiceRecords(binding, (Integer) chain.getArg(0),
                            (Integer) chain.getArg(1), result);
                }
                return result;
            });
            log(Log.INFO, TAG, "Service list fallback installed: "
                    + binding.list.getDeclaringClass().getName() + "." + binding.list.getName()
                    + " record=" + binding.record.getName()
                    + " factory=" + binding.factory.getDeclaringClass().getName()
                    + "." + binding.factory.getName());
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Automatic Storage Isolation contract detection failed: "
                    + error, error);
        }
    }

    private ServiceBinding resolveServiceBinding(ClassLoader loader, String base, String[] splits)
            throws Exception {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        List<String> paths = new ArrayList<>();
        paths.add(base);
        if (splits != null) java.util.Collections.addAll(paths, splits);
        for (String path : paths) {
            DexFile dex = new DexFile(path);
            try {
                Enumeration<String> entries = dex.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement();
                    if (name.startsWith(TARGET_PACKAGE + ".")) names.add(name);
                }
            } finally {
                dex.close();
            }
        }
        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) {
            try {
                classes.add(Class.forName(name, false, loader));
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Optional classes with unavailable dependencies cannot be a contract.
            }
        }
        ServiceBinding selected = null;
        int shapeCandidates = 0;
        for (Class<?> service : classes) {
            if (service.isInterface() || Modifier.isAbstract(service.getModifiers())) continue;
            Method list;
            Method record;
            try {
                list = ServiceBinding.findList(service, null);
                record = ServiceBinding.findRecord(service, null);
            } catch (NoSuchMethodException | LinkageError ignored) {
                continue;
            }
            shapeCandidates++;
            Method factory = null;
            for (Class<?> owner : classes) {
                try {
                    Method candidate = ServiceBinding.findFactory(owner, service, null);
                    if (factory != null) {
                        throw new IllegalStateException("Multiple factories for " + service.getName());
                    }
                    factory = candidate;
                } catch (NoSuchMethodException error) {
                    if (error.getMessage().startsWith("Ambiguous")) throw error;
                } catch (LinkageError ignored) {
                    // An unrelated optional class is not a service provider.
                }
            }
            if (factory == null) continue;
            if (selected != null) {
                throw new IllegalStateException("Multiple complete service contracts: "
                        + selected.list.getDeclaringClass().getName() + " and " + service.getName());
            }
            selected = new ServiceBinding(list, record, factory);
        }
        if (selected == null) throw new NoSuchMethodException(
                "No unique complete service contract; scanned=" + classes.size()
                + " list/record candidates=" + shapeCandidates);
        log(Log.INFO, TAG, "Automatic service contract detection: scanned="
                + classes.size() + " shapeCandidates=" + shapeCandidates);
        return selected;
    }

    private Object recoverServiceRecords(ServiceBinding binding,
            int flags, int listArgument, Object original) {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null);
            if (!(app instanceof Context)) return original;
            Context context = (Context) app;
            List<PackageInfo> installed = context.getPackageManager().getInstalledPackages(
                    PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.GET_PERMISSIONS);
            Object service = binding.factory.invoke(null);
            if (service == null) {
                log(Log.WARN, TAG, "Service factory returned null; preserving original list");
                return original;
            }
            List<Object> recovered = new ArrayList<>();
            List<String> omittedSample = new ArrayList<>();
            int failures = 0;
            int nullRecords = 0;
            for (PackageInfo info : installed) {
                if (info == null || info.packageName == null) continue;
                int userId = info.applicationInfo == null
                        ? android.os.Process.myUid() / 100000 : info.applicationInfo.uid / 100000;
                try {
                    // Preserve record flags and the server's filtering semantics.
                    Object record = binding.record.invoke(service, info.packageName, flags, userId);
                    if (record != null) {
                        recovered.add(record);
                    } else {
                        nullRecords++;
                        if (omittedSample.size() < 8) omittedSample.add(info.packageName + "@u" + userId);
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    if (++failures == 1) {
                        log(Log.WARN, TAG, "Single package lookup failed: "
                                + info.packageName + ": " + error, error);
                    }
                    // An isolated package failure must not truncate all later packages.
                }
            }
            log(Log.INFO, TAG, "fallback installed=" + installed.size()
                    + " nativeRecords=" + recovered.size() + " nullRecords=" + nullRecords
                    + " failures=" + failures + " flags=" + flags + " listArg=" + listArgument
                    + " appUser=" + android.os.Process.myUid() / 100000
                    + " omittedSample=" + omittedSample);
            return recovered.isEmpty() ? original : recovered;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Cannot recover service records: " + error, error);
            return original;
        }
    }
}
