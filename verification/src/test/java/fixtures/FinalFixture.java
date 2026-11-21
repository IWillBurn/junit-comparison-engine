package fixtures;

import examples.Calc;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.Test;

/** Cannot run: the engine needs a subclass. Run by PlatformContractTest only. */
@ComparisonTest(classes = { Calc.Good.class, Calc.Buggy.class })
@SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
public final class FinalFixture {

    @Test
    void test(Calc calc) {
        throw new AssertionError("a final comparison class cannot be executed");
    }
}
