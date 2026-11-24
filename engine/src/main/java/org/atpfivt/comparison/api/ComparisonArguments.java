package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apiguardian.api.API;

/**
 * Counts the leading parameters of a parameterized test supplied by its argument set. Required with a comparison
 * source. Tests declared with {@link ComparisonTest#classes()} can use it to override the inferred boundary.
 */
@API(status = EXPERIMENTAL, since = "0.1")
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ComparisonArguments {

    int value();
}
