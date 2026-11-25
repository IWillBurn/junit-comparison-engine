package fixtures;

import examples.Calc;
import examples.CalcSource;
import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tags on methods, for a TagFilter. Run by PlatformContractTest only. */
@ComparisonTest(source = CalcSource.class)
@SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention", "EmptyMethod"})
public class TaggedFixture {

    @Test
    @Tag("fast")
    void fast(Calc calc) {
    }

    @Test
    void untagged(Calc calc) {
    }

    @ParameterizedTest
    @ComparisonArguments(1)
    @ValueSource(ints = 1)
    @Tag("fast")
    void parameterized(int x, Calc calc) {
    }
}
