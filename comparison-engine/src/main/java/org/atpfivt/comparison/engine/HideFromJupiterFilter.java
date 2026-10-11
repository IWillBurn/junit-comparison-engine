package org.atpfivt.comparison.engine;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * The regular junit-jupiter engine discovers the {@code @ComparisonTest} classes as well; this filter (registered via
 * ServiceLoader) removes them from its tree, so every test runs once, in the comparison-tests engine.
 */
public final class HideFromJupiterFilter implements PostDiscoveryFilter {

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (!descriptor.getUniqueId().getEngineId().map("junit-jupiter"::equals).orElse(false)) {
            return FilterResult.included("not the junit-jupiter engine");
        }
        Class<?> testClass = testClass(descriptor.getSource().orElse(null));
        return testClass != null && Implementations.isComparisonClass(testClass)
                ? FilterResult.excluded("@ComparisonTest class: run by the " + ComparisonTestEngine.ENGINE_ID
                        + " engine")
                : FilterResult.included("plain Jupiter test");
    }

    private static Class<?> testClass(TestSource source) {
        try {
            return source instanceof ClassSource cs ? cs.getJavaClass()
                    : source instanceof MethodSource ms ? ms.getJavaClass() : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }
}
