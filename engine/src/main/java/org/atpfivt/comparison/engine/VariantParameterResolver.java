package org.atpfivt.comparison.engine;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.Variant;
import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.ReflectionSupport;

/** Supplies the current enum constant and the arguments of that variant to Jupiter. */
record VariantParameterResolver(Map<String, Implementation> variants, Set<Class<?>> implementationTypes,
        @Nullable ComparisonSource<?> source) implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(VariantParameterResolver.class);
    private static final Object IMPLEMENTATION = new Object();

    private record Resolvers(List<ParameterResolver> entries) {
    }

    private record SelectedResolver(ParameterResolver delegate) {
    }

    VariantParameterResolver(List<Implementation> implementations, @Nullable ComparisonSource<?> source) {
        this(implementations.stream().collect(toUnmodifiableMap(Implementation::name, identity())),
            source == null ? implementations.stream().map(Implementation::implementationType)
                    .map(Objects::requireNonNull).collect(toUnmodifiableSet()) : Set.of(), source);
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
        var parameterType = parameter.getParameter().getType();
        if (parameterType == marker.variantType()) {
            return true;
        }
        if (source != null) {
            return selectResolver(parameter, context, marker.value());
        }
        if (executable.equals(leaf)) {
            return parameter.getIndex() == marker.parameterIndex();
        }
        if (executable instanceof Constructor<?>) {
            return ComparisonClass.implementationParameter(executable, this::acceptsAll) == parameter.getIndex();
        }
        return acceptsAll(parameterType);
    }

    @Override
    public @Nullable Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        var marker = Objects.requireNonNull(context.getRequiredTestMethod().getAnnotation(ImplementationLeaf.class));
        var variant = variants.get(marker.value());
        if (parameter.getParameter().getType() == marker.variantType()) {
            return variant.variant();
        }
        var store = context.getStore(NAMESPACE);
        if (source == null) {
            return store.computeIfAbsent(IMPLEMENTATION,
                key -> ReflectionSupport.newInstance(Objects.requireNonNull(variant.implementationType())), Object.class);
        }
        var selected = store.remove(parameter.getParameter(), SelectedResolver.class);
        if (selected == null) {
            throw new ParameterResolutionException("No resolver for " + parameter.getParameter());
        }
        return selected.delegate().resolveParameter(parameter, context);
    }

    private boolean acceptsAll(Class<?> parameterType) {
        return implementationTypes.stream().allMatch(parameterType::isAssignableFrom);
    }

    private boolean selectResolver(ParameterContext parameter, ExtensionContext context, String name) {
        var store = context.getStore(NAMESPACE);
        var resolvers = store.computeIfAbsent(name,
            key -> resolversFor(Objects.requireNonNull(source), variants.get(key).variant()), Resolvers.class);
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
