package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.List;

import fixtures.PropertiesFixture;
import org.junit.jupiter.api.Test;

/**
 * The properties in which the variants differ, as one ordered list of events of PropertiesFixture. Every
 * variant asserts its own actual values here, so the variant branches can be compared side by side.
 */
class VariantPropertiesTest {

    @Test
    void properties() {
        PropertiesFixture.EVENTS.clear();
        Run run = Run.of(selectClass(PropertiesFixture.class));
        assertEquals(List.of(), run.violations);
        assertEquals(List.of(
            "@BeforeAll",                                                          // once per class
            "source",                                                              // once per implementation
            "param 1 Good, constructor: Good, TestInfo: [1] 1",
            "param 2 Good, constructor: Good, TestInfo: [2] 2",
            "plain Good, constructor: Good, TestInfo: plain(Calc, TestInfo)",      // all Good leaves first
            "source",
            "param 1 Buggy, constructor: Buggy, TestInfo: [1] 1",                  // TestInfo: no implementation
            "param 2 Buggy, constructor: Buggy, TestInfo: [2] 2",
            "plain Buggy, constructor: Buggy, TestInfo: plain(Calc, TestInfo)"),
            PropertiesFixture.EVENTS);
    }
}
