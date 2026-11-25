package org.atpfivt.comparison.engine;

import static org.apiguardian.api.API.Status.INTERNAL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apiguardian.api.API;
import org.atpfivt.comparison.api.Variant;

/**
 * Identifies the comparison variant of a generated Jupiter test method.
 */
@API(status = INTERNAL, since = "0.1")
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ImplementationLeaf {

    String value();

    Class<? extends Variant> variantType();
}
