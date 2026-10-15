package io.github.storageisolation.visibilityfix;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

public final class RootPackageCatalogTest {
    @Test public void rootCatalogIncludesAllFiveMissingPackages() throws Exception {
        String[] names = {"com.max.xiaoheihe", "com.tencent.qqmusic", "com.baidu.tieba",
                "com.taptap", "com.mfcloudcalculate.networkdisk"};
        StringBuilder output = new StringBuilder("SI_USER:0\n");
        for (String name : names) output.append("package:").append(name).append('\n');
        output.append("SI_DONE\n");
        List<RootPackageCatalog.Entry> entries = RootPackageCatalog.parse(output.toString(), 0);
        assertEquals(5, entries.size());
        for (String name : names) assertTrue(entries.contains(new RootPackageCatalog.Entry(name, 0)));
    }

    @Test public void deduplicatesWithinUserButPreservesSeparateProfiles() throws Exception {
        List<RootPackageCatalog.Entry> entries = RootPackageCatalog.parse(
                "SI_USER:0\npackage:com.example.app\npackage:com.example.app\n"
                + "SI_USER:999\npackage:com.example.app\nSI_DONE\n", -1);
        assertEquals(2, entries.size());
        assertEquals(0, entries.get(0).userId);
        assertEquals(999, entries.get(1).userId);
    }

    @Test public void acceptsCrLfAndUnrelatedSuMessages() throws Exception {
        assertEquals(1, RootPackageCatalog.parse("su notice\r\nSI_USER:10\r\n"
                + "package:android\r\nSI_DONE\r\n", 10).size());
    }

    @Test(expected = IOException.class) public void rejectsTruncatedOutput() throws Exception {
        RootPackageCatalog.parse("SI_USER:0\npackage:com.example.app\n", 0);
    }

    @Test(expected = IOException.class) public void rejectsWrongUser() throws Exception {
        RootPackageCatalog.parse("SI_USER:0\npackage:com.example.app\nSI_DONE\n", 10);
    }

    @Test(expected = IOException.class) public void rejectsUnscopedPackages() throws Exception {
        RootPackageCatalog.parse("package:com.example.app\nSI_DONE\n", -1);
    }

    @Test(expected = IOException.class) public void rejectsEmptyCatalog() throws Exception {
        RootPackageCatalog.parse("SI_USER:0\nSI_DONE\n", 0);
    }

    @Test(expected = IOException.class) public void rejectsMalformedPackage() throws Exception {
        RootPackageCatalog.parse("SI_USER:0\npackage:com.example.app uid:10000\nSI_DONE\n", 0);
    }

    @Test(expected = IOException.class) public void rejectsOutputAfterCompletion() throws Exception {
        RootPackageCatalog.parse("SI_USER:0\npackage:android\nSI_DONE\npackage:com.example\n", 0);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsUnsupportedUser() {
        RootPackageCatalog.command(-2);
    }

    @Test public void explicitUserDoesNotEnumerateOtherUsers() {
        String command = RootPackageCatalog.command(10);
        assertTrue(command.contains("users=10\n"));
        assertFalse(command.contains("pm list users"));
        assertFalse(command.contains("list packages -u"));
    }
}
