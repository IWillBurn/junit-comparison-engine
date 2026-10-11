package org.atpfivt.comparison.engine;

import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Registered through ServiceLoader: removes {@code @ComparisonTest} classes (and their {@code @Nested} classes) from
 * the ordinary junit-jupiter engine, so they run only in the comparison engine. Disabled in the nested Launcher.
 */
public final class HideComparisonClassesFilter implements PostDiscoveryFilter {

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (!descriptor.getUniqueId().getEngineId().map("junit-jupiter"::equals).orElse(false)) {
            return FilterResult.included(null);
        }
        Class<?> type = javaClass(descriptor.getSource().orElse(null));
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            if (AnnotationSupport.isAnnotated(c, ComparisonTest.class)) {
                return FilterResult.excluded("@ComparisonTest classes run in the comparison-tests engine");
            }
        }
        return FilterResult.included(null);
    }

    private static Class<?> javaClass(TestSource source) {
        try {
            if (source instanceof ClassSource cs) {
                return cs.getJavaClass();
            }
            if (source instanceof MethodSource ms) {
                return ms.getJavaClass();
            }
        }
        catch (RuntimeException | LinkageError e) {
            // not loadable: not ours
        }
        return null;
    }
}
