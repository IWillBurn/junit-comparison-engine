package fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

import examples.Calc;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * @ComparisonTest together with @ParameterizedClass: the constructor receives the class argument and the
 * implementation. Buggy fails only where 4 * 5 is computed (factor 4). Run by ExtraFeaturesTest only.
 */
@ComparisonTest({ Calc.Good.class, Calc.Buggy.class })
@ParameterizedClass
@ValueSource(ints = { 3, 4 })
public class ParameterizedClassFixture {

    private final int factor;
    private final Calc calc;

    public ParameterizedClassFixture(int factor, Calc calc) {
        this.factor = factor;
        this.calc = calc;
    }

    @ParameterizedTest
    @ValueSource(ints = { 5 })
    void times(int x, Calc calc) {
        assertEquals(factor * x, calc.mul(factor, x));
    }

    @Test
    void sameObject(Calc calc) {
        assertEquals(this.calc, calc, "constructor and test method get the same implementation object");
    }
}
