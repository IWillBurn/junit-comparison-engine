package org.atpfivt.comparison.engine;

import static org.apiguardian.api.API.Status.INTERNAL;

import java.util.Optional;

import org.apiguardian.api.API;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;

/**
 * The {@code comparison-tests} engine, registered through ServiceLoader: runs the {@code @ComparisonTest} classes.
 * Discovery builds the published tree of the selected classes ({@link Discovery}); execution generates a carrier
 * class per class and lets the real Jupiter run it in a nested Launcher ({@link Execution}).
 */
@API(status = INTERNAL, since = "0.1")
public final class ComparisonTestEngine implements TestEngine {

    static final String ENGINE_ID = "comparison-tests";

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
        return Discovery.discover(request, uniqueId);
    }

    @Override
    public void execute(ExecutionRequest request) {
        Execution.execute(request);
    }
}
