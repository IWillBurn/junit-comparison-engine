package org.atpfivt.comparison.engine;

import java.util.Set;
import java.util.function.UnaryOperator;

import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.descriptor.ComparisonNodeDescriptor;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;

/**
 * Stands for a member the engine does not support ({@code @TestFactory}, ...): a skipped leaf with the reason, so the
 * test neither runs without the implementations nor disappears silently.
 */
final class UnsupportedMethodDescriptor extends ComparisonNodeDescriptor {

    private final String reason;
    private final Set<TestTag> tags;

    private UnsupportedMethodDescriptor(UniqueId uniqueId, String displayName, TestSource source,
            JupiterConfiguration configuration, String reason, Set<TestTag> tags) {
        super(uniqueId, displayName, source, configuration);
        this.reason = reason;
        this.tags = tags;
    }

    static UnsupportedMethodDescriptor replacing(TestDescriptor original, JupiterConfiguration configuration,
            String what) {
        return new UnsupportedMethodDescriptor(original.getUniqueId(), original.getDisplayName(),
            original.getSource().orElse(null), configuration,
            what + " is not supported in a @ComparisonTest class",
            Set.copyOf(original.getTags()));
    }

    @Override
    public Type getType() {
        return Type.TEST;
    }

    @Override
    protected UnsupportedMethodDescriptor withUniqueId(UnaryOperator<UniqueId> uniqueIdTransformer) {
        return new UnsupportedMethodDescriptor(uniqueIdTransformer.apply(getUniqueId()), getDisplayName(),
            getSource().orElse(null), jupiterConfiguration(), reason, tags);
    }

    @Override
    public Set<TestTag> getTags() {
        return tags;
    }

    @Override
    public JupiterEngineExecutionContext prepare(JupiterEngineExecutionContext context) {
        return context; // no own extension context ...
    }

    @Override
    public void cleanUp(JupiterEngineExecutionContext context) {
        // ... so nothing to close here (closing would close the parent's context)
    }

    @Override
    public SkipResult shouldBeSkipped(JupiterEngineExecutionContext context) {
        return SkipResult.skip(reason);
    }
}
