package org.atpfivt.comparison.engine;

import java.lang.reflect.Modifier;

import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Auto-registered (ServiceLoader): removes the comparison classes from the outer junit-jupiter engine, so they run
 * once, in the comparison-tests engine.
 */
public final class HideFromJupiterFilter implements PostDiscoveryFilter {

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        String engine = null;
        for (UniqueId.Segment s : descriptor.getUniqueId().getSegments()) {
            if (s.getType().equals("engine")) {
                engine = s.getValue(); // the innermost engine (e.g. inside a @Suite)
            }
        }
        Class<?> testClass = javaClass(descriptor.getSource().orElse(null));
        boolean hide = ComparisonTestEngine.JUPITER.equals(engine) && testClass != null && isComparisonClass(testClass);
        return hide ? FilterResult.excluded("run by the comparison-tests engine")
                : FilterResult.included("not a comparison class");
    }

    /** The class or one it is nested in (as an inner, i.e. @Nested, class) is annotated with @ComparisonTest. */
    private static boolean isComparisonClass(Class<?> testClass) {
        for (Class<?> c = testClass; c != null; c = Modifier.isStatic(c.getModifiers()) ? null : c.getEnclosingClass()) {
            if (AnnotationSupport.isAnnotated(c, ComparisonTest.class)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> javaClass(TestSource source) {
        if (source instanceof ClassSource cs) {
            return cs.getJavaClass();
        }
        return source instanceof MethodSource ms ? ms.getJavaClass() : null;
    }
}
