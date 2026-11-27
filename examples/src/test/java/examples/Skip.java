package examples;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import examples.CalcSource.CalcVariant;
import org.atpfivt.comparison.api.SkipMarker;

/**
 * This project's type-safe skip annotation: {@code @Skip(BUGGY)} or {@code @Skip(value = BUGGY, description = "...")}
 * on a comparison test method. The engine finds it through {@link SkipMarker} and reads {@code value()} and
 * {@code description()} by convention. The nested container lets the annotation repeat, one description per variant;
 * an array {@code value()} would allow several constants in one annotation instead. The library's own
 * {@link org.atpfivt.comparison.api.Skip} does the same by constant name without a project annotation.
 */
@SkipMarker
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(Skip.List.class)
public @interface Skip {

    /** The variants to skip. */
    CalcVariant value();

    /** Reported as the skip reason; an empty description reports "skipped by @Skip". */
    String description() default "";

    /** Holds repeated {@code @Skip} annotations; the compiler writes it, not the test author. */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface List {
        Skip[] value();
    }
}
