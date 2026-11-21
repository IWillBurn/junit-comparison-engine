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
 * Declare either implementation {@linkplain #classes() classes} or a {@linkplain #source() source}.
 * The argument is passed as the last method parameter whose type accepts all variants.
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

    /** Implementation classes with no-arg constructors. Each leaf is named after its implementation class. */
    Class<?>[] classes() default {};

    /** A source with a no-arg constructor that declares variants and creates their arguments. */
    Class<? extends ComparisonSource<?>> source() default NoSource.class;

    /** Default marker for the form that lists implementation classes. */
    interface NoSource extends ComparisonSource<Object> {
    }
}
