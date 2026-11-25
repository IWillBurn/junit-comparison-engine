package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import examples.Calc;
import examples.CalcSource;
import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonTest;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AnnotationCompatibilityTest {

    @Test
    void composedTestKeepsItsExtensionAndResolvesOnlyTheImplementationParameter() {
        ComposedTestFixture.events.clear();
        Run run = Run.of(selectClass(ComposedTestFixture.class));

        assertPassed(run, ComposedTestFixture.class, 2);
        assertEquals(List.of("Good", "Buggy"), ComposedTestFixture.events);
    }

    @Test
    void composedParameterizedTestResolvesFactoryParametersAndIndexedObjectArguments() {
        FactoryFixture.events.clear();
        Run run = Run.of(selectClass(FactoryFixture.class));

        assertPassed(run, FactoryFixture.class, 2);
        assertEquals(List.of("source", "before Good", "before Buggy"), FactoryFixture.events);
    }

    @Test
    void classArgumentsAndImplementationCanShareAnAssignableConstructorType() {
        Run run = Run.of(selectClass(ClassArgumentsFixture.class));

        assertPassed(run, ClassArgumentsFixture.class, 2);
    }

    @Test
    void composedLocalSourcesAreResolvedAgainstTheUserClass() {
        Run run = Run.of(selectClass(LocalSourceFixture.class));

        assertPassed(run, LocalSourceFixture.class, 4);
    }

    @Test
    void instanceConditionsUseTheOuterInstanceForEachImplementation() {
        Run run = Run.of(selectClass(ConditionFixture.class));

        assertPassed(run, ConditionFixture.class, 2);
        String tree = run.tree(ConditionFixture.class.getName());
        assertTrue(tree.contains("Buggy ↷ instance enabled check"), tree);
        assertTrue(tree.contains("Buggy ↷ instance disabled check"), tree);
    }

    @Test
    void instanceConditionMustDeclareAPrimitiveBooleanResult() {
        Run run = Run.of(selectClass(InvalidConditionFixture.class));

        String tree = run.tree(InvalidConditionFixture.class.getName());
        assertEquals(2, run.leaves().size(), tree);
        assertEquals(2, tree.lines().filter(line -> line.contains("must return a boolean")).count(), tree);
        assertEquals(List.of(), run.violations);
    }

    @Test
    void disabledParameterizedLeavesAreSkippedAfterReadingSources() {
        Run run = Run.of(selectClass(DisabledFixture.class));

        String tree = run.tree(DisabledFixture.class.getName());
        assertEquals(List.of(), run.leaves(), tree);
        assertTrue(tree.contains("Good ↷ skipped leaf"), tree);
        assertTrue(tree.contains("Buggy ↷ skipped leaf"), tree);
        assertTrue(tree.contains("✘ failed source"), tree);
        assertEquals(List.of(), run.violations);
    }

    private static void assertPassed(Run run, Class<?> fixture, long expectedLeaves) {
        String tree = run.tree(fixture.getName());
        assertEquals(expectedLeaves, run.leaves().size(), tree);
        assertEquals(expectedLeaves, tree.lines().filter(line -> line.matches(".*(?:Good|Buggy) ✔")).count(), tree);
        assertEquals(List.of(), run.violations);
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @Test
    @ExtendWith(ComposedExtension.class)
    @interface ComposedTest {
    }

    @ComparisonTest(source = CalcSource.class)
    @DisplayName("ComposedTestFixture")
    @SuppressWarnings("NewClassNamingConvention")
    static class ComposedTestFixture {

        static final List<String> events = new ArrayList<>();

        @ComposedTest
        void compare(Object input, Calc implementation) {
            assertEquals("input", input);
        }
    }

    @NullMarked
    public static class ComposedExtension implements BeforeEachCallback, ParameterResolver {

        @Override
        public void beforeEach(ExtensionContext context) {
            assertTrue(context.getRequiredTestMethod().isAnnotationPresent(ComposedTest.class));
            ComposedTestFixture.events.add(context.getDisplayName());
        }

        @Override
        public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
            return parameter.getParameter().getType() == Object.class;
        }

        @Override
        public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
            return "input";
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @ParameterizedTest
    @MethodSource("cases")
    @ExtendWith(FactoryExtension.class)
    @interface ComposedParameterizedTest {
    }

    @ComparisonTest(source = CalcSource.class)
    @DisplayName("FactoryFixture")
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class FactoryFixture {

        static final List<String> events = new ArrayList<>();

        static Stream<Object> cases(String input) {
            events.add("source");
            return Stream.of(input);
        }

        @ComposedParameterizedTest
        @ComparisonArguments(1)
        void compare(Object input, Calc implementation) {
            assertEquals("source input", input);
        }
    }

    @NullMarked
    public static class FactoryExtension implements ParameterResolver, BeforeEachCallback, BeforeAllCallback {

        @Override
        public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
            return parameter.getDeclaringExecutable().getName().equals("cases")
                    && context.getRequiredTestMethod().getName().equals("compare");
        }

        @Override
        public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
            assertEquals(FactoryFixture.class, context.getRequiredTestClass());
            return "source input";
        }

        @Override
        public void beforeEach(ExtensionContext context) {
            FactoryFixture.events.add("before " + context.getDisplayName());
        }

        @Override
        public void beforeAll(ExtensionContext context) {
            throw new AssertionError("source parameter resolution must not register lifecycle callbacks on the class");
        }
    }

    @ComparisonTest(source = CalcSource.class)
    @ParameterizedClass
    @ValueSource(strings = "class input")
    @DisplayName("ClassArgumentsFixture")
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class ClassArgumentsFixture {

        private final Calc implementation;

        ClassArgumentsFixture(Object input, Calc implementation) {
            assertEquals("class input", input);
            this.implementation = implementation;
        }

        @Test
        void compare(Calc subject) {
            assertSame(implementation, subject);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @MethodSource("localCases")
    @interface LocalSource {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @FieldSource("LOCAL_VALUES")
    @interface LocalFieldSource {
    }

    @ComparisonTest(source = CalcSource.class)
    @DisplayName("LocalSourceFixture")
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class LocalSourceFixture {

        @SuppressWarnings("unused") // Read by the composed @FieldSource.
        static final List<String> LOCAL_VALUES = List.of("field");

        static Stream<String> localCases() {
            return Stream.of("local");
        }

        @ParameterizedTest
        @ComparisonArguments(1)
        @LocalSource
        void compare(String input, Calc implementation) {
            assertEquals("local", input);
        }

        @ParameterizedTest
        @ComparisonArguments(1)
        @LocalFieldSource
        void compareField(String input, Calc implementation) {
            assertEquals("field", input);
        }
    }

    @ComparisonTest(source = CalcSource.class)
    @DisplayName("DisabledFixture")
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class DisabledFixture {

        @ParameterizedTest
        @ComparisonArguments(1)
        @Disabled("skipped leaf")
        @ValueSource(strings = "value")
        void skip(String input, Calc implementation) {
            throw new AssertionError("disabled test body");
        }

        @ParameterizedTest
        @ComparisonArguments(1)
        @Disabled
        @MethodSource("failedSource")
        void failsAtSource(String input, Calc implementation) {
            throw new AssertionError("disabled test body");
        }

        static Stream<String> failedSource() {
            throw new IllegalStateException("failed source");
        }
    }

    @ComparisonTest(source = CalcSource.class)
    @DisplayName("InvalidConditionFixture")
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class InvalidConditionFixture {

        @Test
        @EnabledIf("invalid")
        void compare(Calc implementation) {
            throw new AssertionError("test with an invalid condition");
        }

        private Boolean invalid() {
            throw new AssertionError("condition with an invalid return type");
        }
    }

    @ComparisonTest(source = CalcSource.class)
    @DisplayName("ConditionFixture")
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention", "ClassCanBeRecord"})
    static class ConditionFixture {

        private final Calc implementation;

        ConditionFixture(Calc implementation) {
            this.implementation = implementation;
        }

        @Test
        @EnabledIf(value = "good", disabledReason = "instance enabled check")
        void enabled(Calc subject) {
            assertInstanceOf(Calc.Good.class, subject);
        }

        @Test
        @DisabledIf(value = "buggy", disabledReason = "instance disabled check")
        void disabled(Calc subject) {
            assertInstanceOf(Calc.Good.class, subject);
        }

        private boolean good(ExtensionContext context) {
            return implementation instanceof Calc.Good && context.getDisplayName().equals("Good");
        }

        private boolean buggy() {
            return implementation instanceof Calc.Buggy;
        }
    }
}
