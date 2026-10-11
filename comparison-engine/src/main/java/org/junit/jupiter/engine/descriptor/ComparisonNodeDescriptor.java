package org.junit.jupiter.engine.descriptor;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstances;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.jupiter.engine.extension.MutableExtensionRegistry;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.hierarchical.Node;
import org.junit.platform.engine.support.hierarchical.ThrowableCollector;

/**
 * SPLIT PACKAGE (comparison engine, jupiter-descriptors variant). Lives in Jupiter's package only to reach the
 * package-private constructor of {@link JupiterTestDescriptor} and the package-private
 * {@link AbstractExtensionContext}. Being a {@code JupiterTestDescriptor} keeps Jupiter's inheritance of the execution
 * mode and of the legacy reporting indexes ({@code [2][1]}) working through this node. Works only when JUnit and this
 * library are loaded by the same class loader (class path, not module path).
 */
public abstract class ComparisonNodeDescriptor extends JupiterTestDescriptor {

    protected ComparisonNodeDescriptor(UniqueId uniqueId, String displayName, TestSource source,
            JupiterConfiguration configuration) {
        super(uniqueId, displayName, source, configuration);
    }

    protected final JupiterConfiguration jupiterConfiguration() {
        return configuration;
    }

    /**
     * A new extension context of this node below the parent's one: own store (closed in {@code cleanUp}), the test
     * method as element, the test instances of the parent.
     */
    protected final ExtensionContext createExtensionContext(JupiterEngineExecutionContext parent,
            MutableExtensionRegistry registry, ThrowableCollector throwableCollector, Class<?> testClass,
            Method testMethod) {
        return new NodeExtensionContext(parent.getExtensionContext(), parent, this, registry, throwableCollector,
            testClass, testMethod);
    }

    private static final class NodeExtensionContext extends AbstractExtensionContext<ComparisonNodeDescriptor> {

        private final ThrowableCollector throwableCollector;
        private final Class<?> testClass;
        private final Method testMethod;

        NodeExtensionContext(ExtensionContext parent, JupiterEngineExecutionContext context,
                ComparisonNodeDescriptor descriptor, MutableExtensionRegistry registry,
                ThrowableCollector throwableCollector, Class<?> testClass, Method testMethod) {
            super(parent, context.getExecutionListener(), descriptor, context.getConfiguration(), registry,
                context.getLauncherStoreFacade());
            this.throwableCollector = throwableCollector;
            this.testClass = testClass;
            this.testMethod = testMethod;
        }

        @Override
        public Optional<AnnotatedElement> getElement() {
            return Optional.of(testMethod);
        }

        @Override
        public Optional<Class<?>> getTestClass() {
            return Optional.of(testClass);
        }

        @Override
        public List<Class<?>> getEnclosingTestClasses() {
            return getParent().map(ExtensionContext::getEnclosingTestClasses).orElse(List.of());
        }

        @Override
        public Optional<Lifecycle> getTestInstanceLifecycle() {
            return getParent().flatMap(ExtensionContext::getTestInstanceLifecycle);
        }

        @Override
        public Optional<Object> getTestInstance() {
            return getParent().flatMap(ExtensionContext::getTestInstance);
        }

        @Override
        public Optional<TestInstances> getTestInstances() {
            return getParent().flatMap(ExtensionContext::getTestInstances);
        }

        @Override
        public Optional<Method> getTestMethod() {
            return Optional.of(testMethod);
        }

        @Override
        public Optional<Throwable> getExecutionException() {
            return Optional.ofNullable(throwableCollector.getThrowable());
        }

        @Override
        protected Node.ExecutionMode getPlatformExecutionMode() {
            return getTestDescriptor().getExecutionMode();
        }
    }
}
