package org.atpfivt.comparison.engine;

import static org.junit.jupiter.engine.support.JupiterThrowableCollectorFactory.createThrowableCollector;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.engine.descriptor.ComparisonNodeDescriptor;
import org.junit.jupiter.engine.descriptor.DynamicDescendantFilter;
import org.junit.jupiter.engine.descriptor.TestTemplateInvocationTestDescriptor;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.jupiter.engine.extension.MutableExtensionRegistry;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.engine.support.hierarchical.ThrowableCollector;

/**
 * One invocation of a {@code @TestTemplate} (an argument set of a {@code @ParameterizedTest}) as a CONTAINER with one
 * dynamic leaf per implementation. It takes over what Jupiter's invocation node does: the invocation's extensions
 * (e.g. the resolver of the arguments) and {@code prepareInvocation} belong to this node, so the leaves share them
 * and auto-closed arguments are closed after the last implementation.
 */
final class ArgumentSetDescriptor extends ComparisonNodeDescriptor {

    private TestTemplateInvocationContext invocation;
    private final int index;
    private final Class<?> testClass;
    private final Method testMethod;
    private final Implementations implementations;
    private final DynamicDescendantFilter filter;

    ArgumentSetDescriptor(UniqueId uniqueId, TestTemplateInvocationContext invocation, int index,
            TemplateMethodDescriptor template, Implementations implementations, DynamicDescendantFilter filter) {
        super(uniqueId, invocation.getDisplayName(index),
            MethodSource.from(template.getTestClass(), template.getTestMethod()), template.jupiterConfiguration());
        this.invocation = invocation;
        this.index = index;
        this.testClass = template.getTestClass();
        this.testMethod = template.getTestMethod();
        this.implementations = implementations;
        this.filter = filter;
    }

    @Override
    public Type getType() {
        return Type.CONTAINER;
    }

    @Override
    public boolean mayRegisterTests() {
        return true;
    }

    @Override
    public Set<TestTag> getTags() {
        // the tags of the method and the class, as for Jupiter's invocation nodes (the leaves add the parent's tags)
        return getParent().map(parent -> (Set<TestTag>) new LinkedHashSet<>(parent.getTags())).orElseGet(Set::of);
    }

    @Override
    protected OptionalInt getLegacyReportingIndex() {
        return OptionalInt.of(index);
    }

    @Override
    protected ArgumentSetDescriptor withUniqueId(UnaryOperator<UniqueId> uniqueIdTransformer) {
        // created during execution only, never part of a prototype tree
        throw new UnsupportedOperationException("an argument set is never copied");
    }

    @Override
    public JupiterEngineExecutionContext prepare(JupiterEngineExecutionContext context) {
        // same steps as Jupiter's ClassTemplateInvocationTestDescriptor#prepare
        MutableExtensionRegistry registry = MutableExtensionRegistry.createRegistryFrom(context.getExtensionRegistry(),
            Stream.empty());
        TestTemplateInvocationContext invocation = this.invocation;
        invocation.getAdditionalExtensions().forEach(extension -> registry.registerExtension(extension, invocation));
        ThrowableCollector throwableCollector = createThrowableCollector();
        ExtensionContext extensionContext = createExtensionContext(context, registry, throwableCollector, testClass,
            testMethod);
        throwableCollector.execute(() -> invocation.prepareInvocation(extensionContext));
        return context.extend()
                .withExtensionRegistry(registry)
                .withExtensionContext(extensionContext)
                .withThrowableCollector(throwableCollector)
                .build();
    }

    @Override
    public SkipResult shouldBeSkipped(JupiterEngineExecutionContext context) {
        // conditions are evaluated by every leaf, as for Jupiter's invocation nodes
        context.getThrowableCollector().assertEmpty();
        return SkipResult.doNotSkip();
    }

    @Override
    public JupiterEngineExecutionContext execute(JupiterEngineExecutionContext context,
            DynamicTestExecutor dynamicTestExecutor) {
        // false only if the set was selected by an index alone (IterationSelector): then all implementations run;
        // otherwise the same filter decides per leaf (everything allowed, or a re-run of the set or of some leaves)
        boolean byUniqueId = filter.withoutIndexFiltering().test(getUniqueId(), index - 1);
        for (int i = 1; i <= implementations.classes().size(); i++) {
            UniqueId leafId = ImplementationDescriptor.uniqueId(getUniqueId(), implementations, i);
            if (!byUniqueId || filter.withoutIndexFiltering().test(asJupiterId(leafId), i - 1)) {
                ImplementationDescriptor leaf = new ImplementationDescriptor(leafId, implementations, i, testClass,
                    testMethod, jupiterConfiguration(), true);
                leaf.setParent(this);
                dynamicTestExecutor.execute(leaf); // [1] Good, [1] Buggy, [2] Good, ... one after another
            }
        }
        return context;
    }

    @Override
    public void after(JupiterEngineExecutionContext context) {
        invocation = null; // let the arguments be garbage collected, as Jupiter does
    }

    /**
     * Jupiter knows only its own segment types below a template: a leaf selector is passed to it with the segment
     * type {@code test-template-invocation} (see {@link Selection}), so its filter holds ids in that form.
     */
    static UniqueId asJupiterId(UniqueId leafId) {
        return leafId.removeLastSegment()
                .append(TestTemplateInvocationTestDescriptor.SEGMENT_TYPE, leafId.getLastSegment().getValue());
    }
}
