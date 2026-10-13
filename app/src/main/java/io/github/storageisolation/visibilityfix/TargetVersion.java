package io.github.storageisolation.visibilityfix;

final class TargetVersion {
    static final String PACKAGE = "moe.shizuku.redirectstorage";
    static final String LABEL = "8.8.1（r3684）";
    // Verified from the originally supplied ARM64 APK: versionCode 36842.
    // The build revision occupies the upper digits; the final digit identifies the ABI.
    static boolean supported(long code, String name) {
        return code / 10 == 3684 && name != null && name.startsWith("8.8.1.r3684.");
    }
    static boolean older(long code, String name) {
        // A different ABI or modified version is not automatically an older release.
        return code > 0 && code / 10 < 3684
                && (name == null || !name.startsWith("8.8.1.r3684."));
    }
}
