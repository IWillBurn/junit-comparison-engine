package org.atpfivt.comparison.engine;

import java.lang.reflect.Method;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.descriptor.ComparisonLeafDescriptor;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.jupiter.engine.extension.MutableExtensionRegistry;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.hierarchical.ExclusiveResource;

/**
 * The leaf: one implementation. A real Jupiter test-method node (new test instance, {@code @BeforeEach}, extensions,
 * test method, {@code @AfterEach}); its only addition is the {@link ImplementationResolver} in its own registry.
 * {@code TestInfo}/{@code ExtensionContext} of the leaf report its display name, e.g. {@code Good}.
 */
final class ImplementationDescriptor extends ComparisonLeafDescriptor {

    static final String SEGMENT_TYPE = "implementation";

    private final Implementations implementations;
    private final Class<?> implementation;
    private final int index;
    private final JupiterConfiguration configuration;
    private final boolean templateInvocation;

    /**
     * @param index 1-based position in {@code @ComparisonTest#value()}; only an int can extend the CI name:
     *     {@code mul(int, int, int, Calc)[2][1]}
     */
    ImplementationDescriptor(UniqueId uniqueId, Implementations implementations, int index, Class<?> testClass,
            Method testMethod, JupiterConfiguration configuration, boolean templateInvocation) {
        super(uniqueId, implementations.classes().get(index - 1).getSimpleName(), testClass, testMethod,
            configuration, templateInvocation);
        this.implementations = implementations;
        this.implementation = implementations.classes().get(index - 1);
        this.index = index;
        this.configuration = configuration;
        this.templateInvocation = templateInvocation;
    }

    static UniqueId uniqueId(UniqueId parent, Implementations implementations, int index) {
        return parent.append(SEGMENT_TYPE, implementations.classes().get(index - 1).getSimpleName());
    }

    @Override
    protected ImplementationDescriptor withUniqueId(UnaryOperator<UniqueId> uniqueIdTransformer) {
        // copied per invocation of a @ParameterizedClass, like Jupiter's own method nodes
        return new ImplementationDescriptor(uniqueIdTransformer.apply(getUniqueId()), implementations, index,
            getTestClass(), getTestMethod(), configuration, templateInvocation);
    }

    @Override
    protected OptionalInt getLegacyReportingIndex() {
        return OptionalInt.of(index);
    }

    @Override
    protected MutableExtensionRegistry populateNewExtensionRegistry(JupiterEngineExecutionContext context) {
        MutableExtensionRegistry registry = super.populateNewExtensionRegistry(context);
        registry.registerExtension(new ImplementationResolver(implementations, implementation), this);
        return registry;
    }

    @Override
    public Set<ExclusiveResource> getExclusiveResources() {
        // resources of the method are declared by the method container, as for Jupiter's template invocations
        return Set.of();
    }
}
