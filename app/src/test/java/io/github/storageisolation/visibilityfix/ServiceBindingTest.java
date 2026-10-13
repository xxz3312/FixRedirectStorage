package io.github.storageisolation.visibilityfix;

import java.util.List;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServiceBindingTest {
    static class Original {
        private List<Object> 尾巴捏捏(int flags, int user) { return Collections.emptyList(); }
        private Object 没收门(String name, int flags, int user) { return new Object(); }
    }
    static class Renamed {
        private List<Object> renamedList(int flags, int user) { return Collections.emptyList(); }
        private Object renamedRecord(String name, int flags, int user) { return new Object(); }
    }
    static class Factory {
        private static Renamed service() { return null; }
    }
    static class Wrong {
        public void renamedList(int flags, int user) {}
        public Object renamedRecord(String name, int flags, int user) { return null; }
    }
    static class Ambiguous {
        public List<Object> first(int flags, int user) { return null; }
        public List<Object> second(int flags, int user) { return null; }
    }
    static class WrongFactory {
        private Renamed instanceGetter() { return null; }
        private static Object wrongReturn() { return null; }
    }

    @Test public void originalNamesResolve() throws Exception {
        assertEquals("尾巴捏捏", ServiceBinding.findList(Original.class, "尾巴捏捏").getName());
        assertEquals("没收门", ServiceBinding.findRecord(Original.class, "没收门").getName());
    }
    @Test public void renamedContractAndPrivateFactoryResolve() throws Exception {
        assertEquals("renamedList", ServiceBinding.findList(Renamed.class, null).getName());
        assertEquals("renamedRecord", ServiceBinding.findRecord(Renamed.class, null).getName());
        assertNull(ServiceBinding.findFactory(Factory.class, Renamed.class, null).invoke(null));
    }
    @Test(expected = NoSuchMethodException.class) public void unrelatedClassRejected() throws Exception {
        ServiceBinding.findList(Wrong.class, null);
    }
    @Test(expected = NoSuchMethodException.class) public void ambiguousListRejected() throws Exception {
        ServiceBinding.findList(Ambiguous.class, null);
    }
    @Test(expected = NoSuchMethodException.class) public void unrelatedFactoryRejected() throws Exception {
        ServiceBinding.findFactory(WrongFactory.class, Renamed.class, null);
    }
}
