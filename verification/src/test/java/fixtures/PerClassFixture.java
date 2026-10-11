package fixtures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import examples.Calc;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

/**
 * @TestInstance(PER_CLASS): one instance for all leaves, created before the leaves exist, so the constructor cannot
 * receive the implementation; methods still get the implementation of their leaf. Run by ExtraFeaturesTest only.
 */
@ComparisonTest({ Calc.Good.class, Calc.Buggy.class })
@TestInstance(Lifecycle.PER_CLASS)
public class PerClassFixture {

    public static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    private Calc fromBeforeEach;

    @BeforeEach
    void setUp(Calc calc) {
        fromBeforeEach = calc;
    }

    @Test
    void test(Calc calc) {
        EVENTS.add(System.identityHashCode(this) + " " + calc.getClass().getSimpleName() + " "
                + (fromBeforeEach == calc));
    }
}
