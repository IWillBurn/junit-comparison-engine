/*
 * Two ways to skip a variant. @Skip (Skip.java in this package) is the project's own annotation, typed with
 * CalcVariant and found by the engine through @SkipMarker; the library's org.atpfivt.comparison.api.Skip names the
 * constant as a string. Both remove the variant from the method on every argument set, and both report the
 * description as the skip reason, or "skipped by @Skip" without one, so the tree still shows the variant.
 *
 * Tree (actual run, ConsoleLauncher --details=tree):
 *
 * SkipTest ✔
 * ├─ mulFourByFiveAgain(Calc) ✔
 * │  ├─ Good ✔
 * │  └─ Buggy ↷ skipped by @Skip
 * ├─ mul(int, int, int, Calc) ✔
 * │  ├─ [1] 2, 3, 6 ✔
 * │  │  ├─ Good ✔
 * │  │  └─ Buggy ↷ Buggy returns 21 for 4 * 5
 * │  └─ [2] 4, 5, 20 ✔
 * │     ├─ Good ✔
 * │     └─ Buggy ↷ Buggy returns 21 for 4 * 5
 * └─ mulFourByFive(Calc) ✔
 *    ├─ Good ↷ just because
 *    └─ Buggy ↷ skipped by @Skip
 */
package examples;

import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static examples.CalcSource.CalcVariant.BUGGY;
import static examples.CalcSource.CalcVariant.GOOD;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ComparisonTest(source = CalcSource.class)
@SuppressWarnings("JUnitMalformedDeclaration")
class SkipTest {

    @ParameterizedTest(quoteTextArguments = false)
    @ComparisonArguments(3)
    @CsvSource({ "2, 3, 6", "4, 5, 20" })
    @Skip(value = BUGGY, description = "Buggy returns 21 for 4 * 5")
    void mul(int a, int b, int expected, Calc calc) {
        assertEquals(expected, calc.mul(a, b));
    }

    @Test
    @Skip(BUGGY)
    @Skip(value = GOOD, description = "just because")
    void mulFourByFive(Calc calc) {
        assertEquals(20, calc.mul(4, 5));
    }

    @Test
    @org.atpfivt.comparison.api.Skip(value = "BUGGY", description = "non type-safe skip")
    void mulFourByFiveAgain(Calc calc) {
        assertEquals(20, calc.mul(4, 5));
    }
}
