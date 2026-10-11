/*
 * The same class as LifecycleTest, but plain Jupiter: no @ComparisonTest and one hard-coded implementation
 * (Calc.Good). It shows in IntelliJ how a normal class looks: the leaves are the methods and the argument sets.
 *
 * Tree (actual run, ConsoleLauncher --details=tree):
 *
 * PlainLifecycleTest ✔
 * ├─ mul(int, int, int) ✔
 * │  ├─ [1] 2, 3, 6 ✔
 * │  └─ [2] 4, 5, 20 ✔
 * └─ mulTwoByTwo() ✔
 *
 * Output: @BeforeAll, then for every test @BeforeEach instance #N, test, @AfterEach instance #N (#1..#3).
 */
package examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlainLifecycleTest {

    private static int instances;

    private final int instance = ++instances;

    private final Calc calc = new Calc.Good();

    @BeforeAll
    static void beforeAll() {
        System.out.println("@BeforeAll");
    }

    @BeforeEach
    void setUp() {
        System.out.println("@BeforeEach  instance #" + instance);
    }

    @AfterEach
    void tearDown() {
        System.out.println("@AfterEach   instance #" + instance);
    }

    @ParameterizedTest(quoteTextArguments = false)
    @CsvSource({ "2, 3, 6", "4, 5, 20" })
    void mul(int a, int b, int expected) {
        System.out.println("test         mul(" + a + ", " + b + ")");
        assertEquals(expected, calc.mul(a, b));
    }

    @Test
    void mulTwoByTwo() {
        System.out.println("test         mulTwoByTwo");
        assertEquals(4, calc.mul(2, 2));
    }
}
