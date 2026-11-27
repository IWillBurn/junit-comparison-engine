package org.atpfivt.comparison.engine;

import static org.junit.platform.commons.support.AnnotationSupport.findAnnotation;
import static org.junit.platform.commons.support.AnnotationSupport.isAnnotated;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.api.Variant;
import org.atpfivt.comparison.engine.Position.MemberId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.ClassTemplate;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/** A comparison class in Jupiter's display order, or an invalid class with a discovery problem. */
record ComparisonClass(Class<?> testClass, String displayName, Set<TestTag> tags, boolean classTemplate,
        @Nullable Class<? extends ComparisonSource<?>> sourceType, List<Implementation> implementations,
        List<Member> members, @Nullable String problem) {

    /** One variant with its enum type and reporting name. */
    record Implementation(Variant variant, Class<? extends Variant> variantType, String displayName) {

        String name() {
            return variant.name();
        }
    }

    /** A member of the class that gets a node: a test method, or something the engine cannot run. */
    sealed interface Member permits TestMethod, UnsupportedMember {

        MemberId id();

        String displayName();

        Set<TestTag> tags();

        TestSource source();
    }

    /**
     * A {@code @Test} or {@code @ParameterizedTest} method, run on every implementation.
     *
     * @param argumentCount the number of leading parameters populated by a parameterized argument source
     * @param skips variant name → reason for the variants the method skips
     */
    record TestMethod(Method method, String displayName, Set<TestTag> tags, TestSource source, boolean parameterized,
            int argumentCount, Map<String, String> skips) implements Member {

        @Override
        public MemberId id() {
            return methodId(method);
        }

        String legacyName() {
            return legacyMethodName(method);
        }
    }

    /** A member shown as a skipped leaf with the reason: an unsupported kind of test or an invalid test method. */
    record UnsupportedMember(MemberId id, String displayName, String legacyName, Set<TestTag> tags,
            TestSource source, String reason) implements Member {
    }

    ComparisonClass {
        implementations = List.copyOf(implementations);
        members = List.copyOf(members);
    }

    /** A class the engine runs: annotated, instantiable as a subclass, not an inner class. */
    static boolean isCandidate(Class<?> type) {
        int modifiers = type.getModifiers();
        return !type.isInterface() && !Modifier.isAbstract(modifiers) && !Modifier.isPrivate(modifiers)
                && !type.isAnonymousClass() && !type.isLocalClass()
                && (type.getEnclosingClass() == null || Modifier.isStatic(modifiers))
                && isAnnotated(type, ComparisonTest.class);
    }

    /** Analyzes a candidate class. Never throws: a class that cannot run is returned with its {@link #problem}. */
    static ComparisonClass analyze(Class<?> testClass, ConfigurationParameters configuration) {
        boolean classTemplate = false;
        Set<TestTag> tags = Set.of();
        String displayName = testClass.getSimpleName();
        try {
            classTemplate = isAnnotated(testClass, ClassTemplate.class);
            tags = JupiterConventions.tags(testClass);
            // user code runs here (a DisplayNameGenerator, a MethodOrderer): a failure fails this class only
            DisplayNameGenerator generator = JupiterConventions.displayNameGenerator(testClass, configuration);
            displayName = JupiterConventions.displayName(testClass, generator);
            if (Modifier.isFinal(testClass.getModifiers())) {
                return invalid(testClass, displayName, tags, classTemplate,
                    "the class must not be final: the engine runs it through a generated subclass");
            }
            var source = findAnnotation(testClass, ComparisonTest.class).orElseThrow().source();
            List<Implementation> implementations = implementations(source);
            validateVariantNames(implementations);
            List<Member> members = members(testClass, generator, configuration,
                implementations.isEmpty() ? null : implementations.getFirst().variantType());
            return new ComparisonClass(testClass, displayName, tags, classTemplate, source, implementations, members,
                null);
        }
        catch (RuntimeException | LinkageError e) {
            return invalid(testClass, displayName, tags, classTemplate, "cannot analyze the class: " + e);
        }
    }

    boolean isValid() {
        return problem == null;
    }

    private Optional<Member> member(MemberId id) {
        return members.stream().filter(member -> member.id().equals(id)).findFirst();
    }

    List<TestMethod> testMethods() {
        return members.stream().filter(TestMethod.class::isInstance).map(TestMethod.class::cast).toList();
    }

    /**
     * Whether the tree of this class has a node at the selected position: a UniqueId or selector pointing elsewhere
     * selects nothing. Whatever is selected in an invalid class is accepted: its class node reports the problem.
     */
    boolean contains(Position position) {
        if (!isValid()) {
            return true;
        }
        if (position.classInvocation() != null && !classTemplate) {
            return false;
        }
        MemberId memberId = position.member();
        if (memberId == null) {
            return position.argumentSet() == null && position.implementation() == null;
        }
        return member(memberId).filter(member -> switch (member) {
            case UnsupportedMember ignored -> position.equals(Position.CLASS.withMember(memberId));
            case TestMethod method -> validArgumentSet(position, method)
                    && (position.implementation() == null || hasImplementation(position.implementation()));
        }).isPresent();
    }

    private static boolean validArgumentSet(Position position, TestMethod method) {
        return position.argumentSet() != null ? method.parameterized()
                : position.implementation() == null || !method.parameterized();
    }

    static MemberId methodId(Method method) {
        return new MemberId(Segment.METHOD, methodSignature(method, Class::getTypeName));
    }

    /** The legacy reporting name of a method: {@code name(SimpleParameterTypes)}. */
    private static String legacyMethodName(Method method) {
        return methodSignature(method, Class::getSimpleName);
    }

    private static String methodSignature(Method method, Function<Class<?>, String> parameterName) {
        return method.getName() + Arrays.stream(method.getParameterTypes()).map(parameterName)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private boolean hasImplementation(String name) {
        return implementations.stream().anyMatch(implementation -> implementation.name().equals(name));
    }

    private static ComparisonClass invalid(Class<?> testClass, String displayName, Set<TestTag> tags,
            boolean classTemplate, String problem) {
        return new ComparisonClass(testClass, displayName, tags, classTemplate, null, List.of(), List.of(), problem);
    }

    private static List<Implementation> implementations(Class<? extends ComparisonSource<?>> sourceType) {
        ComparisonSource<?> source = ReflectionSupport.newInstance(sourceType);
        var variants = List.copyOf(source.variants());
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("the comparison source lists no variants");
        }
        return variants.stream().map(variant -> new Implementation(variant,
            variant.getDeclaringClass().asSubclass(Variant.class),
            Objects.requireNonNull(variant.testName(), "variant display name"))).toList();
    }

    private static void validateVariantNames(List<Implementation> implementations) {
        Set<String> names = new HashSet<>();
        for (var implementation : implementations) {
            String name = implementation.name();
            if (!names.add(name)) {
                throw new IllegalArgumentException("duplicate comparison variant name: " + name);
            }
        }
    }

    /** The test methods in Jupiter's order, then the {@code @Nested} classes (unsupported). */
    private static List<Member> members(Class<?> testClass, DisplayNameGenerator generator,
            ConfigurationParameters configuration, @Nullable Class<? extends Variant> variantType) {
        List<Method> methods = ReflectionSupport.findMethods(testClass, ComparisonClass::isTestable,
            HierarchyTraversalMode.TOP_DOWN);
        List<Member> members = new ArrayList<>();
        for (var method : JupiterConventions.orderedMethods(testClass, methods, generator, configuration)) {
            members.add(member(testClass, method.getMethod(), method.getDisplayName(), variantType));
        }
        for (Class<?> nested : ReflectionSupport.findNestedClasses(testClass, c -> isAnnotated(c, Nested.class))) {
            String name = nested.getSimpleName();
            members.add(new UnsupportedMember(new MemberId(Segment.NESTED_CLASS, name), name, nested.getName(),
                JupiterConventions.tags(nested), ClassSource.from(nested),
                "@Nested classes are not supported in a @ComparisonTest class"));
        }
        return members;
    }

    private static boolean isTestable(Method method) {
        return isAnnotated(method, Test.class) || isAnnotated(method, TestTemplate.class)
                || isAnnotated(method, TestFactory.class);
    }

    private static Member member(Class<?> testClass, Method method, String displayName,
            @Nullable Class<? extends Variant> variantType) {
        Set<TestTag> tags = JupiterConventions.tags(method);
        TestSource source = MethodSource.from(testClass, method);
        boolean parameterized = isAnnotated(method, ParameterizedTest.class);
        int argumentCount = parameterized
                ? findAnnotation(method, ComparisonArguments.class).map(ComparisonArguments::value).orElse(-1) : 0;
        String reason = whyUnsupported(method, parameterized, argumentCount);
        if (reason != null) {
            return new UnsupportedMember(methodId(method), displayName, legacyMethodName(method),
                tags, source, reason);
        }
        Map<String, String> skips;
        try {
            skips = SkipAnnotations.read(method, variantType);
        }
        catch (IllegalArgumentException e) {
            return new UnsupportedMember(methodId(method), displayName, legacyMethodName(method),
                tags, source, e.getMessage());
        }
        return new TestMethod(method, displayName, tags, source, parameterized, argumentCount, skips);
    }

    private static @Nullable String whyUnsupported(Method method, boolean parameterized, int argumentCount) {
        boolean test = isAnnotated(method, Test.class);
        if (test && parameterized) {
            return "both @Test and @ParameterizedTest";
        }
        if (!test && !parameterized) {
            String kind = isAnnotated(method, TestFactory.class) ? "TestFactory"
                    : isAnnotated(method, RepeatedTest.class) ? "RepeatedTest" : "TestTemplate";
            return "@" + kind + " is not supported in a @ComparisonTest class";
        }
        if (Modifier.isStatic(method.getModifiers()) || Modifier.isPrivate(method.getModifiers())
                || method.getReturnType() != void.class) {
            return "a test method must not be static or private and must return void";
        }
        if (parameterized && (argumentCount < 0 || argumentCount > method.getParameterCount())) {
            return "declare @ComparisonArguments with the number of leading arguments supplied by the parameterized source";
        }
        return null;
    }
}
