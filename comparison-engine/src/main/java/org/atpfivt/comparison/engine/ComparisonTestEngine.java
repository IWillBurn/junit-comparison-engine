package org.atpfivt.comparison.engine;

import java.util.Optional;

import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.jupiter.engine.descriptor.ComparisonLeafDescriptor;
import org.junit.jupiter.engine.descriptor.JupiterEngineDescriptor;
import org.junit.jupiter.engine.descriptor.TestMethodTestDescriptor;
import org.junit.platform.engine.DiscoveryIssue;
import org.junit.platform.engine.DiscoveryIssue.Severity;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

/**
 * The jupiter-descriptors variant: an engine that is Jupiter inside. It runs the real Jupiter discovery for its own
 * id, keeps the {@code @ComparisonTest} classes, replaces the nodes of their methods with subclasses of Jupiter's
 * internal descriptors and executes the tree with Jupiter's own executor. Jupiter reports all events itself: no
 * nested Launcher, no mirror tree, no event remapping.
 */
public final class ComparisonTestEngine implements TestEngine {

    public static final String ENGINE_ID = "comparison-tests";

    private static final String DISPLAY_NAME = "Comparison Tests";

    private final JupiterTestEngine jupiter = new JupiterTestEngine();

    @Override
    public String getId() {
        return ENGINE_ID;
    }

    @Override
    public Optional<String> getGroupId() {
        return Optional.of("org.atpfivt.comparison");
    }

    @Override
    public Optional<String> getArtifactId() {
        return Optional.of("comparison-engine");
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        if (ComparisonLeafDescriptor.class.getClassLoader() != TestMethodTestDescriptor.class.getClassLoader()) {
            // the split package would be a different runtime package: IllegalAccessError deep inside. A critical
            // issue instead: the platform reports this engine as failed and still runs the other engines
            request.getDiscoveryListener().issueEncountered(uniqueId, DiscoveryIssue.create(Severity.ERROR,
                "The comparison engine extends package-private classes of Jupiter: comparison-engine "
                        + "and junit-jupiter-engine must be loaded by the same class loader (one class path)"));
            return new EngineDescriptor(uniqueId, DISPLAY_NAME);
        }
        Selection selection = new Selection(request, uniqueId);
        JupiterEngineDescriptor jupiterRoot = (JupiterEngineDescriptor) jupiter.discover(selection.jupiterRequest(),
            uniqueId);
        selection.detectFullySelectedPlainMethods(jupiter);
        Root root = new Root(uniqueId, jupiterRoot);
        new TreeRewriter(jupiterRoot.getConfiguration(), selection, request.getDiscoveryListener(), uniqueId)
                .moveComparisonClasses(jupiterRoot, root);
        return root;
    }

    @Override
    public void execute(ExecutionRequest request) {
        if (!(request.getRootTestDescriptor() instanceof Root root)) {
            return; // not reached: the platform does not execute an engine with a critical discovery issue
        }
        JupiterEngineDescriptor jupiterRoot = root.jupiterRoot;
        // Jupiter's executor needs its own root (configuration, engine-level extension context): re-parent the
        // classes that survived the post-discovery filters and prune()
        root.getChildren().forEach(jupiterRoot::addChild);
        jupiter.execute(ExecutionRequest.create(jupiterRoot, request.getEngineExecutionListener(),
            request.getConfigurationParameters(), request.getOutputDirectoryCreator(), request.getStore(),
            request.getCancellationToken()));
    }

    /**
     * Our root in the test plan. {@link JupiterEngineDescriptor} has the display name "JUnit Jupiter" hard-coded, so
     * it is kept aside and used only for the execution (it has the same UniqueId, events are matched by id).
     */
    private static final class Root extends EngineDescriptor {

        private final JupiterEngineDescriptor jupiterRoot;

        Root(UniqueId uniqueId, JupiterEngineDescriptor jupiterRoot) {
            super(uniqueId, DISPLAY_NAME);
            this.jupiterRoot = jupiterRoot;
        }
    }
}
