/*
 * The current variant as a test parameter: typed as the enum (CalcVariant) or as the Variant interface. A
 * parameterized test takes it after its argument-set parameters. The enum lets a test pin a known difference
 * between implementations; the interface is enough to read the name shown in the tree.
 *
 * Tree (actual run, ConsoleLauncher --details=tree):
 *
 * VariantTest ✔
 * ├─ reportsTheVariant(Variant, TestInfo) ✔
 * │  ├─ Good ✔
 * │  └─ Buggy ✔
 * ├─ mul(int, int, int, CalcVariant, Calc) ✔
 * │  ├─ [1] 2, 3, 6 ✔
 * │  │  ├─ Good ✔
 * │  │  └─ Buggy ✔
 * │  └─ [2] 4, 5, 20 ✔
 * │     ├─ Good ✔
 * │     └─ Buggy ✔
 * └─ mulFourByFive(CalcVariant, Calc) ✔
 *    ├─ Good ✔
 *    └─ Buggy ✔
 */
package examples;

import static examples.CalcSource.CalcVariant.BUGGY;
import static org.junit.jupiter.api.Assertions.assertEquals;

import examples.CalcSource.CalcVariant;
import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.api.Variant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ComparisonTest(source = CalcSource.class)
@SuppressWarnings("JUnitMalformedDeclaration")
class VariantTest {

    @Test
    void mulFourByFive(CalcVariant variant, Calc calc) {
        assertEquals(variant == BUGGY ? 21 : 20, calc.mul(4, 5));   // a known difference, pinned
    }

    @Test
    void reportsTheVariant(Variant variant, TestInfo info) {
        assertEquals(variant.testName(), info.getDisplayName());
    }

    @ParameterizedTest(quoteTextArguments = false)
    @ComparisonArguments(3)
    @CsvSource({ "2, 3, 6", "4, 5, 20" })
    void mul(int a, int b, int expected, CalcVariant variant, Calc calc) {
        if (variant == BUGGY && a == 4) {
            expected = 21;
        }
        assertEquals(expected, calc.mul(a, b));
    }
}
