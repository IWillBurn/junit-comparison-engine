package fixtures;

import examples.Calc;
import examples.CalcVariant;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.Test;

/** Cannot run: the engine needs a subclass. Run by PlatformContractTest only. */
@ComparisonTest(classes = CalcVariant.class)
@SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
public final class FinalFixture {

    @Test
    void test(Calc calc) {
        throw new AssertionError("a final comparison class cannot be executed");
    }
}
