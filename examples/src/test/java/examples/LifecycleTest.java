/*
 * Lifecycle per leaf: @BeforeEach/@AfterEach and a new instance of the class (instance #1..#6) for every leaf,
 * @BeforeAll once. Compare with PlainLifecycleTest, the same class in plain Jupiter.
 *
 * The order of the leaves depends on the variant. class-template runs them "by implementation": first all Good
 * (instances #1..#3), then all Buggy (#4..#6). generated-carrier and jupiter-descriptors run them in the order of the
 * tree: per argument set Good, then Buggy.
 *
 * In IntelliJ, when this class is run alone, the "@BeforeAll" line is shown at the very end of the output although it
 * runs first. The output of @BeforeAll belongs to the root (the class). IntelliJ puts the root's output before the
 * children only if the root has a leaf among the children known before the run, otherwise after the whole tree. Here
 * the class has only containers below it (even @Test became a container with the Good/Buggy leaves), so the line is at
 * the bottom. PlainLifecycleTest has the leaf mulTwoByTwo(), so there it is at the top.
 *
 * Tree (actual run, ConsoleLauncher --details=tree):
 *
 * LifecycleTest ✔
 * ├─ mul(int, int, int, Calc) ✔
 * │  ├─ [1] 2, 3, 6 ✔
 * │  │  ├─ Good ✔
 * │  │  └─ Buggy ✔
 * │  └─ [2] 4, 5, 20 ✔
 * │     ├─ Good ✔
 * │     └─ Buggy ✘ expected: <20> but was: <21>
 * └─ mulTwoByTwo(Calc) ✔
 *    ├─ Good ✔
 *    └─ Buggy ✔
 *
 * Output: @BeforeAll, then for every leaf @BeforeEach instance #N, test, @AfterEach instance #N.
 */
package examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ComparisonTest({ Calc.Good.class, Calc.Buggy.class })
class LifecycleTest {

    private static int instances;

    private final int instance = ++instances;

    @BeforeAll
    static void beforeAll() {
        System.out.println("@BeforeAll");
    }

    @BeforeEach
    void setUp(Calc calc) {
        System.out.println("@BeforeEach  instance #" + instance + ", " + calc.getClass().getSimpleName());
    }

    @AfterEach
    void tearDown() {
        System.out.println("@AfterEach   instance #" + instance);
    }

    @ParameterizedTest(quoteTextArguments = false)
    @CsvSource({ "2, 3, 6", "4, 5, 20" })
    void mul(int a, int b, int expected, Calc calc) {
        System.out.println("test         mul(" + a + ", " + b + ")");
        assertEquals(expected, calc.mul(a, b));
    }

    @Test
    void mulTwoByTwo(Calc calc) {
        System.out.println("test         mulTwoByTwo");
        assertEquals(4, calc.mul(2, 2));
    }
}
