package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import org.apiguardian.api.API;
import org.jspecify.annotations.NullUnmarked;

import java.util.Arrays;
import java.util.List;

/**
 * An enum constant representing a comparison variant. A comparison test receives the current constant through a
 * parameter typed as the enum, as {@code Variant}, or as any interface between the two that the enum implements.
 */
@API(status = EXPERIMENTAL, since = "0.1")
public interface Variant {

    /** The constant name identifies the variant in test selectors; {@link SkipMarker} annotations name the constant itself. */
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
