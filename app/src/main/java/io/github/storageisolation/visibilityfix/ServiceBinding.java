package io.github.storageisolation.visibilityfix;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

// Resolve the complete contract before installing a hook. No Android dependencies.
final class ServiceBinding {
    final Method list;
    final Method record;
    final Method factory;

    ServiceBinding(Method list, Method record, Method factory) {
        this.list = list;
        this.record = record;
        this.factory = factory;
    }

    static Method findList(Class<?> type, String name) throws NoSuchMethodException {
        return unique(type, name, false, List.class, int.class, int.class);
    }

    static Method findRecord(Class<?> type, String name) throws NoSuchMethodException {
        return unique(type, name, false, null, String.class, int.class, int.class);
    }

    static Method findFactory(Class<?> owner, Class<?> service, String name)
            throws NoSuchMethodException {
        return unique(owner, name, true, service);
    }

    private static Method unique(Class<?> type, String name, boolean isStatic,
            Class<?> result, Class<?>... params) throws NoSuchMethodException {
        Method selected = null;
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()
                    || (name != null && !name.equals(method.getName()))
                    || Modifier.isStatic(method.getModifiers()) != isStatic
                    || !java.util.Arrays.equals(method.getParameterTypes(), params)) continue;
            Class<?> returns = method.getReturnType();
            if (returns.isPrimitive() || (result != null && !result.isAssignableFrom(returns))) continue;
            if (selected != null) throw new NoSuchMethodException("Ambiguous contract in " + type.getName());
            selected = method;
        }
        if (selected == null) throw new NoSuchMethodException("No matching contract in " + type.getName());
        selected.setAccessible(true);
        return selected;
    }
}
