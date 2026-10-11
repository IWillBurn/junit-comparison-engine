package org.atpfivt.comparison.engine;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.ReflectionSupport;

/**
 * Registered in the extension registry of one leaf: passes the leaf's implementation to the last parameter whose type
 * accepts all implementations (constructor, {@code @BeforeEach}, test method, {@code @AfterEach}). The object is
 * created on first use and shared by all of them.
 */
final class ImplementationResolver implements ParameterResolver {

    private final Implementations implementations;
    private final Class<?> implementation;
    private Object instance;

    ImplementationResolver(Implementations implementations, Class<?> implementation) {
        this.implementations = implementations;
        this.implementation = implementation;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?>[] types = parameterContext.getDeclaringExecutable().getParameterTypes();
        return implementations.implementationParameterIndex(types) == parameterContext.getIndex();
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        if (instance == null) {
            try {
                instance = ReflectionSupport.newInstance(implementation);
            }
            catch (RuntimeException e) {
                throw new ParameterResolutionException("Cannot create implementation " + implementation.getName(), e);
            }
        }
        return instance;
    }
}
