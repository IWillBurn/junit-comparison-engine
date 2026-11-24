package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import org.apiguardian.api.API;
import org.jspecify.annotations.NullUnmarked;

/** An enum constant representing a comparison variant. */
@API(status = EXPERIMENTAL, since = "0.1")
public interface Variant {

    /** The constant name identifies the variant in {@link Skip} and test selectors. */
    @NullUnmarked
    String name();

    /** The label shown in the test tree. */
    default String testName() {
        return name();
    }

    /** Whether this variant is a reference implementation. */
    default boolean isOracle() {
        return false;
    }
}
