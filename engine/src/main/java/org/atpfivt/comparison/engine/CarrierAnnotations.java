package org.atpfivt.comparison.engine;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationDescription.Builder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.DisabledIf;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.Extensions;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.FieldSources;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.MethodSources;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;

/** Annotation placement for the carrier, each argument-set class, and each implementation leaf. */
final class CarrierAnnotations {

    private static final Set<String> PARAMETERIZED_CLASS_ATTRIBUTES = Arrays
            .stream(ParameterizedClass.class.getDeclaredMethods()).map(Method::getName)
            .collect(Collectors.toUnmodifiableSet());
    private final List<AnnotationDescription> onNestedClass;
    private final List<AnnotationDescription> onLeaf;
    /** Variant constant name → skip reason. */
    private final Map<String, String> skippedVariants;
    private final @Nullable SourceParameterResolver sourceResolver;
    private final List<InstanceCondition> instanceConditions = new ArrayList<>();

    record InstanceCondition(String name, Predicate<ExtensionContext> predicate) {
    }

    CarrierAnnotations(Class<?> userClass, Method method, String displayName, boolean parameterized,
            Map<String, String> skips) {
        skippedVariants = skips;
        onNestedClass = new ArrayList<>(List.of(
            annotation(Nested.class).build(),
            annotation(DisplayName.class).define("value", displayName).build(),
            annotation(TestMethodOrder.class).define("value", MethodOrderer.OrderAnnotation.class).build(),
            annotation(TestInstance.class).define("value", TestInstance.Lifecycle.PER_METHOD).build()));
        if (parameterized) {
            onNestedClass.add(parameterizedClass(method));
        }
        List<Annotation> sources = new ArrayList<>();
        List<Annotation> execution = new ArrayList<>();
        for (Annotation declared : method.getDeclaredAnnotations()) {
            collect(declared, parameterized, sources, execution, new HashSet<>());
        }
        onNestedClass.addAll(describe(sources, source -> argumentSource(source, userClass, method.getName())));
        onLeaf = describe(execution, declared -> leafAnnotation(declared, userClass));
        sourceResolver = sources.stream().anyMatch(MethodSource.class::isInstance)
                ? SourceParameterResolver.forMethod(userClass, method).orElse(null) : null;
    }

    Optional<SourceParameterResolver> sourceResolver() {
        return Optional.ofNullable(sourceResolver);
    }

    List<InstanceCondition> instanceConditions() {
        return instanceConditions;
    }

    List<AnnotationDescription> nestedClass(int order) {
        List<AnnotationDescription> annotations = new ArrayList<>(onNestedClass);
        annotations.add(annotation(Order.class).define("value", order).build());
        return annotations;
    }

    List<AnnotationDescription> leaf(int order, Implementation implementation) {
        List<AnnotationDescription> annotations = new ArrayList<>(onLeaf);
        String name = implementation.name();
        String reason = skippedVariants.get(name);
        if (reason != null) {
            annotations.add(annotation(Disabled.class).define("value", reason).build());
        }
        annotations.add(annotation(Test.class).build());
        annotations.add(annotation(Order.class).define("value", order).build());
        annotations.add(annotation(DisplayName.class).define("value", implementation.displayName()).build());
        annotations.add(annotation(ImplementationLeaf.class).define("value", name)
                .define("variantType", implementation.variantType()).build());
        return annotations;
    }

    /** Add the carrier's own annotations and copy the user's class annotations that Java does not inherit. */
    static List<AnnotationDescription> carrier(Class<?> userClass, String displayName) {
        List<AnnotationDescription> annotations = new ArrayList<>(List.of(
            annotation(DisplayName.class).define("value", displayName).build(),
            annotation(TestClassOrder.class).define("value", ClassOrderer.OrderAnnotation.class).build()));
        for (Annotation annotation : userClass.getDeclaredAnnotations()) {
            Class<? extends Annotation> type = annotation.annotationType();
            if (!type.isAnnotationPresent(Inherited.class) && canAnnotateClass(type)
                    && !isLanguageAnnotation(type) && type != ComparisonTest.class && type != DisplayName.class) {
                annotations.add(AnnotationDescription.ForLoadedAnnotation.of(annotation));
            }
        }
        return annotations;
    }

    /**
     * {@code @MethodSource("cases")} → {@code @MethodSource("pkg.UserClass#cases")}, the same for
     * {@code @FieldSource}. An empty name means the factory named like the test method.
     */
    private static AnnotationDescription argumentSource(Annotation annotation, Class<?> userClass, String methodName) {
        return switch (annotation) {
            case MethodSource source -> annotation(MethodSource.class)
                    .defineArray("value", qualifySources(source.value(), userClass, methodName)).build();
            case FieldSource source -> annotation(FieldSource.class)
                    .defineArray("value", qualifySources(source.value(), userClass, methodName)).build();
            default -> AnnotationDescription.ForLoadedAnnotation.of(annotation);
        };
    }

    private static void collect(Annotation annotation, boolean parameterized, List<Annotation> sources,
            List<Annotation> execution, Set<Class<? extends Annotation>> path) {
        Class<? extends Annotation> type = annotation.annotationType();
        if (type == Test.class || type == ParameterizedTest.class || type == TestTemplate.class
                || type == DisplayName.class || type == Order.class || type == ComparisonArguments.class
                || SkipAnnotations.isSkipOrContainer(type) || isLanguageAnnotation(type)) {
            return;
        }
        Annotation[] contents = switch (annotation) {
            case MethodSources container -> container.value();
            case FieldSources container -> container.value();
            case Extensions container -> container.value();
            default -> null;
        };
        if (contents == null && needsTranslation(type)) {
            contents = type.getDeclaredAnnotations();
        }
        if (contents != null) {
            if (path.add(type)) {
                for (Annotation nested : contents) {
                    collect(nested, parameterized, sources, execution, path);
                }
                path.remove(type);
            }
        }
        else if (parameterized && canAnnotateClass(type) && isArgumentSource(type)) {
            sources.add(annotation);
        }
        else {
            execution.add(annotation);
        }
    }

    private static boolean needsTranslation(Class<? extends Annotation> type) {
        // Preserve other composed annotations as declarations so extensions can still read them.
        return AnnotationSupport.isAnnotated(type, TestTemplate.class)
                || AnnotationSupport.isAnnotated(type, EnabledIf.class)
                || AnnotationSupport.isAnnotated(type, DisabledIf.class)
                || !AnnotationSupport.findRepeatableAnnotations(type, MethodSource.class).isEmpty()
                || !AnnotationSupport.findRepeatableAnnotations(type, FieldSource.class).isEmpty();
    }

    private static List<AnnotationDescription> describe(List<Annotation> annotations,
            Function<Annotation, AnnotationDescription> translate) {
        Map<Class<? extends Annotation>, List<Annotation>> byType = new LinkedHashMap<>();
        annotations.forEach(annotation -> byType.computeIfAbsent(annotation.annotationType(), key -> new ArrayList<>())
                .add(annotation));
        return byType.entrySet().stream().map(entry -> {
            List<Annotation> group = entry.getValue();
            Repeatable[] repeatable = entry.getKey().getDeclaredAnnotationsByType(Repeatable.class);
            if (group.size() == 1 || repeatable.length == 0) {
                return translate.apply(group.getFirst());
            }
            return annotation(repeatable[0].value()).defineAnnotationArray("value",
                TypeDescription.ForLoadedType.of(entry.getKey()),
                group.stream().map(translate).toArray(AnnotationDescription[]::new)).build();
        }).toList();
    }

    private static String[] qualifySources(String[] names, Class<?> userClass, String methodName) {
        return Arrays.stream(names.length == 0 ? new String[] { "" } : names)
                .map(String::strip)
                .map(name -> name.isEmpty() ? methodName : name)
                .map(name -> qualifyName(name, userClass))
                .toArray(String[]::new);
    }

    private static boolean isArgumentSource(Class<? extends Annotation> type) {
        if (hasArgumentProvider(type)) {
            return true;
        }
        // Repeated source annotations (such as @CsvSources) are stored in their container.
        Class<?> component = ReflectionSupport.findMethod(type, "value")
                .map(Method::getReturnType).map(Class::getComponentType).orElse(null);
        if (component == null || !component.isAnnotation()) {
            return false;
        }
        return Arrays.stream(component.getDeclaredAnnotationsByType(Repeatable.class))
                .anyMatch(repeatable -> repeatable.value() == type) && hasArgumentProvider(component);
    }

    private static boolean hasArgumentProvider(Class<?> type) {
        return type == ArgumentsSource.class
                || !AnnotationSupport.findRepeatableAnnotations(type, ArgumentsSource.class).isEmpty();
    }

    private AnnotationDescription leafAnnotation(Annotation annotation, Class<?> userClass) {
        return switch (annotation) {
            case EnabledIf condition ->
                    condition(EnabledIf.class, condition.value(), condition.disabledReason(), userClass);
            case DisabledIf condition ->
                    condition(DisabledIf.class, condition.value(), condition.disabledReason(), userClass);
            default -> AnnotationDescription.ForLoadedAnnotation.of(annotation);
        };
    }

    private AnnotationDescription condition(Class<? extends Annotation> type, String name, String reason,
            Class<?> userClass) {
        String target = name.contains("#") ? name : conditionMethodName(userClass, name);
        return annotation(type)
                .define("value", target)
                .define("disabledReason", reason)
                .build();
    }

    private String conditionMethodName(Class<?> userClass, String name) {
        Optional<Method> instanceMethod = ReflectionSupport.findMethod(userClass, name)
                .or(() -> ReflectionSupport.findMethod(userClass, name, ExtensionContext.class))
                .filter(method -> !Modifier.isStatic(method.getModifiers()));
        if (instanceMethod.isEmpty()) {
            return qualifyName(name, userClass);
        }

        Method method = instanceMethod.get();
        String bridge = "$comparisonCondition" + instanceConditions.size();
        instanceConditions.add(new InstanceCondition(bridge, context -> {
            if (method.getReturnType() != boolean.class) {
                throw new JUnitException("Condition method [" + method + "] must return a boolean");
            }
            Object receiver = context.getRequiredTestInstances().findInstance(userClass).orElseThrow();
            Object result = method.getParameterCount() == 0 ? ReflectionSupport.invokeMethod(method, receiver)
                    : ReflectionSupport.invokeMethod(method, receiver, context);
            if (!(result instanceof Boolean decision)) {
                throw new JUnitException("Condition method [" + method + "] returned no boolean value");
            }
            return decision;
        }));
        return bridge;
    }

    private static String qualifyName(String name, Class<?> userClass) {
        return name.contains("#") ? name : userClass.getName() + "#" + name;
    }

    /** {@code @ParameterizedTest(...)} → {@code @ParameterizedClass(...)}: the attributes both declare are copied. */
    private static AnnotationDescription parameterizedClass(Method method) {
        AnnotationDescription test = AnnotationDescription.ForLoadedAnnotation
                .of(AnnotationSupport.findAnnotation(method, ParameterizedTest.class).orElseThrow());
        Builder builder = annotation(ParameterizedClass.class);
        for (MethodDescription.InDefinedShape attribute : test.getAnnotationType().getDeclaredMethods()) {
            if (PARAMETERIZED_CLASS_ATTRIBUTES.contains(attribute.getName())) {
                builder = builder.define(attribute.getName(), test.getValue(attribute));
            }
        }
        return builder.build();
    }

    private static Builder annotation(Class<? extends Annotation> type) {
        return Builder.ofType(type);
    }

    /** {@code java.lang} and Kotlin metadata annotations: not test configuration, never copied. */
    private static boolean isLanguageAnnotation(Class<? extends Annotation> type) {
        return type.getName().startsWith("java.lang.") || type.getName().startsWith("kotlin.");
    }

    private static boolean canAnnotateClass(Class<? extends Annotation> type) {
        return Arrays.stream(type.getDeclaredAnnotationsByType(Target.class)).findFirst()
                .map(target -> Arrays.asList(target.value()).contains(ElementType.TYPE)).orElse(true);
    }
}
