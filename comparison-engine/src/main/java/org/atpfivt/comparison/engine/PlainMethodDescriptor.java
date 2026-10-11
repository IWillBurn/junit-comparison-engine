package org.atpfivt.comparison.engine;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.descriptor.TestMethodTestDescriptor;
import org.junit.jupiter.engine.descriptor.TestTemplateTestDescriptor;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.platform.engine.UniqueId;

/**
 * Replaces Jupiter's node of a {@code @Test} method: a CONTAINER with one static leaf per implementation, known
 * before the run. Reuses {@link TestTemplateTestDescriptor} for its public constructor (display name computed like
 * Jupiter's), method-level extensions and extension context; the template machinery itself is switched off.
 */
final class PlainMethodDescriptor extends TestTemplateTestDescriptor {

    private final Implementations implementations;
    private final List<Class<?>> enclosingTestClasses;
    private final JupiterConfiguration configuration;

    private PlainMethodDescriptor(UniqueId uniqueId, Class<?> testClass, Method testMethod,
            List<Class<?>> enclosingTestClasses, JupiterConfiguration configuration, Implementations implementations) {
        super(uniqueId, testClass, testMethod, () -> enclosingTestClasses, configuration);
        this.implementations = implementations;
        this.enclosingTestClasses = enclosingTestClasses;
        this.configuration = configuration;
    }

    /**
     * @param selected names of the implementations to add as leaves (all, unless the selectors asked for leaves)
     */
    static PlainMethodDescriptor replacing(TestMethodTestDescriptor original, JupiterConfiguration configuration,
            Implementations implementations, Set<String> selected) {
        PlainMethodDescriptor container = new PlainMethodDescriptor(original.getUniqueId(), original.getTestClass(),
            original.getTestMethod(), original.getEnclosingTestClasses(), configuration, implementations);
        for (int i = 1; i <= implementations.classes().size(); i++) {
            if (selected.contains(implementations.classes().get(i - 1).getSimpleName())) {
                container.addChild(new ImplementationDescriptor(
                    ImplementationDescriptor.uniqueId(container.getUniqueId(), implementations, i), implementations,
                    i, original.getTestClass(), original.getTestMethod(), configuration, false));
            }
        }
        return container;
    }

    @Override
    protected PlainMethodDescriptor withUniqueId(UnaryOperator<UniqueId> uniqueIdTransformer) {
        // children are copied by Jupiter's copyIncludingDescendants (@ParameterizedClass)
        return new PlainMethodDescriptor(uniqueIdTransformer.apply(getUniqueId()), getTestClass(), getTestMethod(),
            enclosingTestClasses, configuration, implementations);
    }

    @Override
    public boolean mayRegisterTests() {
        return false;
    }

    @Override
    public JupiterEngineExecutionContext execute(JupiterEngineExecutionContext context,
            DynamicTestExecutor dynamicTestExecutor) {
        return context; // the static leaves are executed by Jupiter's executor
    }
}
