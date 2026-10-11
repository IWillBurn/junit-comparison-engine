package org.atpfivt.comparison.engine;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.platform.commons.support.AnnotationSupport;

/** The implementation classes of a {@code @ComparisonTest} class, in the order of {@code value()}. */
record Implementations(List<Class<?>> classes) {

    /**
     * The {@code @ComparisonTest} that applies to a test class: on the class itself (also inherited) or on the nearest
     * enclosing class (for {@code @Nested} classes).
     */
    static Optional<ComparisonTest> annotationOf(Class<?> testClass) {
        for (Class<?> c = testClass; c != null; c = c.getEnclosingClass()) {
            Optional<ComparisonTest> annotation = AnnotationSupport.findAnnotation(c, ComparisonTest.class);
            if (annotation.isPresent()) {
                return annotation;
            }
        }
        return Optional.empty();
    }

    static boolean isComparisonClass(Class<?> testClass) {
        return annotationOf(testClass).isPresent();
    }

    /** Validates the annotation; the messages become discovery errors. */
    static Implementations of(ComparisonTest annotation, Class<?> testClass) {
        List<Class<?>> classes = List.of(annotation.value());
        List<String> problems = new ArrayList<>();
        if (classes.isEmpty()) {
            problems.add("declares no implementations");
        }
        Set<String> names = new HashSet<>();
        for (Class<?> c : classes) {
            if (!names.add(c.getSimpleName())) {
                problems.add("has two implementations with the simple name " + c.getSimpleName());
            }
            if (c.isInterface() || Modifier.isAbstract(c.getModifiers())) {
                problems.add(c.getName() + " is not a concrete class");
            }
            else if (Arrays.stream(c.getDeclaredConstructors()).noneMatch(k -> k.getParameterCount() == 0)) {
                problems.add(c.getName() + " has no no-arg constructor");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                "@ComparisonTest on " + testClass.getName() + " " + String.join("; ", problems));
        }
        return new Implementations(classes);
    }

    /** The index of the parameter that receives the implementation: the last one whose type accepts all of them. */
    int implementationParameterIndex(Class<?>[] parameterTypes) {
        for (int i = parameterTypes.length - 1; i >= 0; i--) {
            Class<?> type = parameterTypes[i];
            if (classes.stream().allMatch(type::isAssignableFrom)) {
                return i;
            }
        }
        return -1;
    }
}
