package org.atpfivt.comparison.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs every test of the annotated class once per implementation. The implementation is passed to the last parameter
 * whose type accepts all implementations (test method, {@code @BeforeEach}/{@code @AfterEach}, constructor).
 *
 * <p>Mechanics (jupiter-descriptors variant): the class is executed by the {@code comparison-tests} engine, which
 * runs Jupiter's own discovery and executor and replaces the method nodes with its own subclasses of Jupiter's
 * internal descriptors.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ComparisonTest {

    /** Implementation classes, each with a no-arg constructor; the leaf is named after the simple class name. */
    Class<?>[] value();
}
