package org.atpfivt.comparison.engine;

import static org.apiguardian.api.API.Status.INTERNAL;

import java.util.Optional;

import org.apiguardian.api.API;
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
@API(status = INTERNAL, since = "0.1")
public final class HideComparisonClassesFilter implements PostDiscoveryFilter {

    private static final FilterResult INCLUDED = FilterResult.included(null);
    private static final FilterResult EXCLUDED = FilterResult
            .excluded("@ComparisonTest classes run in the comparison-tests engine");

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (descriptor.getUniqueId().getEngineId().filter(NestedJupiter.ENGINE_ID::equals).isEmpty()) {
            return INCLUDED;
        }
        return descriptor.getSource().flatMap(HideComparisonClassesFilter::javaClass)
                .filter(HideComparisonClassesFilter::isInComparisonClass).isPresent() ? EXCLUDED : INCLUDED;
    }

    /** The class, or one of its enclosing classes, is a {@code @ComparisonTest} class. */
    private static boolean isInComparisonClass(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
            if (AnnotationSupport.isAnnotated(current, ComparisonTest.class)) {
                return true;
            }
        }
        return false;
    }

    /** The class of a class or method source; empty if it cannot be loaded (then it is not ours). */
    private static Optional<Class<?>> javaClass(TestSource source) {
        try {
            return switch (source) {
                case ClassSource classSource -> Optional.of(classSource.getJavaClass());
                case MethodSource methodSource -> Optional.of(methodSource.getJavaClass());
                default -> Optional.empty();
            };
        }
        catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
