package org.atpfivt.comparison.engine;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;

/** Gives a generated argument source access to the original method's parameter resolvers. */
record SourceParameterResolver(Class<?> testClass, Method testMethod, List<Class<? extends ParameterResolver>> types)
        implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(SourceParameterResolver.class);

    static Optional<SourceParameterResolver> forMethod(Class<?> testClass, Method testMethod) {
        List<Class<? extends ParameterResolver>> types =
                AnnotationSupport.findRepeatableAnnotations(testMethod, ExtendWith.class).stream()
                        .flatMap(extension -> Arrays.stream(extension.value()))
                        .filter(ParameterResolver.class::isAssignableFrom)
                        .<Class<? extends ParameterResolver>>map(type -> type.asSubclass(ParameterResolver.class))
                        .distinct().toList();
        return types.isEmpty() ? Optional.empty() : Optional.of(new SourceParameterResolver(testClass, testMethod, types));
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        if (context.getTestMethod().isPresent() || !(parameter.getDeclaringExecutable() instanceof Method)) {
            return false;
        }
        SourceContext source = context.getStore(NAMESPACE).computeIfAbsent(this,
            key -> new SourceContext(userContext(context),
                types.stream().<ParameterResolver>map(ReflectionSupport::newInstance).toList()), SourceContext.class);
        List<ParameterResolver> matches = source.resolvers().stream()
                .filter(resolver -> resolver.supportsParameter(parameter, source.context())).toList();
        if (matches.size() > 1) {
            throw new ParameterResolutionException("Multiple method-level resolvers for " + parameter.getParameter()
                    + ": " + matches);
        }
        if (matches.isEmpty()) {
            return false;
        }
        context.getStore(NAMESPACE).put(parameter, new Binding(source.context(), matches.getFirst()));
        return true;
    }

    @Override
    public @Nullable Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        Binding binding = Objects.requireNonNull(context.getStore(NAMESPACE).remove(parameter, Binding.class));
        return binding.resolver().resolveParameter(parameter, binding.context());
    }

    /** Source evaluation still has Jupiter's own Store and invoker, with the user's test metadata. */
    private ExtensionContext userContext(ExtensionContext context) {
        return (ExtensionContext) Proxy.newProxyInstance(ExtensionContext.class.getClassLoader(),
            new Class<?>[] { ExtensionContext.class }, (proxy, method, arguments) -> switch (method.getName()) {
                case "getElement", "getTestMethod" -> Optional.of(testMethod);
                case "getRequiredTestMethod" -> testMethod;
                case "getTestClass" -> Optional.of(testClass);
                case "getRequiredTestClass" -> testClass;
                default -> {
                    try {
                        yield method.invoke(context, arguments);
                    }
                    catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            });
    }

    private record SourceContext(ExtensionContext context, List<ParameterResolver> resolvers) {
    }

    private record Binding(ExtensionContext context, ParameterResolver resolver) {
    }
}
