package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectIteration;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

import java.util.List;

import fixtures.MembersFixture;
import fixtures.ParameterizedClassFixture;
import fixtures.PerClassFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** What this variant does beyond the minimal scope (backs the claims of the README). */
class ExtraFeaturesTest {

    static final String E = "[engine:comparison-tests]";

    Run run;

    @AfterEach
    void listenerContract() {
        assertEquals(List.of(), run.violations, "parent started before / finished after its children");
    }

    /** The implementation level composes with @ParameterizedClass; the constructor gets both arguments. */
    @Test
    void parameterizedClass() {
        run = Run.of(selectClass(ParameterizedClassFixture.class));
        assertEquals("""
                ParameterizedClassFixture ✔
                  [1] 3 ✔
                    sameObject(Calc) ✔
                      Good ✔
                      Buggy ✔
                    times(int, Calc) ✔
                      [1] 5 ✔
                        Good ✔
                        Buggy ✔
                  [2] 4 ✔
                    sameObject(Calc) ✔
                      Good ✔
                      Buggy ✔
                    times(int, Calc) ✔
                      [1] 5 ✔
                        Good ✔
                        Buggy ✘ expected: <20> but was: <21>
                """, run.tree(ParameterizedClassFixture.class.getName()));
    }

    /** @RepeatedTest, @Nested and auto-closed arguments work; @TestFactory is a skipped leaf with the reason. */
    @Test
    void members() {
        MembersFixture.EVENTS.clear();
        run = Run.of(selectClass(MembersFixture.class));
        assertEquals("""
                MembersFixture ✔
                  factory() ↷ @TestFactory is not supported in a @ComparisonTest class
                  closeable(Resource, Calc) ✔
                    [1] resource ✔
                      Good ✔
                      Buggy ✔
                  repeated(Calc) ✔
                    repetition 1 of 2 ✔
                      Good ✔
                      Buggy ✔
                    repetition 2 of 2 ✔
                      Good ✔
                      Buggy ✔
                  Inner ✔
                    nested(Calc) ✔
                      Good ✔
                      Buggy ✔
                """, run.tree(MembersFixture.class.getName()));
        // the argument is closed once, after the last implementation of its set; the factory never runs
        assertEquals(List.of("closeable Good", "closeable Buggy", "closed", "repeated Good", "repeated Buggy",
            "repeated Good", "repeated Buggy", "nested Good", "nested Buggy"), MembersFixture.EVENTS);
    }

    /** One instance for all leaves; @BeforeEach and the test get the implementation of their leaf. */
    @Test
    void perClassLifecycle() {
        PerClassFixture.EVENTS.clear();
        run = Run.of(selectClass(PerClassFixture.class));
        assertEquals(2, PerClassFixture.EVENTS.size());
        String instance = PerClassFixture.EVENTS.get(0).split(" ")[0];
        assertEquals(List.of(instance + " Good true", instance + " Buggy true"), PerClassFixture.EVENTS);
    }

    /** Re-run by UniqueId inside a @ParameterizedClass invocation and inside a @Nested class: exactly these leaves. */
    @Test
    void rerunDeepLeaves() {
        String pc = E + "/[class-template:fixtures.ParameterizedClassFixture]";
        run = Run.of(
            selectUniqueId(pc + "/[class-template-invocation:#2]/[test-template:times(int, examples.Calc)]"
                    + "/[test-template-invocation:#1]/[implementation:Buggy]"),
            selectUniqueId(pc + "/[class-template-invocation:#1]/[method:sameObject(examples.Calc)]"
                    + "/[implementation:Good]"),
            selectUniqueId(E + "/[class:fixtures.MembersFixture]/[nested-class:Inner]/[method:nested(examples.Calc)]"
                    + "/[implementation:Buggy]"));
        assertEquals(List.of("ParameterizedClassFixture > [1] 3 > sameObject(Calc) > Good",
            "ParameterizedClassFixture > [2] 4 > times(int, Calc) > [1] 5 > Buggy",
            "MembersFixture > Inner > nested(Calc) > Buggy"), run.leaves());
    }

    /** IterationSelector (index 1 = the second argument set): the set with all implementations. */
    @Test
    void iteration() {
        run = Run.of(selectIteration(selectMethod("examples.MulTest#mul(int, int, int, examples.Calc)"), 1));
        assertEquals(List.of("MulTest > mul(int, int, int, Calc) > [2] 4, 5, 20 > Good",
            "MulTest > mul(int, int, int, Calc) > [2] 4, 5, 20 > Buggy"), run.leaves());
    }

    /** A leaf selector together with a selector of its whole class: the class wins, all leaves run. */
    @Test
    void leafAndItsClass() {
        run = Run.of(selectClass("examples.MulTest"),
            selectUniqueId(ComparisonEngineTest.MUL + "/[method:mulFourByFive(examples.Calc)]/[implementation:Buggy]"));
        assertEquals(6, run.leaves().size(), run.leaves().toString());
    }
}
