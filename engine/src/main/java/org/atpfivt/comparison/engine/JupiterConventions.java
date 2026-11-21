package org.atpfivt.comparison.engine;

import static java.util.function.Predicate.not;
import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;
import static org.junit.platform.commons.support.AnnotationSupport.findRepeatableAnnotations;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.MethodDescriptor;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestTag;

/**
 * What the engine reproduces of Jupiter for the user class, which Jupiter itself never discovers: display names, the
 * method order and tags. User code (a {@code DisplayNameGenerator}, a {@code MethodOrderer}) runs here.
 */
final class JupiterConventions {

    private JupiterConventions() {
    }

    /** The class's {@code @DisplayNameGeneration}, else the configured default generator, else the standard one. */
    static DisplayNameGenerator displayNameGenerator(Class<?> testClass, ConfigurationParameters configuration) {
        Class<?> type = findAnnotation(testClass, DisplayNameGeneration.class)
                .<Class<?>>map(DisplayNameGeneration::value)
                .or(() -> configuredClass(configuration, DisplayNameGenerator.DEFAULT_GENERATOR_PROPERTY_NAME))
                .orElse(DisplayNameGenerator.Standard.class);
        return (DisplayNameGenerator) ReflectionSupport.newInstance(type);
    }

    static String displayName(Class<?> testClass, DisplayNameGenerator generator) {
        return declaredDisplayName(testClass).orElseGet(() -> generator.generateDisplayNameForClass(testClass));
    }

    private static String displayName(Method method, Class<?> testClass, DisplayNameGenerator generator) {
        return declaredDisplayName(method)
                .orElseGet(() -> generator.generateDisplayNameForMethod(List.of(), testClass, method));
    }

    /**
     * The methods in the order of the class's {@code MethodOrderer} (or of the configured default one), applied the
     * way Jupiter applies it; without an orderer, the given order (Jupiter's default method order).
     */
    static List<MethodDescriptor> orderedMethods(Class<?> testClass, List<Method> methods,
            DisplayNameGenerator generator, ConfigurationParameters configuration) {
        List<MethodDescriptor> ordered = new ArrayList<>();
        methods.forEach(method -> ordered.add(new OrderedMethod(method, displayName(method, testClass, generator))));
        Optional<Class<?>> ordererType = findAnnotation(testClass, TestMethodOrder.class)
                .<Class<?>>map(TestMethodOrder::value)
                .filter(type -> type != MethodOrderer.Default.class)
                .or(() -> configuredClass(configuration, MethodOrderer.DEFAULT_ORDER_PROPERTY_NAME));
        ordererType.ifPresent(type -> {
            MethodOrderer orderer = (MethodOrderer) ReflectionSupport.newInstance(type);
            orderer.orderMethods(new OrdererContext(testClass, ordered, configuration));
        });
        return ordered;
    }

    /** The valid {@code @Tag}s of a class or method, in declaration order. */
    static Set<TestTag> tags(AnnotatedElement element) {
        Set<TestTag> tags = findRepeatableAnnotations(element, Tag.class).stream()
                .map(Tag::value)
                .filter(TestTag::isValid)
                .map(TestTag::create)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(tags);
    }

    private static Optional<String> declaredDisplayName(AnnotatedElement element) {
        return findAnnotation(element, DisplayName.class).map(DisplayName::value).filter(not(String::isBlank));
    }

    private static Optional<Class<?>> configuredClass(ConfigurationParameters configuration, String key) {
        return configuration.get(key).flatMap(name -> ReflectionSupport.tryToLoadClass(name.strip()).toOptional());
    }

    /** Jupiter's view of one method, handed to the user's {@code MethodOrderer}. */
    private record OrderedMethod(Method method, String displayName) implements MethodDescriptor {

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public boolean isAnnotated(Class<? extends Annotation> annotationType) {
            return AnnotationSupport.isAnnotated(method, annotationType);
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
            return AnnotationSupport.findAnnotation(method, annotationType);
        }

        @Override
        public <A extends Annotation> List<A> findRepeatableAnnotations(Class<A> annotationType) {
            return AnnotationSupport.findRepeatableAnnotations(method, annotationType);
        }
    }

    /** The orderer sorts {@code methods} in place. */
    private record OrdererContext(Class<?> testClass, List<MethodDescriptor> methods,
            ConfigurationParameters configuration) implements MethodOrdererContext {

        @Override
        public Class<?> getTestClass() {
            return testClass;
        }

        @Override
        public List<? extends MethodDescriptor> getMethodDescriptors() {
            return methods;
        }

        @Override
        public Optional<String> getConfigurationParameter(String key) {
            return configuration.get(key);
        }
    }
}
