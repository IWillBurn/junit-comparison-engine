package org.atpfivt.comparison.engine;

import static org.junit.platform.engine.TestExecutionResult.Status.ABORTED;
import static org.junit.platform.engine.TestExecutionResult.Status.FAILED;
import static org.junit.platform.engine.TestExecutionResult.Status.SUCCESSFUL;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;

import org.atpfivt.comparison.engine.CarrierGenerator.Carrier;
import org.atpfivt.comparison.engine.CarrierGenerator.NestedClass;
import org.atpfivt.comparison.engine.CarrierGenerator.Spec;
import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.atpfivt.comparison.engine.ComparisonClass.Kind;
import org.atpfivt.comparison.engine.ComparisonClass.Member;
import org.atpfivt.comparison.engine.Node.Pattern;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.NestedClassSelector;
import org.junit.platform.engine.reporting.FileEntry;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherConstants;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Execution: generates a carrier per selected class, runs all carriers in ONE nested Launcher with only the
 * junit-jupiter engine, and republishes Jupiter's events on the mirror tree. The mapping is structural (by the test
 * sources of the generated classes), plus one informal convention: the invocation index is the {@code #k} value of
 * the last UniqueId segment of a class template invocation.
 */
final class NestedRun implements TestExecutionListener {

    /** Where a node of the nested run goes in the mirror tree. */
    private record Target(Node node, Node classNode, NestedClass nested, boolean dropped) {
    }

    private static final Target ROOT = new Target(null, null, null, false);
    private static final Target DROPPED = new Target(null, null, null, true);

    private static final class State {
        boolean started;
        boolean skipped;
        boolean finished;
        /** Nested counterparts that are not finished yet (a method may be split into several nested classes). */
        int remaining = 1;
        TestExecutionResult result = TestExecutionResult.successful();

        void merge(TestExecutionResult r) {
            if (r.getStatus() == FAILED && result.getStatus() != FAILED
                    || r.getStatus() == ABORTED && result.getStatus() == SUCCESSFUL) {
                result = r;
            }
        }
    }

    private final ExecutionRequest request;
    private final EngineExecutionListener listener;
    private final Map<String, Node> carriers = new HashMap<>();
    private final Map<String, NestedClass> nestedClasses = new HashMap<>();
    private final Map<UniqueId, Target> targets = new HashMap<>();
    private final Map<Node, State> states = new HashMap<>();
    private final List<DiscoverySelector> selectors = new ArrayList<>();
    private TestPlan plan;
    private TestExecutionResult nestedEngineFailure;

    NestedRun(ExecutionRequest request) {
        this.request = request;
        this.listener = request.getEngineExecutionListener();
    }

    void run() {
        TestDescriptor engine = request.getRootTestDescriptor();
        listener.executionStarted(engine);
        List<Node> classes = engine.getChildren().stream().map(Node.class::cast).toList();
        for (Node classNode : classes) {
            try {
                prepare(classNode);
            }
            catch (Throwable t) {
                start(classNode);
                state(classNode).merge(TestExecutionResult.failed(t));
                finish(classNode);
            }
        }
        if (!selectors.isEmpty()) {
            LauncherFactory.create(nestedLauncherConfig()).execute(LauncherDiscoveryRequestBuilder.request()
                    .selectors(selectors)
                    .parentConfigurationParameters(request.getConfigurationParameters())
                    // the outer Launcher captures the output; the nested one must not take System.out away
                    .configurationParameter(LauncherConstants.CAPTURE_STDOUT_PROPERTY_NAME, "false")
                    .configurationParameter(LauncherConstants.CAPTURE_STDERR_PROPERTY_NAME, "false")
                    .build(), this);
        }
        for (Node classNode : classes) {
            State s = state(classNode);
            if (!s.finished && !s.skipped) {
                start(classNode);
                s.merge(nestedEngineFailure != null ? nestedEngineFailure : TestExecutionResult.failed(
                    new IllegalStateException("the nested Jupiter run did not execute " + classNode.getDisplayName())));
                finish(classNode);
            }
        }
        listener.executionFinished(engine, TestExecutionResult.successful());
    }

    /** Generates the carrier of one class and adds its nested classes to the selectors of the nested run. */
    private void prepare(Node classNode) throws ReflectiveOperationException {
        ComparisonClass model = classNode.model;
        if (model.problem != null) {
            throw new IllegalArgumentException("@ComparisonTest " + model.testClass.getName() + ": " + model.problem);
        }
        List<Spec> specs = specs(classNode);
        if (specs.isEmpty()) {
            // only unsupported members selected: nothing for Jupiter to run
            start(classNode);
            finish(classNode);
            return;
        }
        Carrier carrier = CarrierGenerator.generate(model, specs);
        carriers.put(carrier.type().getName(), classNode);
        for (NestedClass nested : carrier.nestedClasses()) {
            nestedClasses.put(nested.type().getName(), nested);
            NestedClassSelector selector = DiscoverySelectors.selectNestedClass(List.of(carrier.type()), nested.type());
            List<Integer> sets = nested.spec().argumentSets();
            selectors.add(sets.isEmpty() ? selector : DiscoverySelectors.selectIteration(selector,
                sets.stream().mapToInt(i -> i - 1).toArray()));
        }
        if (!model.classTemplate) {
            for (TestDescriptor child : classNode.getChildren()) {
                Node method = (Node) child;
                if (method.kind == Node.Kind.METHOD) {
                    state(method).remaining = (int) specs.stream().filter(s -> s.member() == method.member).count();
                }
            }
        }
    }

    /**
     * What to generate: one nested class per selected method with the selected implementations. Argument sets
     * selected by UniqueId become an IterationSelector; sets that need different implementations get separate nested
     * classes. Only when "all sets of some implementations" is mixed with a single set of others, the union runs and
     * the extra leaves are dropped from the report.
     */
    private static List<Spec> specs(Node classNode) {
        ComparisonClass model = classNode.model;
        List<Spec> specs = new ArrayList<>();
        List<Member> members = new ArrayList<>();
        if (model.classTemplate) {
            model.members.stream().filter(m -> m.kind() != Kind.UNSUPPORTED)
                    .filter(m -> classNode.patterns.stream().anyMatch(p -> p.accepts(0, m.id(), 0, null)))
                    .forEach(members::add);
        }
        else {
            classNode.getChildren().stream().map(Node.class::cast).filter(n -> n.kind == Node.Kind.METHOD)
                    .forEach(n -> members.add(n.member));
        }
        for (Member member : members) {
            List<Pattern> patterns = classNode.patterns.stream().filter(p -> p.accepts(0, member.id(), 0, null))
                    .toList();
            if (member.kind() == Kind.TEST && !model.classTemplate) {
                Node method = classNode.child(member.id()).orElseThrow();
                specs.add(new Spec(member, method.getChildren().stream().map(c -> ((Node) c).implementation)
                        .toList(), List.of()));
            }
            else if (model.classTemplate || patterns.stream().anyMatch(p -> p.argumentSet() == 0)) {
                specs.add(new Spec(member, implementations(model, patterns), List.of()));
            }
            else {
                Map<Integer, List<Pattern>> perSet = new TreeMap<>();
                patterns.forEach(p -> perSet.computeIfAbsent(p.argumentSet(), k -> new ArrayList<>()).add(p));
                Map<List<Implementation>, List<Integer>> groups = new LinkedHashMap<>();
                perSet.forEach((set, ps) -> groups.computeIfAbsent(implementations(model, ps), k -> new ArrayList<>())
                        .add(set));
                groups.forEach((impls, sets) -> specs.add(new Spec(member, impls, sets)));
            }
        }
        return specs;
    }

    private static List<Implementation> implementations(ComparisonClass model, List<Pattern> patterns) {
        return model.implementations.stream().filter(i -> patterns.stream()
                .anyMatch(p -> p.implementation() == null || p.implementation().equals(i.name()))).toList();
    }

    private static LauncherConfig nestedLauncherConfig() {
        TestEngine jupiter = null;
        for (TestEngine engine : ServiceLoader.load(TestEngine.class)) {
            if (engine.getId().equals("junit-jupiter")) {
                jupiter = engine;
            }
        }
        if (jupiter == null) {
            throw new IllegalStateException("junit-jupiter-engine is not on the classpath");
        }
        // only Jupiter, and none of the auto-registered extensions of the outer Launcher (our own filter hides the
        // user classes; listeners and session listeners must not see the nested run a second time)
        return LauncherConfig.builder().enableTestEngineAutoRegistration(false).addTestEngines(jupiter)
                .enablePostDiscoveryFilterAutoRegistration(false).enableTestExecutionListenerAutoRegistration(false)
                .enableLauncherDiscoveryListenerAutoRegistration(false)
                .enableLauncherSessionListenerAutoRegistration(false).build();
    }

    // ------------------------------------------------------------------ events of the nested run

    @Override
    public synchronized void testPlanExecutionStarted(TestPlan testPlan) {
        plan = testPlan;
    }

    @Override
    public synchronized void dynamicTestRegistered(TestIdentifier id) {
        resolve(id); // creates and registers the mirror node
    }

    @Override
    public synchronized void executionStarted(TestIdentifier id) {
        Target target = resolve(id);
        if (target.node() != null) {
            start(target.node());
        }
    }

    @Override
    public synchronized void executionSkipped(TestIdentifier id, String reason) {
        Target target = resolve(id);
        if (target.node() != null) {
            State s = state(target.node());
            if (!s.started && !s.skipped) {
                s.skipped = true;
                listener.executionSkipped(target.node(), reason);
            }
        }
    }

    @Override
    public synchronized void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.getParentIdObject().isEmpty()) {
            // the nested junit-jupiter engine: fails e.g. on critical discovery issues
            nestedEngineFailure = result.getStatus() == SUCCESSFUL ? null : result;
            return;
        }
        Target target = resolve(id);
        if (target.node() != null) {
            State s = state(target.node());
            s.merge(result);
            if (--s.remaining <= 0) {
                finish(target.node());
            }
        }
    }

    @Override
    public synchronized void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        TestDescriptor target = reportTarget(id);
        if (target != null) {
            listener.reportingEntryPublished(target, entry);
        }
    }

    @Override
    public synchronized void fileEntryPublished(TestIdentifier id, FileEntry file) {
        TestDescriptor target = reportTarget(id);
        if (target != null) {
            listener.fileEntryPublished(target, file);
        }
    }

    private TestDescriptor reportTarget(TestIdentifier id) {
        Target target = resolve(id);
        if (target == ROOT) {
            return request.getRootTestDescriptor();
        }
        return target.node() != null && state(target.node()).started ? target.node() : null;
    }

    private void start(Node node) {
        State s = state(node);
        if (s.started || s.skipped) {
            return; // a further nested counterpart of the same node
        }
        s.started = true;
        listener.executionStarted(node);
        if (node.kind == Node.Kind.CLASS) {
            for (TestDescriptor child : node.getChildren()) {
                Node c = (Node) child;
                if (c.kind == Node.Kind.UNSUPPORTED) {
                    state(c).skipped = true;
                    listener.executionSkipped(c, c.member.unsupportedReason());
                }
            }
        }
    }

    /** Finishes a node; closes its open children first and reports never-reached static children as skipped. */
    private void finish(Node node) {
        State s = state(node);
        if (!s.started || s.finished) {
            return;
        }
        for (TestDescriptor child : List.copyOf(node.getChildren())) {
            Node c = (Node) child;
            State cs = state(c);
            if (cs.started && !cs.finished) {
                finish(c);
            }
            else if (!cs.started && !cs.skipped && s.result.getStatus() == SUCCESSFUL) {
                cs.skipped = true;
                listener.executionSkipped(c, "not executed by the nested Jupiter run");
            }
        }
        s.finished = true;
        listener.executionFinished(node, s.result);
    }

    private State state(Node node) {
        return states.computeIfAbsent(node, k -> new State());
    }

    // ------------------------------------------------------------------ nested node -> mirror node

    private Target resolve(TestIdentifier id) {
        Target target = targets.get(id.getUniqueIdObject());
        if (target == null) {
            target = compute(id);
            targets.put(id.getUniqueIdObject(), target);
        }
        return target;
    }

    private Target compute(TestIdentifier id) {
        if (id.getParentIdObject().isEmpty()) {
            return ROOT;
        }
        Target parent = resolve(plan.getTestIdentifier(id.getParentIdObject().get()));
        if (parent.dropped()) {
            return DROPPED;
        }
        TestSource source = id.getSource().orElse(null);
        if (source instanceof ClassSource cs) {
            Node classNode = carriers.get(cs.getClassName());
            if (classNode != null && parent == ROOT) {
                return new Target(classNode, classNode, null, false);
            }
            if (classNode != null && parent.node() == classNode) {
                // an invocation of the user's own @ParameterizedClass (inherited by the carrier)
                int index = index(id);
                return accepted(classNode, index, null, 0, null)
                        ? register(classNode.classInvocation(index, id.getDisplayName()), parent, null) : DROPPED;
            }
            NestedClass nested = nestedClasses.get(cs.getClassName());
            if (nested != null && parent.node() != null && parent.nested() == null) {
                // the generated @Nested class: the user's method
                Node container = parent.node();
                Member member = nested.spec().member();
                if (container.kind == Node.Kind.CLASS) {
                    return new Target(container.child(member.id()).orElseThrow(), parent.classNode(), nested, false);
                }
                return accepted(parent.classNode(), container.classInvocation, member.id(), 0, null)
                        ? register(container.member(member), parent, nested) : DROPPED;
            }
            if (nested != null && parent.nested() == nested) {
                // an invocation of the generated @ParameterizedClass: the argument set
                Node method = parent.node();
                int index = index(id);
                return accepted(parent.classNode(), method.classInvocation, method.member.id(), index, null)
                        ? register(method.argumentSet(index, id.getDisplayName()), parent, nested) : DROPPED;
            }
        }
        if (source instanceof MethodSource ms && parent.nested() != null
                && ms.getClassName().equals(parent.nested().type().getName())) {
            Implementation impl = parent.nested().leaves().get(ms.getMethodName());
            Node container = parent.node();
            if (impl == null) {
                return DROPPED;
            }
            if (container.kind == Node.Kind.METHOD && container.classInvocation == 0) {
                // leaf of a @Test method: static, created at discovery
                return new Target(container.child(impl.name()).orElseThrow(), parent.classNode(), null, false);
            }
            return accepted(parent.classNode(), container.classInvocation, container.member.id(),
                container.argumentSet, impl.name()) ? register(container.implementation(impl), parent, null) : DROPPED;
        }
        return DROPPED;
    }

    /** A dynamic node of the mirror tree, registered with the outer Launcher before it starts. */
    private Target register(Node node, Target parent, NestedClass nested) {
        listener.dynamicTestRegistered(node);
        return new Target(node, parent.classNode(), nested, false);
    }

    private static boolean accepted(Node classNode, int classInvocation, String member, int argumentSet,
            String implementation) {
        return classNode.patterns.stream().anyMatch(p -> p.accepts(classInvocation, member, argumentSet, implementation));
    }

    /** {@code [class-template-invocation:#2]} gives 2 (an informal convention of Jupiter's UniqueIds). */
    private static int index(TestIdentifier id) {
        String value = id.getUniqueIdObject().getLastSegment().getValue();
        try {
            return Integer.parseInt(value.startsWith("#") ? value.substring(1) : value);
        }
        catch (NumberFormatException e) {
            throw new IllegalStateException("unexpected invocation id " + id.getUniqueId(), e);
        }
    }
}
