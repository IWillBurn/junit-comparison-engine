package org.atpfivt.comparison.engine;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Put by the engine on every generated leaf method ({@code @Test void Good(...)}): the implementation this leaf
 * runs on. {@link ImplementationResolver} reads it to create the implementation object of the leaf. Not for users.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ImplementationLeaf {

    Class<?> value();
}
