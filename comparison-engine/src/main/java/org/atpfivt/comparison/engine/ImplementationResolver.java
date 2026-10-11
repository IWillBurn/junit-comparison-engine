package org.atpfivt.comparison.engine;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;

/**
 * Registered on every generated carrier class. Resolves parameters whose type accepts every implementation of the
 * class (the implementation parameter of the leaf, of {@code @BeforeEach}/{@code @AfterEach} and of the user class
 * constructor) to the implementation object of the current leaf: one new object per leaf, shared by all of them.
 *
 * <p>The user class constructor gets the leaf's implementation only because this resolver asks for the
 * {@code TEST_METHOD} extension context scope during test instantiation (MAINTAINED API since 5.13.3).
 */
public final class ImplementationResolver implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(ImplementationResolver.class);

    @Override
    public ExtensionContextScope getTestInstantiationExtensionContextScope(ExtensionContext rootContext) {
        return ExtensionContextScope.TEST_METHOD;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return implementation(extensionContext).isPresent() && implementations(extensionContext)
                .map(all -> Arrays.stream(all).allMatch(type::isAssignableFrom)).orElse(false);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = implementation(extensionContext).orElseThrow();
        // the method-level store: one object per leaf, closed with the leaf if it is AutoCloseable
        return extensionContext.getStore(NAMESPACE).computeIfAbsent(ImplementationLeaf.class,
            key -> ReflectionSupport.newInstance(type), Object.class);
    }

    private static Optional<Class<?>> implementation(ExtensionContext context) {
        return context.getTestMethod().map(m -> m.getAnnotation(ImplementationLeaf.class))
                .map(ImplementationLeaf::value);
    }

    /** All implementations of the user class (the leaf's class is nested in the carrier, which extends it). */
    private static Optional<Class<?>[]> implementations(ExtensionContext context) {
        return context.getTestMethod().map(Method::getDeclaringClass).map(Class::getEnclosingClass)
                .flatMap(carrier -> AnnotationSupport.findAnnotation(carrier, ComparisonTest.class))
                .map(ComparisonTest::value);
    }
}
