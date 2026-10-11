package fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import examples.Calc;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Members beyond the minimal scope: @RepeatedTest, @Nested, auto-closed arguments, @Tag and the unsupported
 * @TestFactory. Run by ExtraFeaturesTest only.
 */
@ComparisonTest({ Calc.Good.class, Calc.Buggy.class })
public class MembersFixture {

    public static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    @RepeatedTest(2)
    @Tag("slow")
    void repeated(Calc calc) {
        EVENTS.add("repeated " + calc.getClass().getSimpleName());
    }

    @TestFactory
    Stream<DynamicTest> factory() {
        EVENTS.add("factory");
        return Stream.of(DynamicTest.dynamicTest("dynamic", () -> { }));
    }

    static Stream<Arguments> resources() {
        return Stream.of(Arguments.of(new Resource()));
    }

    /** The argument is shared by the implementations of its set and closed once, after the last one. */
    @ParameterizedTest
    @MethodSource("resources")
    void closeable(Resource resource, Calc calc) {
        assertFalse(resource.closed, "closed before " + calc.getClass().getSimpleName());
        EVENTS.add("closeable " + calc.getClass().getSimpleName());
    }

    @Nested
    class Inner {

        @Test
        @Tag("fast")
        void nested(Calc calc) {
            assertEquals(4, calc.mul(2, 2));
            EVENTS.add("nested " + calc.getClass().getSimpleName());
        }
    }

    public static final class Resource implements AutoCloseable {

        boolean closed;

        @Override
        public void close() {
            closed = true;
            EVENTS.add("closed");
        }

        @Override
        public String toString() {
            return "resource";
        }
    }
}
