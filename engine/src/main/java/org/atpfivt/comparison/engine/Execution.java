package org.atpfivt.comparison.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;

import org.atpfivt.comparison.engine.MirrorNode.ClassNode;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;

/**
 * Execution: generates a carrier per selected class, runs all carriers in one nested Launcher
 * ({@link NestedJupiter}) and republishes Jupiter's events on the mirror tree ({@link NestedRunListener}).
 * A class that fails during preparation does not stop the others.
 */
final class Execution {

    private final ExecutionRequest request;
    private final MirrorReporter reporter;
    private final NestedRunListener nestedRun;

    private Execution(ExecutionRequest request) {
        this.request = request;
        this.reporter = new MirrorReporter(request.getEngineExecutionListener(), request.getRootTestDescriptor());
        this.nestedRun = new NestedRunListener(request.getRootTestDescriptor(), reporter);
    }

    static void execute(ExecutionRequest request) {
        new Execution(request).run();
    }

    private void run() {
        TestDescriptor engine = request.getRootTestDescriptor();
        reporter.started(engine);
        List<ClassNode> classes = engine.getChildren().stream().map(ClassNode.class::cast).toList();
        List<DiscoverySelector> selectors = new ArrayList<>();
        classes.forEach(classNode -> selectors.addAll(prepare(classNode)));
        Optional<TestExecutionResult> failure = runNested(selectors);
        for (ClassNode classNode : classes) {
            if (!reporter.isDone(classNode)) {
                reporter.finishNow(classNode, failure.orElseGet(() -> notExecuted(classNode)));
            }
        }
        reporter.finishNow(engine, failure.orElseGet(TestExecutionResult::successful));
    }

    private Optional<TestExecutionResult> runNested(List<DiscoverySelector> selectors) {
        if (selectors.isEmpty()) {
            return Optional.empty();
        }
        try {
            NestedJupiter.execute(selectors, request, nestedRun);
            return nestedRun.nestedEngineFailure();
        }
        catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
            return Optional.of(TestExecutionResult.failed(e));
        }
    }

    private static TestExecutionResult notExecuted(ClassNode classNode) {
        return TestExecutionResult.failed(
            new JUnitException("the nested Jupiter run did not execute " + classNode.getDisplayName()));
    }

    /** Generates the carrier of one class; returns the selectors of its nested classes for the nested run. */
    private List<DiscoverySelector> prepare(ClassNode classNode) {
        ComparisonClass model = classNode.model();
        if (!model.isValid()) {
            reporter.failed(classNode,
                new JUnitException("@ComparisonTest " + model.testClass().getName() + ": " + model.problem()));
            return List.of();
        }
        List<CarrierPlan> plans = CarrierPlan.forClass(classNode);
        if (plans.isEmpty()) {
            // only unsupported members are selected: they are skipped when the class starts
            reporter.finishNow(classNode, TestExecutionResult.successful());
            return List.of();
        }
        try {
            Carrier carrier = CarrierGenerator.generate(model, plans);
            List<DiscoverySelector> selected = carrier.selectors(model.classTemplate(), classNode.selection());
            nestedRun.add(classNode, carrier);
            return selected;
        }
        catch (Exception | LinkageError e) {
            reporter.failed(classNode, e);
            return List.of();
        }
    }
}
