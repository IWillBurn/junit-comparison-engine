package org.atpfivt.comparison.engine;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toUnmodifiableMap;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.apiguardian.api.API.Status.INTERNAL;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apiguardian.api.API;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;

/**
 * Supplies the implementation to the designated parameter of the generated leaf, the last compatible parameter
 * of the user constructor, and compatible lifecycle parameters. Each leaf receives one argument.
 *
 * <p>The constructor gets the leaf's implementation only because this resolver asks for the {@code TEST_METHOD}
 * extension context scope during test instantiation (MAINTAINED API since 5.13.3).
 */
@API(status = INTERNAL, since = "0.1")
public final class ImplementationResolver implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(ImplementationResolver.class);
    private static final Namespace SOURCE_RESOURCES = Namespace.create(ImplementationResolver.class, "resources");
    private static final String IMPLEMENTATION = "implementation";

    private static final ClassValue<Arguments> CLASS_ARGUMENTS = new ClassValue<>() {
        @Override
        protected Arguments computeValue(Class<?> carrier) {
            var declaration = AnnotationSupport.findAnnotation(carrier, ComparisonTest.class).orElseThrow();
            return new Arguments(ComparisonClass.implementations(declaration.classes()));
        }
    };

    private final @Nullable Arguments sourceArguments;

    public ImplementationResolver() {
        sourceArguments = null;
    }

    ImplementationResolver(List<Implementation> implementations) {
        sourceArguments = new Arguments(implementations);
    }

    @Override
    public ExtensionContextScope getTestInstantiationExtensionContextScope(ExtensionContext rootContext) {
        return ExtensionContextScope.TEST_METHOD;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Method leaf = extensionContext.getTestMethod().orElse(null);
        ImplementationLeaf implementation = leaf == null ? null : leaf.getAnnotation(ImplementationLeaf.class);
        if (leaf == null || implementation == null) {
            return false;
        }
        var executable = parameterContext.getDeclaringExecutable();
        if (executable.equals(leaf)) {
            return parameterContext.getIndex() == implementation.parameterIndex();
        }
        if (executable instanceof Constructor<?>) {
            if (executable.getDeclaringClass() == leaf.getDeclaringClass()) {
                return false; // The generated nested constructor takes Jupiter's argument set.
            }
            return ComparisonClass.implementationParameter(executable, arguments(leaf)::acceptsAll)
                    == parameterContext.getIndex();
        }
        return arguments(leaf).acceptsAll(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Method leaf = extensionContext.getRequiredTestMethod();
        Implementation implementation = arguments(leaf).byName().get(leaf.getAnnotation(ImplementationLeaf.class).value());
        var leafStore = extensionContext.getStore(NAMESPACE);
        if (implementation.factory() == null) {
            return leafStore.computeIfAbsent(IMPLEMENTATION,
                key -> ReflectionSupport.newInstance(implementation.type()), Object.class);
        }
        return leafStore.computeIfAbsent(IMPLEMENTATION,
            key -> createArgument(implementation, classContext(extensionContext, leaf)), SourceArgument.class).value();
    }

    private Arguments arguments(Method leaf) {
        return sourceArguments != null ? sourceArguments : CLASS_ARGUMENTS.get(leaf.getDeclaringClass().getEnclosingClass());
    }

    private record Arguments(Map<String, Implementation> byName, Set<Class<?>> types) {
        Arguments(List<Implementation> implementations) {
            this(implementations.stream().collect(toUnmodifiableMap(Implementation::name, identity())),
                implementations.stream().map(Implementation::type).collect(toUnmodifiableSet()));
        }

        boolean acceptsAll(Class<?> parameterType) {
            return types.stream().allMatch(parameterType::isAssignableFrom);
        }
    }

    /** The nearest context of the carrier owns resources, including in a class-template invocation. */
    private static ExtensionContext classContext(ExtensionContext context, Method leaf) {
        Class<?> carrier = leaf.getDeclaringClass().getEnclosingClass();
        var parent = context.getParent();
        while (parent.isPresent()) {
            ExtensionContext current = parent.orElseThrow();
            if (current.getTestClass().orElse(null) == carrier) {
                return current;
            }
            parent = current.getParent();
        }
        throw new ParameterResolutionException("No class context for " + carrier.getName());
    }

    private static SourceArgument createArgument(Implementation implementation, ExtensionContext classContext) {
        var factory = Objects.requireNonNull(implementation.factory());
        try {
            Object value = Objects.requireNonNull(factory.create(classContext.getStore(SOURCE_RESOURCES)),
                "the variant factory returned null");
            return new SourceArgument(implementation.type().cast(value));
        }
        catch (Exception e) {
            throw new ParameterResolutionException("Failed to create argument for " + implementation.name(), e);
        }
    }

    /** A source owns its resources through the class store, even if one is also an argument. */
    private record SourceArgument(Object value) {
    }
}
