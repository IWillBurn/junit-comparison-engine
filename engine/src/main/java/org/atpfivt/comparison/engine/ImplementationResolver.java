package org.atpfivt.comparison.engine;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.apiguardian.api.API.Status.INTERNAL;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apiguardian.api.API;
import org.atpfivt.comparison.api.ClassVariant;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.ReflectionSupport;

/**
 * Supplies the implementation to the designated parameter of the generated leaf, the last compatible parameter
 * of the user constructor, and compatible lifecycle parameters. It also supplies the current enum constant.
 *
 * <p>The constructor gets the leaf's implementation only because this resolver asks for the {@code TEST_METHOD}
 * extension context scope during test instantiation (MAINTAINED API since 5.13.3).
 */
@API(status = INTERNAL, since = "0.1")
public final class ImplementationResolver implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(ImplementationResolver.class);
    private static final String IMPLEMENTATION = "implementation";

    private static final ClassValue<Arguments> CLASS_ARGUMENTS = new ClassValue<>() {
        @Override
        protected Arguments computeValue(Class<?> variantType) {
            return new Arguments(List.of(variantType.asSubclass(ClassVariant.class).getEnumConstants()));
        }
    };

    @Override
    public ExtensionContextScope getTestInstantiationExtensionContextScope(ExtensionContext rootContext) {
        return ExtensionContextScope.TEST_METHOD;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Method leaf = extensionContext.getTestMethod().orElse(null);
        if (leaf == null || !leaf.isAnnotationPresent(ImplementationLeaf.class)) {
            return false;
        }
        ImplementationLeaf implementation = leaf.getAnnotation(ImplementationLeaf.class);
        var executable = parameterContext.getDeclaringExecutable();
        if (executable instanceof Constructor<?> && executable.getDeclaringClass() == leaf.getDeclaringClass()) {
            return false; // The generated nested constructor takes Jupiter's argument set.
        }
        var parameterType = parameterContext.getParameter().getType();
        if (parameterType == implementation.variantType()) {
            return true;
        }
        if (executable.equals(leaf)) {
            return parameterContext.getIndex() == implementation.parameterIndex();
        }
        var arguments = CLASS_ARGUMENTS.get(implementation.variantType());
        if (executable instanceof Constructor<?>) {
            return ComparisonClass.implementationParameter(executable, arguments::acceptsAll)
                    == parameterContext.getIndex();
        }
        return arguments.acceptsAll(parameterType);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        var leaf = extensionContext.getRequiredTestMethod().getAnnotation(ImplementationLeaf.class);
        ClassVariant variant = CLASS_ARGUMENTS.get(leaf.variantType()).byName().get(leaf.value());
        if (parameterContext.getParameter().getType() == leaf.variantType()) {
            return variant;
        }
        var leafStore = extensionContext.getStore(NAMESPACE);
        return leafStore.computeIfAbsent(IMPLEMENTATION,
            key -> ReflectionSupport.newInstance(variant.implementation()), Object.class);
    }

    private record Arguments(Map<String, ClassVariant> byName, Set<Class<?>> types) {
        Arguments(List<ClassVariant> variants) {
            this(variants.stream().collect(toUnmodifiableMap(ClassVariant::name, identity())),
                variants.stream().map(ClassVariant::implementation).collect(toUnmodifiableSet()));
        }

        boolean acceptsAll(Class<?> parameterType) {
            return types.stream().allMatch(parameterType::isAssignableFrom);
        }
    }
}
