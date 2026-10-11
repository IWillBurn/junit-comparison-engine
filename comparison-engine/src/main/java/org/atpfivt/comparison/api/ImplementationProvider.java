package org.atpfivt.comparison.api;

import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContext;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;

/**
 * One class-template invocation per implementation of {@link ComparisonTest}; each invocation registers a resolver
 * for its implementation.
 */
public class ImplementationProvider implements ClassTemplateInvocationContextProvider {

    @Override
    public boolean supportsClassTemplate(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        if (!AnnotationSupport.isAnnotated(testClass, ComparisonTest.class)) {
            return false; // e.g. a class template nested in a comparison class
        }
        if (AnnotationSupport.isAnnotated(testClass, ParameterizedClass.class)
                || context.getTestInstanceLifecycle().orElse(Lifecycle.PER_METHOD) == Lifecycle.PER_CLASS) {
            throw new ExtensionConfigurationException(
                "@ComparisonTest does not support @ParameterizedClass or @TestInstance(PER_CLASS)");
        }
        return true;
    }

    @Override
    public Stream<ClassTemplateInvocationContext> provideClassTemplateInvocationContexts(ExtensionContext context) {
        Class<?>[] all = AnnotationSupport.findAnnotation(context.getRequiredTestClass(), ComparisonTest.class)
                .orElseThrow().value();
        return Arrays.stream(all).map(implementation -> new ClassTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return implementation.getSimpleName();
            }

            @Override
            public List<Extension> getAdditionalExtensions() {
                return List.of(new ImplementationResolver(implementation, all));
            }
        });
    }

    /**
     * Resolves the LAST parameter whose type accepts every implementation (earlier parameters of that type stay
     * arguments of a {@code @ParameterizedTest}). One new instance per test, shared by its {@code @BeforeEach},
     * the test method and {@code @AfterEach}.
     */
    record ImplementationResolver(Class<?> implementation, Class<?>[] all) implements ParameterResolver {

        private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace
                .create(ImplementationResolver.class);

        @Override
        public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            Parameter[] parameters = parameterContext.getDeclaringExecutable().getParameters();
            for (int i = parameters.length - 1; i >= 0; i--) {
                Class<?> type = parameters[i].getType();
                if (type != Object.class && Arrays.stream(all).allMatch(type::isAssignableFrom)) {
                    return i == parameterContext.getIndex();
                }
            }
            return false;
        }

        @Override
        public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            // keyed by the implementation too: a constructor is resolved in a context shared by all implementations
            return extensionContext.getStore(NAMESPACE).computeIfAbsent(
                List.of(extensionContext.getUniqueId(), implementation),
                key -> ReflectionSupport.newInstance(implementation), Object.class);
        }
    }
}
