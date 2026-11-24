/*
 * The main example: @ComparisonTest on the class, the methods are plain @Test and @ParameterizedTest, the
 * implementation comes as the last parameter (Calc calc). Buggy is wrong on 4 * 5. Every argument set gets one leaf
 * per implementation; any node can be re-run from the results tree.
 *
 * Tree (actual run, ConsoleLauncher --details=tree):
 *
 * MulTest ✔
 * ├─ mul(int, int, int, Calc) ✔
 * │  ├─ [1] 2, 3, 6 ✔
 * │  │  ├─ Good ✔
 * │  │  └─ Buggy ✔
 * │  └─ [2] 4, 5, 20 ✔
 * │     ├─ Good ✔
 * │     └─ Buggy ✘ expected: <20> but was: <21>
 * └─ mulFourByFive(Calc) ✔
 *    ├─ Good ✔
 *    └─ Buggy ✘ expected: <20> but was: <21>
 */
package examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ComparisonTest(classes = CalcVariant.class)
// The comparison engine supplies the implementation parameter on the generated test leaf.
@SuppressWarnings("JUnitMalformedDeclaration")
class MulTest {

    // quoteTextArguments = false only shortens the display name to "[1] 2, 3, 6" (JUnit 6 default: "2", "3", "6")
    @ParameterizedTest(quoteTextArguments = false)
    @CsvSource({ "2, 3, 6", "4, 5, 20" })
    void mul(int a, int b, int expected, Calc calc) {
        assertEquals(expected, calc.mul(a, b));
    }

    @Test
    void mulFourByFive(Calc calc) {
        assertEquals(20, calc.mul(4, 5));
    }
}
