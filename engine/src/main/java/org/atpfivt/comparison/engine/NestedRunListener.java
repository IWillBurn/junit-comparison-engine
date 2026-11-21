package org.atpfivt.comparison.engine;

import static org.junit.platform.engine.TestExecutionResult.Status.SUCCESSFUL;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.atpfivt.comparison.engine.Carrier.NestedClass;
import org.atpfivt.comparison.engine.MirrorNode.ArgumentSetNode;
import org.atpfivt.comparison.engine.MirrorNode.ClassInvocationNode;
import org.atpfivt.comparison.engine.MirrorNode.ClassNode;
import org.atpfivt.comparison.engine.MirrorNode.ImplementationNode;
import org.atpfivt.comparison.engine.MirrorNode.MethodNode;
import org.atpfivt.comparison.engine.MirrorNode.UnsupportedNode;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.reporting.FileEntry;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/** Maps Jupiter test sources and their parents to nodes in the published tree. */
final class NestedRunListener implements TestExecutionListener {

    private final TestDescriptor engine;
    private final MirrorReporter reporter;
    private final Map<String, ClassNode> carriers = new HashMap<>();
    private final Map<String, NestedClass> nestedClasses = new HashMap<>();
    /** Nested UniqueId → its mirror node, or empty if dropped; filled on first sight. */
    private final Map<UniqueId, Optional<MirrorNode>> mirrors = new HashMap<>();
    private @Nullable TestPlan plan;
    private @Nullable TestExecutionResult nestedEngineResult;

    NestedRunListener(TestDescriptor engine, MirrorReporter reporter) {
        this.engine = engine;
        this.reporter = reporter;
    }

    void add(ClassNode classNode, Carrier carrier) {
        carriers.put(carrier.type().getName(), classNode);
        carrier.nestedClasses().forEach(nested -> nestedClasses.put(nested.type().getName(), nested));
    }

    /** How the nested junit-jupiter engine failed, if it did (e.g. on a critical discovery issue). */
    Optional<TestExecutionResult> nestedEngineFailure() {
        return Optional.ofNullable(nestedEngineResult).filter(result -> result.getStatus() != SUCCESSFUL);
    }

    // Jupiter reports from several threads. Serialize mapping and reporting together.

    @Override
    public synchronized void testPlanExecutionStarted(TestPlan testPlan) {
        plan = testPlan;
    }

    @Override
    public synchronized void dynamicTestRegistered(TestIdentifier id) {
        mirror(id).ifPresent(reporter::registered);
    }

    @Override
    public synchronized void executionStarted(TestIdentifier id) {
        mirror(id).ifPresent(reporter::started);
    }

    @Override
    public synchronized void executionSkipped(TestIdentifier id, String reason) {
        mirror(id).ifPresent(node -> reporter.skipped(node, reason));
    }

    @Override
    public synchronized void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (isNestedEngine(id)) {
            nestedEngineResult = result;
        }
        else {
            mirror(id).ifPresent(node -> reporter.finished(node, result));
        }
    }

    @Override
    public synchronized void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        reportTarget(id).ifPresent(target -> reporter.reportingEntryPublished(target, entry));
    }

    @Override
    public synchronized void fileEntryPublished(TestIdentifier id, FileEntry file) {
        reportTarget(id).ifPresent(target -> reporter.fileEntryPublished(target, file));
    }

    /** Entries of the nested engine go to our engine; those of a node only while its mirror node is running. */
    private Optional<TestDescriptor> reportTarget(TestIdentifier id) {
        if (isNestedEngine(id)) {
            return Optional.of(engine);
        }
        return mirror(id).filter(reporter::isRunning).map(TestDescriptor.class::cast);
    }

    private Optional<MirrorNode> mirror(TestIdentifier id) {
        UniqueId key = id.getUniqueIdObject();
        Optional<MirrorNode> cached = mirrors.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<MirrorNode> mapped = map(id); // recurses through parents, which also update mirrors
        mirrors.put(key, mapped);
        return mapped;
    }

    private Optional<MirrorNode> map(TestIdentifier id) {
        if (isNestedEngine(id)) {
            return Optional.empty();
        }
        TestIdentifier parent = nestedPlan().getTestIdentifier(id.getParentIdObject().orElseThrow());
        if (isNestedEngine(parent)) {
            return className(id).map(carriers::get);
        }
        return mirror(parent).flatMap(parentNode -> child(parentNode, id));
    }

    /** The mirror node of a nested node below the nested counterpart of {@code parent}. */
    private Optional<MirrorNode> child(MirrorNode parent, TestIdentifier id) {
        Optional<? extends MirrorNode> candidate = switch (parent) {
            case ClassNode classNode -> isCarrierOf(classNode, id)
                    ? Optional.of(new ClassInvocationNode(classNode, invocationIndex(id), id.getDisplayName()))
                    : nestedClass(id).map(nested -> new MethodNode(classNode, nested.plan().method()));
            case ClassInvocationNode invocation ->
                    nestedClass(id).map(nested -> new MethodNode(invocation, nested.plan().method()));
            case MethodNode method -> source(id) instanceof ClassSource
                    ? Optional.of(new ArgumentSetNode(method, invocationIndex(id), id.getDisplayName()))
                    : leaf(id).map(implementation -> new ImplementationNode(method, implementation));
            case ArgumentSetNode set -> leaf(id).map(implementation -> new ImplementationNode(set, implementation));
            case ImplementationNode ignored -> Optional.empty();
            case UnsupportedNode ignored -> Optional.empty();
        };
        return candidate.flatMap(parent::child);
    }

    private boolean isCarrierOf(ClassNode classNode, TestIdentifier id) {
        return className(id).map(carriers::get).filter(classNode::equals).isPresent();
    }

    private Optional<NestedClass> nestedClass(TestIdentifier id) {
        return className(id).map(nestedClasses::get);
    }

    /** The implementation of a generated leaf method. */
    private Optional<ComparisonClass.Implementation> leaf(TestIdentifier id) {
        return source(id) instanceof MethodSource method
                ? nestedClass(id).flatMap(nested -> nested.implementationOfLeaf(method.getMethodName()))
                : Optional.empty();
    }

    private static Optional<String> className(TestIdentifier id) {
        TestSource source = source(id);
        if (source instanceof ClassSource classSource) {
            return Optional.of(classSource.getClassName());
        }
        if (source instanceof MethodSource methodSource) {
            return Optional.of(methodSource.getClassName());
        }
        return Optional.empty();
    }

    private static @Nullable TestSource source(TestIdentifier id) {
        return id.getSource().orElse(null);
    }

    /** {@code [class-template-invocation:#2]} → 2. */
    private static int invocationIndex(TestIdentifier id) {
        return Segment.index(id.getUniqueIdObject().getLastSegment().getValue())
                .orElseThrow(() -> new IllegalStateException("unexpected invocation id " + id.getUniqueId()));
    }

    private static boolean isNestedEngine(TestIdentifier id) {
        return id.getParentIdObject().isEmpty();
    }

    private TestPlan nestedPlan() {
        if (plan == null) {
            throw new IllegalStateException("an event of the nested run before its test plan");
        }
        return plan;
    }
}
