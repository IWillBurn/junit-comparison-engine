package org.atpfivt.comparison.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs every {@code @Test} and {@code @ParameterizedTest} method of the annotated class on each listed
 * implementation. The implementation is passed as the last method parameter whose type accepts all of them; every
 * implementation becomes a separate leaf of the test tree under the method (or under the argument set).
 *
 * <p>Mechanics (generated-carrier variant): the annotation has no Jupiter meta-annotations. The
 * {@code comparison-tests} engine generates a carrier class at runtime and lets the real Jupiter execute it; the
 * ordinary Jupiter engine does not run such classes.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface ComparisonTest {

    /** Implementation classes, each with a no-arg constructor; the leaf name is the simple class name. */
    Class<?>[] value();
}
