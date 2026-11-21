package org.atpfivt.comparison.engine;

import static org.apiguardian.api.API.Status.INTERNAL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apiguardian.api.API;

/**
 * Put by the engine on every generated leaf method ({@code @Test void Good(...)}): the implementation this leaf runs
 * on. {@link ImplementationResolver} reads it to create the implementation object of the leaf. Not for users.
 */
@API(status = INTERNAL, since = "0.1")
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ImplementationLeaf {

    String value();

    int parameterIndex();
}
