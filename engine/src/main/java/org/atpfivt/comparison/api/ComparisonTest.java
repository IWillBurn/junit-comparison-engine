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
 * Declare variants through an {@linkplain #classes() enum of implementation classes} or a {@linkplain #source() source}.
 * Either form can supply the current enum constant as a test parameter. With {@code classes}, the implementation
 * instance is passed to the last method parameter whose type accepts all implementations.
 * Sources supply parameter resolvers and may implement Jupiter lifecycle callbacks. For parameterized methods
 * using a source, {@link ComparisonArguments} identifies the parameters supplied by the argument set.
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

    /** An enum whose constants declare implementation classes. */
    Class<? extends ClassVariant> classes() default ClassVariant.class;

    /** A source with a no-arg constructor that declares variants and may provide Jupiter extensions. */
    Class<? extends ComparisonSource<?>> source() default NoSource.class;

    /** Default marker for the form that lists implementation classes. */
    interface NoSource extends ComparisonSource<NoSource.Placeholder> {

        enum Placeholder implements Variant {
        }
    }
}
