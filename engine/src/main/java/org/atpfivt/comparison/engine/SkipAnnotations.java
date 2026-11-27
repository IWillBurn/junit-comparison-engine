package org.atpfivt.comparison.engine;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.atpfivt.comparison.api.SkipMarker;
import org.atpfivt.comparison.api.Variant;
import org.jspecify.annotations.Nullable;
import org.junit.platform.commons.support.ReflectionSupport;

/** Reads the {@link SkipMarker} annotations on a test method: variant name → skip reason. */
final class SkipAnnotations {

    private SkipAnnotations() {
    }

    /**
     * The variants a method skips with their reasons, in declaration order.
     *
     * @param variantType the enum the comparison source supplies, or null when it selects no variant
     * @throws IllegalArgumentException for an annotation violating the {@link SkipMarker} contract
     */
    static Map<String, String> read(Method method, @Nullable Class<? extends Variant> variantType) {
        Map<String, String> skips = new LinkedHashMap<>();
        for (Annotation declared : method.getDeclaredAnnotations()) {
            if (isSkip(declared.annotationType())) {
                read(declared, variantType, skips);
            }
            else if (isSkipContainer(declared.annotationType())) {
                for (Annotation contained : (Annotation[]) value(declared)) {
                    read(contained, variantType, skips);
                }
            }
        }
        return skips;
    }

    /** Whether the type is a skip annotation or a container of them: never copied to generated tests. */
    static boolean isSkipOrContainer(Class<? extends Annotation> type) {
        return isSkip(type) || isSkipContainer(type);
    }

    private static boolean isSkip(Class<? extends Annotation> type) {
        return type.isAnnotationPresent(SkipMarker.class);
    }

    private static boolean isSkipContainer(Class<? extends Annotation> type) {
        Class<?> component = valueMethod(type).map(Method::getReturnType).map(Class::getComponentType).orElse(null);
        return component != null && component.isAnnotation() && isSkip(component.asSubclass(Annotation.class));
    }

    private static void read(Annotation skip, @Nullable Class<? extends Variant> variantType,
            Map<String, String> skips) {
        String name = "@" + skip.annotationType().getSimpleName();
        Object value = value(skip);
        List<Object> values = value instanceof Object[] array ? Arrays.asList(array) : List.of(value);
        String reason = description(skip).filter(text -> !text.isBlank()).orElse("skipped by " + name);
        for (Object element : values) {
            String constant = switch (element) {
                case Enum<?> enumConstant -> constantOf(name, enumConstant, variantType);
                case String text -> nameOf(name, text, variantType);
                default -> throw new IllegalArgumentException(name
                        + ".value() must return the variant enum constants or their names, not " + describe(value));
            };
            if (skips.putIfAbsent(constant, reason) != null) {
                throw new IllegalArgumentException(name + " skips " + constant + " twice");
            }
        }
    }

    /** A typed constant: its enum must be the one the source supplies. */
    private static String constantOf(String name, Enum<?> constant, @Nullable Class<? extends Variant> variantType) {
        if (variantType != null && constant.getDeclaringClass() != variantType) {
            throw new IllegalArgumentException(name + " skips " + constant.getDeclaringClass().getSimpleName() + "."
                    + constant.name() + " but the comparison source supplies " + variantType.getSimpleName()
                    + " variants");
        }
        return constant.name();
    }

    /** A constant named as a string: the source's enum must declare it. */
    private static String nameOf(String name, String text, @Nullable Class<? extends Variant> variantType) {
        if (variantType != null
                && Arrays.stream(variantType.getEnumConstants()).map(Variant::name).noneMatch(text::equals)) {
            throw new IllegalArgumentException(name + " names " + text + " but " + variantType.getSimpleName()
                    + " has no such constant");
        }
        return text;
    }

    private static Object value(Annotation annotation) {
        Method value = valueMethod(annotation.annotationType()).orElseThrow(() -> new IllegalArgumentException(
            "@" + annotation.annotationType().getSimpleName() + " must declare value() with the variants to skip"));
        return ReflectionSupport.invokeMethod(value, annotation);
    }

    private static Optional<String> description(Annotation annotation) {
        return ReflectionSupport.findMethod(annotation.annotationType(), "description")
                .filter(method -> method.getReturnType() == String.class)
                .map(method -> (String) ReflectionSupport.invokeMethod(method, annotation));
    }

    private static Optional<Method> valueMethod(Class<? extends Annotation> type) {
        return ReflectionSupport.findMethod(type, "value");
    }

    private static String describe(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        Class<?> type = value.getClass();
        return type.isArray() ? type.getComponentType().getSimpleName() + "[]" : type.getSimpleName();
    }
}
