package org.atpfivt.comparison.engine;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.descriptor.DynamicDescendantFilter;
import org.junit.jupiter.engine.descriptor.TestTemplateInvocationTestDescriptor;
import org.junit.jupiter.engine.descriptor.TestTemplateTestDescriptor;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.engine.UniqueId;

/**
 * Replaces Jupiter's node of a {@code @TestTemplate} method ({@code @ParameterizedTest}, {@code @RepeatedTest}, ...).
 * Everything up to the invocations is Jupiter's own: the extension context of the template, the providers (the
 * {@code @*Source} of junit-jupiter-params is evaluated once) and the filter of the UniqueId/iteration selectors.
 * Only the invocation node differs: an {@link ArgumentSetDescriptor} container instead of a test.
 */
final class TemplateMethodDescriptor extends TestTemplateTestDescriptor {

    private final Implementations implementations;
    private final List<Class<?>> enclosingTestClasses;
    private final JupiterConfiguration configuration;
    private final DynamicDescendantFilter filter;

    TemplateMethodDescriptor(UniqueId uniqueId, Class<?> testClass, Method templateMethod,
            List<Class<?>> enclosingTestClasses, JupiterConfiguration configuration, Implementations implementations,
            DynamicDescendantFilter filter) {
        super(uniqueId, testClass, templateMethod, () -> enclosingTestClasses, configuration);
        this.implementations = implementations;
        this.enclosingTestClasses = enclosingTestClasses;
        this.configuration = configuration;
        this.filter = filter;
    }

    /** Takes over Jupiter's node, including what the selectors allowed below it. */
    static TemplateMethodDescriptor replacing(TestTemplateTestDescriptor original, JupiterConfiguration configuration,
            Implementations implementations) {
        return new TemplateMethodDescriptor(original.getUniqueId(), original.getTestClass(), original.getTestMethod(),
            original.getEnclosingTestClasses(), configuration, implementations,
            original.getDynamicDescendantFilter().copy(UnaryOperator.identity()));
    }

    JupiterConfiguration jupiterConfiguration() {
        return configuration;
    }

    @Override
    public DynamicDescendantFilter getDynamicDescendantFilter() {
        return filter;
    }

    @Override
    protected TemplateMethodDescriptor withUniqueId(UnaryOperator<UniqueId> uniqueIdTransformer) {
        // copied per invocation of a @ParameterizedClass, like Jupiter's own method nodes
        return new TemplateMethodDescriptor(uniqueIdTransformer.apply(getUniqueId()), getTestClass(), getTestMethod(),
            enclosingTestClasses, configuration, implementations, filter.copy(uniqueIdTransformer));
    }

    /** The loop of Jupiter's package-private {@code TemplateExecutor}, creating argument-set containers. */
    @Override
    public JupiterEngineExecutionContext execute(JupiterEngineExecutionContext context,
            DynamicTestExecutor dynamicTestExecutor) {
        ExtensionContext extensionContext = context.getExtensionContext();
        List<TestTemplateInvocationContextProvider> providers = context.getExtensionRegistry()
                .stream(TestTemplateInvocationContextProvider.class)
                .filter(provider -> provider.supportsTestTemplate(extensionContext))
                .toList();
        if (providers.isEmpty()) {
            throw new PreconditionViolationException("You must register at least one %s that supports @%s method [%s]"
                    .formatted(TestTemplateInvocationContextProvider.class.getSimpleName(),
                        TestTemplate.class.getSimpleName(), getTestMethod()));
        }
        AtomicInteger index = new AtomicInteger();
        for (TestTemplateInvocationContextProvider provider : providers) {
            int before = index.get();
            // forEach like Jupiter: each argument set runs before the next one is pulled from the source
            try (Stream<? extends TestTemplateInvocationContext> invocations = provider
                    .provideTestTemplateInvocationContexts(extensionContext)) {
                invocations.forEach(invocation -> {
                    int i = index.incrementAndGet();
                    UniqueId setId = getUniqueId().append(TestTemplateInvocationTestDescriptor.SEGMENT_TYPE, "#" + i);
                    if (filter.test(setId, i - 1)) {
                        ArgumentSetDescriptor set = new ArgumentSetDescriptor(setId, invocation, i, this,
                            implementations, filter);
                        set.setParent(this);
                        dynamicTestExecutor.execute(set);
                    }
                });
            }
            if (index.get() == before && !provider.mayReturnZeroTestTemplateInvocationContexts(extensionContext)) {
                throw new PreconditionViolationException("Provider [%s] did not provide any invocation contexts, but "
                        .formatted(provider.getClass().getSimpleName()) + "was expected to do so. You may override "
                        + "mayReturnZeroTestTemplateInvocationContexts() to allow this.");
            }
        }
        return context;
    }
}
