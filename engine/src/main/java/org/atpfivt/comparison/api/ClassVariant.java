package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import org.apiguardian.api.API;

/** Associates an enum constant with an implementation class for {@link ComparisonTest#classes()}. */
@API(status = EXPERIMENTAL, since = "0.1")
public interface ClassVariant extends Variant {

    /** A concrete class with a no-argument constructor. */
    Class<?> implementation();
}
