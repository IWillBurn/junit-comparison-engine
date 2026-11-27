package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apiguardian.api.API;

/**
 * The generic skip annotation: skips a comparison test method for the named variant on every argument set.
 * It names the enum constant as a string, so it works with any variant enum; a project wanting compile-time
 * checking declares its own annotation with {@link SkipMarker} instead.
 *
 * <pre>{@code
 * @Skip("ORACLE")
 * @Skip(value = "MYSQL", description = "this type is buggy in MySQL")
 * }</pre>
 */
@API(status = EXPERIMENTAL, since = "0.1")
@SkipMarker
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(Skip.List.class)
public @interface Skip {

    /** Name of the variant enum constant to skip; a name the enum lacks invalidates the method. */
    String value();

    /** Reported as the skip reason; when blank, the reason is "skipped by @Skip". */
    String description() default "";

    /** Holds repeated {@code @Skip} annotations; the compiler writes it, not the test author. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface List {
        Skip[] value();
    }
}
