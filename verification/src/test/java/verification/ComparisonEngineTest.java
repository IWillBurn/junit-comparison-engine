package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.TestIdentifier;

/** Runs the example classes through the Launcher API like IntelliJ / Surefire and checks what they would show. */
class ComparisonEngineTest {

    // the class node is Jupiter's own [class:...] (in class-template it is a hidden @ClassTemplate)
    static final String MUL = "[engine:comparison-tests]/[class:examples.MulTest]";
    static final String MUL_TEMPLATE = MUL + "/[test-template:mul(int, int, int, examples.Calc)]";

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
        // one CI row per leaf (Surefire uses the legacy reporting name). Here Jupiter builds the name itself
        // (final methods) from the method and int indexes only, so the implementation is its index in value()
        assertEquals(Set.of("mul(int, int, int, Calc)[1][1]", "mul(int, int, int, Calc)[1][2]",
            "mul(int, int, int, Calc)[2][1]", "mul(int, int, int, Calc)[2][2]", "mulFourByFive(Calc)[1]",
            "mulFourByFive(Calc)[2]"), Set.copyOf(run.all().stream().filter(TestIdentifier::isTest)
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
            assertTrue(lines.get(0).matches("@BeforeEach  instance #\\d+, " + leaf.getDisplayName()), lines.get(0));
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
        String failedSet = MUL_TEMPLATE + "/[test-template-invocation:#2]/[implementation:Buggy]";
        String failedTest = MUL + "/[method:mulFourByFive(examples.Calc)]/[implementation:Buggy]";
        run = Run.of(selectUniqueId(failedSet), selectUniqueId(failedTest));
        assertEquals(List.of("MulTest > mul(int, int, int, Calc) > [2] 4, 5, 20 > Buggy",
            "MulTest > mulFourByFive(Calc) > Buggy"), run.leaves());
        assertEquals(Set.of(UniqueId.parse(failedSet), UniqueId.parse(failedTest)), Set.copyOf(run.all().stream()
                .filter(TestIdentifier::isTest).map(TestIdentifier::getUniqueIdObject).toList()), "same ids as before");
    }

    @Test
    void rerunArgumentSet() {
        run = Run.of(selectUniqueId(MUL_TEMPLATE + "/[test-template-invocation:#2]"));
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

    /** The engine's own Jupiter discovery sees the plain classes too; they must not run a second time. */
    @Test
    void plainClassesRunOnce() {
        run = Run.of(selectClass("examples.MulTest"), selectClass("examples.PlainLifecycleTest"));
        assertEquals(3, run.allOutput().split("@BeforeEach", -1).length - 1, run.allOutput());
    }
}
