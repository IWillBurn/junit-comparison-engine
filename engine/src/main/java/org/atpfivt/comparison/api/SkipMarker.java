package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apiguardian.api.API;

/**
 * Marks a project's own skip annotation. Java annotations cannot take an arbitrary enum, so each project declares a
 * type-safe one for its variant enum and marks it with this meta-annotation:
 *
 * <pre>{@code
 * @SkipMarker
 * @Target(ElementType.METHOD)
 * @Retention(RetentionPolicy.RUNTIME)
 * @Repeatable(Skip.List.class)
 * public @interface Skip {
 *     Database[] value();
 *     String description() default "";
 *
 *     @Target(ElementType.METHOD)
 *     @Retention(RetentionPolicy.RUNTIME)
 *     @interface List { Skip[] value(); }
 * }
 * }</pre>
 *
 * <p>Placed on a comparison test method, the annotation skips the method for the listed variants on every argument
 * set. The contract, read reflectively:
 * <ul>
 * <li>{@code value()} returns one constant or an array of constants of the enum supplied by the class's
 * {@link ComparisonSource}, or constant names as a {@code String} or {@code String[]}. Any other type, another
 * enum, or an unknown name invalidates the method with a reported reason.</li>
 * <li>{@code description()}, optional, is a {@code String} reported as the skip reason. Without one, or when it is
 * blank, the reason names the annotation.</li>
 * <li>The annotation needs runtime retention. Repeating it requires a {@code @Repeatable} container: the engine reads
 * any container whose {@code value()} holds marked annotations, so nothing is registered. A repeatable annotation
 * and its enum must be public, because the JDK creates the container's proxy outside their package. A method that
 * is itself {@code @Disabled} ignores its skips.</li>
 * </ul>
 *
 * <p>The library's own {@link Skip} is such an annotation with string names, for projects that do not declare one.
 */
@API(status = EXPERIMENTAL, since = "0.1")
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SkipMarker {
}
