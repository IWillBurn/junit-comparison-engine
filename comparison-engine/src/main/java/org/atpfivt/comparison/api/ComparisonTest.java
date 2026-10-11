package org.atpfivt.comparison.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.ClassTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs every {@code @Test} and {@code @ParameterizedTest} of the class once per implementation:
 * {@code @ComparisonTest({ Calc.Good.class, Calc.Buggy.class })}. Each implementation class needs a no-arg
 * constructor; every test gets a new instance in its LAST parameter whose type accepts all implementations. The tree
 * is {@code class > method > [argument set] > implementation}, the leaf is named after the simple class name.
 *
 * <p>Mechanics (class-template variant): the class is a Jupiter {@link ClassTemplate} with one hidden invocation per
 * implementation; the {@code comparison-tests} engine moves that level from the top of the tree to the leaves.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ClassTemplate
@ExtendWith(ImplementationProvider.class)
public @interface ComparisonTest {

    /** The implementations to compare, in the order of the leaves. */
    Class<?>[] value();
}
