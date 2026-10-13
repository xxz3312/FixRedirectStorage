package io.github.storageisolation.visibilityfix;

import org.junit.Test;
import static org.junit.Assert.*;

public class TargetVersionTest {
    @Test public void verifiedApkIsSupported() {
        assertTrue(TargetVersion.supported(36842, "8.8.1.r3684.1ff94a9be-arm64-v8a"));
    }
    @Test public void olderVersionIsBlocked() {
        assertFalse(TargetVersion.supported(36002, "8.0.0.r3600.example-arm64-v8a"));
        assertTrue(TargetVersion.older(36002, "8.0.0.r3600.example-arm64-v8a"));
    }
    @Test public void differentNameCannotPassUsingVersionCodeAlone() {
        assertFalse(TargetVersion.supported(36842, "8.7.4"));
    }
    @Test public void newerUnverifiedVersionIsNotReportedAsOld() {
        assertFalse(TargetVersion.supported(40002, "9.0.0.r4000.example-arm64-v8a"));
        assertFalse(TargetVersion.older(40002, "9.0.0.r4000.example-arm64-v8a"));
    }
    @Test public void unknownVersionIsNotSupported() {
        assertFalse(TargetVersion.supported(36842, null));
        assertFalse(TargetVersion.older(0, null));
    }
}
