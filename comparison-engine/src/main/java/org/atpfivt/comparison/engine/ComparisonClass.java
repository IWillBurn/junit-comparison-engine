package org.atpfivt.comparison.engine;

import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;
import static org.junit.platform.commons.support.AnnotationSupport.findRepeatableAnnotations;
import static org.junit.platform.commons.support.AnnotationSupport.isAnnotated;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.ClassTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.MethodDescriptor;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * What the engine knows about one {@code @ComparisonTest} class: its implementations and its test methods in Jupiter's
 * order (the class's {@code MethodOrderer}, or Jupiter's default method order), with Jupiter-like display names.
 * Computed with public JUnit API only; the user class itself is never discovered by the nested Jupiter.
 */
final class ComparisonClass {

    /** One implementation: the leaf name (simple class name, made unique) and the class. */
    record Implementation(String name, Class<?> type) {
    }

    enum Kind { TEST, PARAMETERIZED, UNSUPPORTED }

    /** A test method (or an unsupported member) of the class. */
    record Member(String id, String displayName, String legacyName, Kind kind, Method method, Class<?> nestedClass,
            int implementationIndex, String unsupportedReason, Set<TestTag> tags) {

        boolean parameterized() {
            return kind == Kind.PARAMETERIZED;
        }

        TestSource source(Class<?> testClass) {
            return method != null ? MethodSource.from(testClass, method) : ClassSource.from(nestedClass);
        }
    }

    final Class<?> testClass;
    final String displayName;
    final List<Implementation> implementations = new ArrayList<>();
    final List<Member> members = new ArrayList<>();
    final Set<TestTag> tags;
    /** {@code @ParameterizedClass} or another {@code @ClassTemplate} on the user class: its methods appear per invocation. */
    final boolean classTemplate;
    /** Why the class cannot run at all, or null. */
    final String problem;

    static boolean isComparisonClass(Class<?> c) {
        return isAnnotated(c, ComparisonTest.class) && !c.isInterface() && !c.isAnnotation()
                && !Modifier.isAbstract(c.getModifiers()) && !Modifier.isPrivate(c.getModifiers())
                && !c.isAnonymousClass() && !c.isLocalClass()
                && (c.getEnclosingClass() == null || Modifier.isStatic(c.getModifiers()));
    }

    ComparisonClass(Class<?> testClass, ConfigurationParameters configuration) {
        this.testClass = testClass;
        this.classTemplate = isAnnotated(testClass, ClassTemplate.class);
        this.tags = tags(testClass);
        String name = testClass.getSimpleName();
        String why;
        try {
            // user code runs here (DisplayNameGenerator, MethodOrderer): a failure fails this class, not the discovery
            DisplayNameGenerator generator = displayNameGenerator(configuration);
            Optional<String> annotated = findAnnotation(testClass, DisplayName.class).map(DisplayName::value)
                    .filter(s -> !s.isBlank());
            name = annotated.isPresent() ? annotated.get() : generator.generateDisplayNameForClass(testClass);
            why = Modifier.isFinal(testClass.getModifiers())
                    ? "the class must not be final: the engine runs it through a generated subclass"
                    : collectImplementations();
            List<Member> methods = new ArrayList<>();
            for (Method method : ReflectionSupport.findMethods(testClass, ComparisonClass::isTestable,
                HierarchyTraversalMode.TOP_DOWN)) {
                methods.add(member(method, generator));
            }
            members.addAll(order(methods, configuration));
            for (Class<?> nested : ReflectionSupport.findNestedClasses(testClass, c -> isAnnotated(c, Nested.class))) {
                members.add(new Member(nested.getSimpleName(), nested.getSimpleName(), nested.getName(),
                    Kind.UNSUPPORTED, null, nested, -1, "@Nested classes are not supported in a @ComparisonTest class",
                    tags(nested)));
            }
        }
        catch (RuntimeException | LinkageError e) {
            why = "cannot analyze the class: " + e;
        }
        this.displayName = name;
        this.problem = why;
    }

    private String collectImplementations() {
        Class<?>[] types = findAnnotation(testClass, ComparisonTest.class).map(ComparisonTest::value)
                .orElse(new Class<?>[0]);
        if (types.length == 0) {
            return "@ComparisonTest lists no implementations";
        }
        Set<String> names = new HashSet<>();
        for (Class<?> type : types) {
            if (type.isInterface() || Modifier.isAbstract(type.getModifiers())
                    || Arrays.stream(type.getDeclaredConstructors()).noneMatch(c -> c.getParameterCount() == 0)) {
                return "implementation " + type.getName() + " must be a concrete class with a no-arg constructor";
            }
            String name = type.getSimpleName();
            for (int n = 2; !names.add(name); n++) {
                name = type.getSimpleName() + "#" + n;
            }
            implementations.add(new Implementation(name, type));
        }
        return null;
    }

    /** The UniqueId segment value of a method: {@code name(fully.qualified.ParameterTypes)}, like Jupiter's. */
    static String memberId(Method method) {
        return method.getName() + "(" + Arrays.stream(method.getParameterTypes()).map(Class::getTypeName)
                .collect(Collectors.joining(", ")) + ")";
    }

    private static boolean isTestable(Method m) {
        return isAnnotated(m, Test.class) || isAnnotated(m, TestTemplate.class) || isAnnotated(m, TestFactory.class);
    }

    private Member member(Method method, DisplayNameGenerator generator) {
        String id = memberId(method);
        String legacy = method.getName() + "(" + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(", ")) + ")";
        String display = findAnnotation(method, DisplayName.class).map(DisplayName::value).filter(s -> !s.isBlank())
                .orElseGet(() -> generator.generateDisplayNameForMethod(List.of(), testClass, method));
        Set<TestTag> methodTags = tags(method);
        boolean test = isAnnotated(method, Test.class);
        boolean parameterized = isAnnotated(method, ParameterizedTest.class);
        String reason = null;
        if (test && parameterized) {
            reason = "both @Test and @ParameterizedTest";
        }
        else if (!test && !parameterized) {
            reason = "@" + (isAnnotated(method, TestFactory.class) ? "TestFactory"
                    : isAnnotated(method, RepeatedTest.class) ? "RepeatedTest" : "TestTemplate")
                    + " is not supported in a @ComparisonTest class";
        }
        else if (Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())
                || method.getReturnType() != void.class) {
            reason = "a test method must not be static or private and must return void";
        }
        int index = implementationParameter(method);
        if (reason == null && index < 0) {
            reason = "no parameter accepts every implementation (" + implementations.stream()
                    .map(Implementation::name).collect(Collectors.joining(", ")) + ")";
        }
        Kind kind = reason != null ? Kind.UNSUPPORTED : parameterized ? Kind.PARAMETERIZED : Kind.TEST;
        return new Member(id, display, legacy, kind, method, null, index, reason, methodTags);
    }

    /** Index of the last parameter whose type accepts every implementation, or -1. */
    private int implementationParameter(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = types.length - 1; i >= 0; i--) {
            Class<?> type = types[i];
            if (!implementations.isEmpty() && implementations.stream().allMatch(impl -> type.isAssignableFrom(impl.type()))) {
                return i;
            }
        }
        return -1;
    }

    private DisplayNameGenerator displayNameGenerator(ConfigurationParameters configuration) {
        Optional<Class<?>> type = findAnnotation(testClass, DisplayNameGeneration.class)
                .<Class<?>> map(DisplayNameGeneration::value)
                .or(() -> configuration.get(DisplayNameGenerator.DEFAULT_GENERATOR_PROPERTY_NAME)
                        .flatMap(name -> ReflectionSupport.tryToLoadClass(name.strip()).toOptional()));
        return (DisplayNameGenerator) ReflectionSupport.newInstance(type.orElse(DisplayNameGenerator.Standard.class));
    }

    /** Applies the class's MethodOrderer (or the configured default one) like Jupiter does; else keeps the order. */
    private List<Member> order(List<Member> methods, ConfigurationParameters configuration) {
        Optional<Class<?>> type = findAnnotation(testClass, TestMethodOrder.class).<Class<?>> map(TestMethodOrder::value)
                .filter(t -> t != MethodOrderer.Default.class)
                .or(() -> configuration.get(MethodOrderer.DEFAULT_ORDER_PROPERTY_NAME)
                        .flatMap(name -> ReflectionSupport.tryToLoadClass(name.strip()).toOptional()));
        if (type.isEmpty()) {
            return methods;
        }
        MethodOrderer orderer = (MethodOrderer) ReflectionSupport.newInstance(type.get());
        List<OrderedMethod> descriptors = new ArrayList<>(methods.stream().map(OrderedMethod::new).toList());
        orderer.orderMethods(new MethodOrdererContext() {
            @Override
            public Class<?> getTestClass() {
                return testClass;
            }

            @Override
            public List<? extends MethodDescriptor> getMethodDescriptors() {
                return descriptors;
            }

            @Override
            public Optional<String> getConfigurationParameter(String key) {
                return configuration.get(key);
            }
        });
        return descriptors.stream().map(OrderedMethod::member).toList();
    }

    /** Our implementation of Jupiter's MethodDescriptor, handed to the user's MethodOrderer. */
    private record OrderedMethod(Member member) implements MethodDescriptor {
        @Override
        public Method getMethod() {
            return member.method();
        }

        @Override
        public String getDisplayName() {
            return member.displayName();
        }

        @Override
        public boolean isAnnotated(Class<? extends Annotation> annotationType) {
            return org.junit.platform.commons.support.AnnotationSupport.isAnnotated(member.method(), annotationType);
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
            return org.junit.platform.commons.support.AnnotationSupport.findAnnotation(member.method(), annotationType);
        }

        @Override
        public <A extends Annotation> List<A> findRepeatableAnnotations(Class<A> annotationType) {
            return org.junit.platform.commons.support.AnnotationSupport.findRepeatableAnnotations(member.method(),
                annotationType);
        }
    }

    private static Set<TestTag> tags(AnnotatedElement element) {
        return findRepeatableAnnotations(element, Tag.class).stream().map(Tag::value).filter(TestTag::isValid)
                .map(TestTag::create).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
