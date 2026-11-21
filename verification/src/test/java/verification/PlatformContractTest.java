package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;
import static org.junit.platform.engine.discovery.PackageNameFilter.excludePackageNames;

import java.util.List;
import java.util.Set;

import fixtures.FinalFixture;
import fixtures.TaggedFixture;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.launcher.TagFilter;

/** How the engine keeps the rules of the JUnit Platform: filters, unknown UniqueIds, cancellation, a broken class. */
class PlatformContractTest {

    /** A TagFilter removes nodes after discovery; the execution must not bring them back. */
    @Test
    void tagFilter() {
        Run run = Run.of(request -> request.selectors(selectClass(TaggedFixture.class))
                .filters(TagFilter.includeTags("fast")));
        assertEquals(List.of(), run.violations);
        assertEquals(Set.of("TaggedFixture > fast(Calc) > Good", "TaggedFixture > fast(Calc) > Buggy",
            "TaggedFixture > parameterized(int, Calc) > [1] 1 > Good",
            "TaggedFixture > parameterized(int, Calc) > [1] 1 > Buggy"), Set.copyOf(run.leaves()));
    }

    /** The class name and package name filters apply to the classes found in a package, as in Jupiter. */
    @Test
    void packageNameFilter() {
        assertTrue(Run.of(selectPackage("examples")).leaves().contains("MulTest > mulFourByFive(Calc) > Buggy"));
        Run filtered = Run.of(request -> request.selectors(selectPackage("examples"))
                .filters(excludePackageNames("examples")));
        assertEquals(List.of(), filtered.leaves());
    }

    /** A UniqueId the engine cannot resolve (here of a renamed method) is a discovery error, as in Jupiter. */
    @Test
    void unknownUniqueId() {
        String renamed = "[engine:comparison-tests]/[class:examples.MulTest]/[method:renamed(examples.Calc)]";
        JUnitException error = assertThrows(JUnitException.class, () -> Run.of(selectUniqueId(renamed)));
        assertTrue(error.getMessage().contains("could not be resolved"), error.getMessage());
    }

    @Test
    void malformedUniqueIds() {
        String method = ComparisonEngineTest.MUL_METHOD;
        for (String malformed : List.of(
                method.replace("[method:", "[nested-class:"),
                method + "/[implementation:Good]",
                method + "/[argument-set:#01]",
                method + "/[argument-set:1]")) {
            JUnitException error = assertThrows(JUnitException.class, () -> Run.of(selectUniqueId(malformed)),
                malformed);
            assertTrue(error.getMessage().contains("could not be resolved"), error.getMessage());
        }
    }

    /** A cancelled run (fail-fast) skips what the nested Jupiter has not started yet. */
    @Test
    void cancellation() {
        Run run = Run.cancelledAfterFirstTest(selectClass("examples.MulTest"));
        assertEquals(List.of(), run.violations);
        assertEquals(List.of("MulTest > mul(int, int, int, Calc) > [1] 2, 3, 6 > Good"), run.leaves());
    }

    /** A class that cannot run fails alone, with the reason; the other classes run. */
    @Test
    void invalidClassFailsAlone() {
        Run run = Run.of(selectClass(FinalFixture.class), selectClass("examples.MulTest"));
        assertEquals(List.of(), run.violations);
        assertEquals("FinalFixture ✘ @ComparisonTest fixtures.FinalFixture: the class must not be final: the engine"
                + " runs it through a generated subclass\n", run.tree(FinalFixture.class.getName()));
        assertEquals(6, run.leaves().stream().filter(leaf -> leaf.startsWith("MulTest > ")).count());
    }
}
