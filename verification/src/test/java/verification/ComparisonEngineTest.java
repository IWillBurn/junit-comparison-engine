package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import examples.Calc;
import examples.CalcSource;
import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonTest;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.params.AfterParameterizedClassInvocation;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.TestIdentifier;

/** Runs the example classes through the Launcher API like IntelliJ / Surefire and checks what they would show. */
class ComparisonEngineTest {

    static final String MUL = "[engine:comparison-tests]/[class:examples.MulTest]";
    static final String MUL_METHOD = MUL + "/[method:mul(int, int, int, examples.Calc)]";

    Run run;

    @AfterEach
    void listenerContract() {
        assertEquals(List.of(), run.violations, "parent started before / finished after its children");
    }

    @Test
    void mulTestTree() {
        run = Run.of(selectClass("examples.MulTest"));
        assertEquals("""
                MulTest ✔
                  mul(int, int, int, Calc) ✔
                    [1] 2, 3, 6 ✔
                      Good ✔
                      Buggy ✔
                    [2] 4, 5, 20 ✔
                      Good ✔
                      Buggy ✘ expected: <20> but was: <21>
                  mulFourByFive(Calc) ✔
                    Good ✔
                    Buggy ✘ expected: <20> but was: <21>
                """, run.tree("examples.MulTest"));
        // one CI row per leaf (Surefire uses the legacy reporting name)
        assertEquals(Set.of("mul(int, int, int, Calc)[1][GOOD]", "mul(int, int, int, Calc)[1][BUGGY]",
            "mul(int, int, int, Calc)[2][GOOD]", "mul(int, int, int, Calc)[2][BUGGY]", "mulFourByFive(Calc)[GOOD]",
            "mulFourByFive(Calc)[BUGGY]"), Set.copyOf(run.all().stream().filter(TestIdentifier::isTest)
                    .map(TestIdentifier::getLegacyReportingName).toList()));
    }

    @Test
    void lifecycle() {
        run = Run.of(selectClass("examples.LifecycleTest"));
        String all = run.allOutput();
        assertEquals(1, all.split("@BeforeAll", -1).length - 1, "@BeforeAll once for all implementations");
        assertEquals(6, all.split("@BeforeEach", -1).length - 1, "@BeforeEach once per leaf");
        Set<String> instances = new HashSet<>();
        for (TestIdentifier leaf : run.all().stream().filter(TestIdentifier::isTest).toList()) {
            List<String> lines = run.output(leaf).lines().toList(); // @BeforeEach, test, @AfterEach of THIS leaf
            assertEquals(3, lines.size(), run.path(leaf) + " printed " + lines);
            assertTrue(lines.get(0).matches("@BeforeEach {2}instance #\\d+, " + leaf.getDisplayName()), lines.get(0));
            assertEquals(lines.get(0).replaceAll(".*(#\\d+).*", "@AfterEach   instance $1"), lines.get(2));
            instances.add(lines.get(0));
        }
        assertEquals(6, instances.size(), "a new test instance per leaf");
    }

    /** IDE gutter icon of a method: MethodSelector. */
    @Test
    void runOneMethod() {
        run = Run.of(selectMethod("examples.MulTest#mul(int, int, int, examples.Calc)"));
        assertEquals(List.of("MulTest > mul(int, int, int, Calc) > [1] 2, 3, 6 > Good",
            "MulTest > mul(int, int, int, Calc) > [1] 2, 3, 6 > Buggy",
            "MulTest > mul(int, int, int, Calc) > [2] 4, 5, 20 > Good",
            "MulTest > mul(int, int, int, Calc) > [2] 4, 5, 20 > Buggy"), run.leaves());
    }

    /** IntelliJ's "Rerun Failed Tests": the UniqueIds of the failed leaves. */
    @Test
    void rerunLeaves() {
        String failedSet = MUL_METHOD + "/[argument-set:#2]/[implementation:BUGGY]";
        String failedTest = MUL + "/[method:mulFourByFive(examples.Calc)]/[implementation:BUGGY]";
        run = Run.of(selectUniqueId(failedSet), selectUniqueId(failedTest));
        assertEquals(List.of("MulTest > mul(int, int, int, Calc) > [2] 4, 5, 20 > Buggy",
            "MulTest > mulFourByFive(Calc) > Buggy"), run.leaves());
        assertEquals(Set.of(UniqueId.parse(failedSet), UniqueId.parse(failedTest)), Set.copyOf(run.all().stream()
                .filter(TestIdentifier::isTest).map(TestIdentifier::getUniqueIdObject).toList()), "same ids as before");
    }

    @Test
    void rerunDifferentImplementationsAcrossArgumentSets() {
        RerunFixture.events.clear();
        String method = classId(RerunFixture.class) + "/[method:calc(int, examples.Calc)]";
        run = Run.of(selectUniqueId(method + "/[argument-set:#1]/[implementation:GOOD]"),
            selectUniqueId(method + "/[argument-set:#2]/[implementation:BUGGY]"),
            selectUniqueId(method + "/[argument-set:#3]/[implementation:GOOD]"));

        assertEquals(List.of("RerunFixture > calc(int, Calc) > [1] 1 > Good",
            "RerunFixture > calc(int, Calc) > [2] 2 > Buggy", "RerunFixture > calc(int, Calc) > [3] 3 > Good"),
            run.leaves());
        assertEquals(List.of("beforeAll", "source", "new Good", "before Good", "1 Good", "after Good",
            "new Buggy", "before Buggy", "2 Buggy", "after Buggy",
            "new Good", "before Good", "3 Good", "after Good", "afterAll"), RerunFixture.events);
        assertTrue(run.tree(RerunFixture.class.getName()).contains("Buggy ✘ intentional failure"));
    }

    @Test
    void rerunLeafInOneClassInvocation() {
        RerunFixture.events.clear();
        String leaf = classId(TemplateFixture.class)
                + "/[class-invocation:#2]/[method:calc(int, examples.Calc)]/[argument-set:#1]/[implementation:GOOD]";
        run = Run.of(selectUniqueId(leaf));

        assertEquals(List.of("TemplateFixture > [2] value = 8 > calc(int, Calc) > [1] 1 > Good"), run.leaves());
        assertEquals(List.of("beforeAll", "beforeInvocation 8", "source", "new Good", "before Good", "1 Good",
            "after Good", "afterInvocation 8", "afterAll"), RerunFixture.events);
    }

    @Test
    void rerunPlainLeafInOneClassInvocation() {
        RerunFixture.events.clear();
        String leaf = classId(TemplateFixture.class)
                + "/[class-invocation:#2]/[method:plain(examples.Calc)]/[implementation:GOOD]";
        run = Run.of(selectUniqueId(leaf));

        assertEquals(List.of("TemplateFixture > [2] value = 8 > plain(Calc) > Good"), run.leaves());
        assertEquals(List.of("beforeAll", "beforeInvocation 8", "new Good", "before Good", "plain Good",
            "after Good", "afterInvocation 8", "afterAll"), RerunFixture.events);
    }

    @Test
    void rerunAcrossAllAndSpecificClassInvocations() {
        RerunFixture.events.clear();
        String classId = classId(TemplateFixture.class);
        String method = "/[method:calc(int, examples.Calc)]";
        run = Run.of(selectUniqueId(classId + method + "/[argument-set:#1]/[implementation:GOOD]"),
            selectUniqueId(classId + "/[class-invocation:#2]" + method + "/[argument-set:#3]/[implementation:GOOD]"));

        assertEquals(List.of("TemplateFixture > [1] value = 7 > calc(int, Calc) > [1] 1 > Good",
            "TemplateFixture > [2] value = 8 > calc(int, Calc) > [1] 1 > Good",
            "TemplateFixture > [2] value = 8 > calc(int, Calc) > [3] 3 > Good"), run.leaves());
    }

    private static String classId(Class<?> type) {
        return "[engine:comparison-tests]/[class:" + type.getName() + "]";
    }

    @ComparisonTest(source = CalcSource.class)
    @DisplayName("RerunFixture")
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class RerunFixture {

        static final List<String> events = new ArrayList<>();
        private final Calc implementation;

        RerunFixture(Calc implementation) {
            this.implementation = implementation;
            events.add("new " + implementation.getClass().getSimpleName());
        }

        @BeforeAll
        static void beforeAll() {
            events.add("beforeAll");
        }

        @AfterAll
        static void afterAll() {
            events.add("afterAll");
        }

        @BeforeEach
        void before(Calc subject) {
            assertSame(implementation, subject);
            events.add("before " + subject.getClass().getSimpleName());
        }

        @AfterEach
        void after(Calc subject) {
            assertSame(implementation, subject);
            events.add("after " + subject.getClass().getSimpleName());
        }

        @Test
        void plain(Calc subject) {
            assertSame(implementation, subject);
            events.add("plain " + subject.getClass().getSimpleName());
        }

        static IntStream values() {
            events.add("source");
            return IntStream.of(1, 2, 3);
        }

        @ParameterizedTest
        @ComparisonArguments(1)
        @MethodSource("values")
        void calc(int value, Calc subject) {
            assertSame(implementation, subject);
            events.add(value + " " + subject.getClass().getSimpleName());
            if (subject instanceof Calc.Buggy) {
                throw new AssertionError("intentional failure");
            }
        }
    }

    @ParameterizedClass
    @ValueSource(ints = {7, 8})
    @DisplayName("TemplateFixture")
    @SuppressWarnings("NewClassNamingConvention")
    static class TemplateFixture extends RerunFixture {

        @Parameter
        @SuppressWarnings("unused") // Bound by Jupiter for the invocation callbacks.
        int value;

        TemplateFixture(Calc implementation) {
            super(implementation);
        }

        @BeforeParameterizedClassInvocation
        static void beforeInvocation(int value) {
            events.add("beforeInvocation " + value);
        }

        @AfterParameterizedClassInvocation
        static void afterInvocation(int value) {
            events.add("afterInvocation " + value);
        }
    }

    @Test
    void executionAnnotationsApplyToEachImplementation() {
        LeafAnnotations.SOURCE_CALLS.set(0);
        run = Run.of(selectClass(LeafAnnotations.class));

        String tree = run.tree(LeafAnnotations.class.getName());
        assertEquals(1, LeafAnnotations.SOURCE_CALLS.get(), tree);
        assertEquals(7, run.leaves().size(), tree);
        assertEquals(7, tree.lines().filter(line -> line.matches(".*(?:Good|Buggy) ✔")).count(), tree);
        assertTrue(tree.contains("Buggy ↷ Buggy excluded"), tree);
    }

    @ComparisonTest(source = CalcSource.class)
    @DisplayName("LeafAnnotations")
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class LeafAnnotations {

        static final AtomicInteger SOURCE_CALLS = new AtomicInteger();

        static IntStream cases() {
            SOURCE_CALLS.incrementAndGet();
            return IntStream.of(2, 3);
        }

        @Test
        @Timeout(5)
        @ExtendWith(LeafExtension.class)
        void plain(Calc calc, String leaf) {
            assertEquals(calc.getClass().getSimpleName(), leaf);
        }

        @ParameterizedTest
        @ComparisonArguments(1)
        @MethodSource("cases")
        @Timeout(5)
        @ExtendWith(LeafExtension.class)
        void parameterized(int value, Calc calc, String leaf) {
            assertEquals(calc.getClass().getSimpleName(), leaf);
            assertTrue(value >= 2);
        }

        @Test
        @DisabledIf(value = "isBuggy", disabledReason = "Buggy excluded")
        void conditional(Calc calc) {
            assertEquals(Calc.Good.class, calc.getClass());
        }

        static boolean isBuggy(ExtensionContext context) {
            return context.getDisplayName().equals("Buggy");
        }
    }

    @NullMarked
    public static class LeafExtension implements BeforeAllCallback, ParameterResolver {

        @Override
        public void beforeAll(ExtensionContext context) {
            throw new AssertionError("a method-level extension must not run beforeAll on the carrier");
        }

        @Override
        public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
            return parameter.getParameter().getType() == String.class;
        }

        @Override
        public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
            assertTrue(context.getRequiredTestMethod().isAnnotationPresent(Timeout.class));
            return context.getDisplayName();
        }
    }

    @Test
    void parameterizedMethodWithTypeVariable() {
        run = Run.of(selectClass(GenericArgument.class));
        assertEquals(List.of("GenericArgument > generic(CharSequence, Calc) > [1] \"hello\" > Good",
            "GenericArgument > generic(CharSequence, Calc) > [1] \"hello\" > Buggy"), run.leaves());
        String tree = run.tree(GenericArgument.class.getName());
        assertTrue(tree.contains("Good ✔") && tree.contains("Buggy ✔"), tree);
    }

    @ComparisonTest(source = CalcSource.class)
    @DisplayName("GenericArgument")
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class GenericArgument {

        @ParameterizedTest
        @ComparisonArguments(1)
        @ValueSource(strings = "hello")
        <T extends CharSequence> void generic(T arg, Calc implementation) {
            assertEquals("hello", arg);
        }
    }

    @Test
    void rerunArgumentSet() {
        run = Run.of(selectUniqueId(MUL_METHOD + "/[argument-set:#2]"));
        assertEquals(List.of("MulTest > mul(int, int, int, Calc) > [2] 4, 5, 20 > Good",
            "MulTest > mul(int, int, int, Calc) > [2] 4, 5, 20 > Buggy"), run.leaves());
    }

    @Test
    void outerJupiterDoesNotRunComparisonClassesAgain() {
        run = Run.of(selectClass("examples.MulTest"), selectClass("examples.PlainLifecycleTest"));
        TestIdentifier jupiter = run.plan().getTestIdentifier(UniqueId.forEngine("junit-jupiter"));
        assertEquals(List.of("PlainLifecycleTest"),
            run.plan().getChildren(jupiter).stream().map(TestIdentifier::getDisplayName).toList());
        assertEquals(6, run.leaves().stream().filter(p -> p.startsWith("MulTest > ")).count());
        assertEquals(3, run.leaves().stream().filter(p -> p.startsWith("PlainLifecycleTest > ")).count());
    }

    /** The nested Jupiter run must not run the plain classes of the same request a second time. */
    @Test
    void plainClassesRunOnce() {
        run = Run.of(selectClass("examples.MulTest"), selectClass("examples.PlainLifecycleTest"));
        assertEquals(3, run.allOutput().split("@BeforeEach", -1).length - 1, run.allOutput());
    }
}
