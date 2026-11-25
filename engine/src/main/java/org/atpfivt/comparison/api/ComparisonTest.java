package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apiguardian.api.API;

/**
 * Runs every {@code @Test} and {@code @ParameterizedTest} method of the annotated class on each variant.
 * The {@linkplain #source() source} selects enum variants, supplies parameter resolvers, and may implement Jupiter
 * lifecycle callbacks. The current enum constant is also available as a test parameter. For parameterized methods,
 * {@link ComparisonArguments} identifies the parameters supplied by the argument set.
 * Each variant becomes a separate leaf under the method (or under the argument set).
 *
 * <p>The annotation has no Jupiter meta-annotations: such classes are run by the {@code comparison-tests} engine,
 * which generates a carrier class at run time and lets the real Jupiter execute it. The ordinary Jupiter engine does
 * not run them.
 */
@API(status = EXPERIMENTAL, since = "0.1")
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface ComparisonTest {

    /** A source with a no-arg constructor that declares variants and may provide Jupiter extensions. */
    Class<? extends ComparisonSource<?>> source();
}
