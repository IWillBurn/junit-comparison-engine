package org.atpfivt.comparison.engine;

import static org.junit.platform.launcher.EngineFilter.includeEngines;

import java.util.List;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.launcher.LauncherConstants;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherExecutionRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/** The nested run: the real Jupiter executes the generated classes in a Launcher of its own. */
final class NestedJupiter {

    static final String ENGINE_ID = "junit-jupiter";

    private NestedJupiter() {
    }

    static void execute(List<DiscoverySelector> selectors, ExecutionRequest outer, TestExecutionListener listener) {
        LauncherDiscoveryRequest discovery = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .filters(includeEngines(ENGINE_ID))
                .parentConfigurationParameters(outer.getConfigurationParameters())
                // Keep output capture with the outer Launcher.
                .configurationParameter(LauncherConstants.CAPTURE_STDOUT_PROPERTY_NAME, "false")
                .configurationParameter(LauncherConstants.CAPTURE_STDERR_PROPERTY_NAME, "false")
                .build();
        LauncherFactory.create(config()).execute(LauncherExecutionRequestBuilder.request(discovery)
                .listeners(listener)
                // a cancelled outer run (e.g. fail-fast) skips what the nested run has not started yet
                .cancellationToken(outer.getCancellationToken())
                .build());
    }

    /**
     * Keep the outer Launcher's filters and listeners out of the nested run. Our filter hides generated classes from
     * the outer Jupiter, and the outer listeners receive events through the comparison engine.
     */
    private static LauncherConfig config() {
        return LauncherConfig.builder()
                .enablePostDiscoveryFilterAutoRegistration(false)
                .enableTestExecutionListenerAutoRegistration(false)
                .enableLauncherDiscoveryListenerAutoRegistration(false)
                .enableLauncherSessionListenerAutoRegistration(false)
                .build();
    }
}
