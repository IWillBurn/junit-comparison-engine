package org.junit.jupiter.engine.descriptor;

import java.lang.reflect.Method;

import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.platform.engine.UniqueId;

/**
 * SPLIT PACKAGE (comparison engine, jupiter-descriptors variant). Lives in Jupiter's package only to reach the
 * package-private constructor of {@link TestMethodTestDescriptor} that takes a display name and an interceptor call.
 * Contains no logic. Works only when JUnit and this library are loaded by the same class loader (class path, not
 * module path).
 */
public abstract class ComparisonLeafDescriptor extends TestMethodTestDescriptor {

    /**
     * @param templateInvocation {@code true} for a leaf of a {@code @TestTemplate} (e.g. {@code @ParameterizedTest}):
     *     extensions see {@code interceptTestTemplateMethod} as for Jupiter's own invocations; {@code false} for a
     *     leaf of a {@code @Test}: {@code interceptTestMethod}
     */
    protected ComparisonLeafDescriptor(UniqueId uniqueId, String displayName, Class<?> testClass, Method testMethod,
            JupiterConfiguration configuration, boolean templateInvocation) {
        super(uniqueId, displayName, testClass, testMethod, configuration,
            templateInvocation ? InvocationInterceptor::interceptTestTemplateMethod
                    : InvocationInterceptor::interceptTestMethod);
    }
}
