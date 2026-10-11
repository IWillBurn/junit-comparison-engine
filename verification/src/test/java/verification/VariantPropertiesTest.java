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
            "source",                                                              // once for all implementations
            "param 1 Good, constructor: Good, TestInfo: Good",                     // TestInfo: the leaf itself
            "param 1 Buggy, constructor: Buggy, TestInfo: Buggy",                  // natural order: per argument set
            "param 2 Good, constructor: Good, TestInfo: Good",
            "param 2 Buggy, constructor: Buggy, TestInfo: Buggy",
            "plain Good, constructor: Good, TestInfo: Good",
            "plain Buggy, constructor: Buggy, TestInfo: Buggy"),
            PropertiesFixture.EVENTS);
    }
}
