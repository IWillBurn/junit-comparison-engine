package fixtures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import examples.Calc;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Records what differs between the variants: when @BeforeAll and the argument source run, the order of the leaves,
 * the implementation the constructor receives and what TestInfo reports. Run by VariantPropertiesTest only.
 */
@ComparisonTest({ Calc.Good.class, Calc.Buggy.class })
@TestMethodOrder(MethodOrderer.MethodName.class)
public class PropertiesFixture {

    public static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    private final Calc constructed;

    public PropertiesFixture(Calc calc) {
        constructed = calc;
    }

    static IntStream numbers() {
        EVENTS.add("source");
        return IntStream.of(1, 2);
    }

    @BeforeAll
    static void beforeAll() {
        EVENTS.add("@BeforeAll");
    }

    @ParameterizedTest
    @MethodSource("numbers")
    void param(int x, Calc calc, TestInfo info) {
        EVENTS.add("param " + x + " " + describe(calc, info));
    }

    @Test
    void plain(Calc calc, TestInfo info) {
        EVENTS.add("plain " + describe(calc, info));
    }

    private String describe(Calc calc, TestInfo info) {
        return calc.getClass().getSimpleName() + ", constructor: " + constructed.getClass().getSimpleName()
                + ", TestInfo: " + info.getDisplayName();
    }
}
