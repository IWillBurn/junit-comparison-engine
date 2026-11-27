package org.atpfivt.comparison.engine;

import static java.util.stream.Collectors.toUnmodifiableMap;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.Variant;
import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Supplies the current enum constant and the arguments of that variant to Jupiter. */
record VariantParameterResolver(Map<String, Variant> variants, ComparisonSource<?> source)
        implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(VariantParameterResolver.class);

    private record Resolvers(List<ParameterResolver> entries) {
    }

    private record SelectedResolver(ParameterResolver delegate) {
    }

    VariantParameterResolver(List<Implementation> implementations, ComparisonSource<?> source) {
        this(implementations.stream().collect(toUnmodifiableMap(Implementation::name, Implementation::variant)), source);
    }

    @Override
    public ExtensionContextScope getTestInstantiationExtensionContextScope(ExtensionContext context) {
        return ExtensionContextScope.TEST_METHOD;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        var leaf = context.getTestMethod().orElse(null);
        if (leaf == null || !leaf.isAnnotationPresent(ImplementationLeaf.class)) {
            return false;
        }
        var executable = parameter.getDeclaringExecutable();
        if (executable instanceof Constructor<?> && executable.getDeclaringClass() == leaf.getDeclaringClass()) {
            return false; // The generated nested constructor takes Jupiter's argument set.
        }
        var marker = leaf.getAnnotation(ImplementationLeaf.class);
        return isVariantParameter(parameter.getParameter().getType(), marker.variantType())
                || selectResolver(parameter, context, marker.value());
    }

    @Override
    public @Nullable Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        var marker = Objects.requireNonNull(context.getRequiredTestMethod().getAnnotation(ImplementationLeaf.class));
        if (isVariantParameter(parameter.getParameter().getType(), marker.variantType())) {
            return variants.get(marker.value());
        }
        var selected = context.getStore(NAMESPACE).remove(parameter.getParameter(), SelectedResolver.class);
        if (selected == null) {
            throw new ParameterResolutionException("No resolver for " + parameter.getParameter());
        }
        return selected.delegate().resolveParameter(parameter, context);
    }

    /** The enum itself, {@link Variant}, or an interface between them that the enum implements. */
    private static boolean isVariantParameter(Class<?> type, Class<? extends Variant> variantType) {
        return type == variantType || (Variant.class.isAssignableFrom(type) && type.isAssignableFrom(variantType));
    }

    private boolean selectResolver(ParameterContext parameter, ExtensionContext context, String name) {
        var store = context.getStore(NAMESPACE);
        var resolvers = store.computeIfAbsent(name,
            key -> resolversFor(source, variants.get(key)), Resolvers.class);
        ParameterResolver match = null;
        for (var resolver : resolvers.entries()) {
            if (resolver.supportsParameter(parameter, context)) {
                if (match != null) {
                    throw new ParameterResolutionException("Multiple resolvers for " + parameter.getParameter()
                            + " on comparison variant " + name + ": " + match + ", " + resolver);
                }
                match = resolver;
            }
        }
        if (match == null) {
            return false;
        }
        store.put(parameter.getParameter(), new SelectedResolver(match));
        return true;
    }

    @SuppressWarnings("unchecked") // Discovery obtained the constant from this source class.
    private static <V extends Enum<V> & Variant> Resolvers resolversFor(ComparisonSource<V> source, Variant variant) {
        return new Resolvers(List.copyOf(source.parameterResolvers((V) variant)));
    }
}
