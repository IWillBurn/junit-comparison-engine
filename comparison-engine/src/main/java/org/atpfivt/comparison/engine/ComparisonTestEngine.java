package org.atpfivt.comparison.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.Filter;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor.Type;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.UniqueIdSelector;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherExecutionRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import org.atpfivt.comparison.engine.Node.Root;

/**
 * TestEngine {@value #ID}. Discovery runs a nested Jupiter discovery and mirrors every {@code @ComparisonTest} class
 * as {@code class > method > [argument set] > implementation}; execution runs the real Jupiter engine through a
 * nested Launcher, where every implementation is one hidden invocation of the class template, and {@link Remapper}
 * moves its events onto the mirror.
 */
public final class ComparisonTestEngine implements TestEngine {

    static final String ID = "comparison-tests";
    static final String JUPITER = "junit-jupiter";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        Root root = new Root(uniqueId);
        for (DiscoverySelector selector : request.getSelectorsByType(DiscoverySelector.class)) {
            if (!(selector instanceof UniqueIdSelector u)) {
                root.selectors.add(selector);
                root.unrestricted = true;
            }
            else if (u.getUniqueId().hasPrefix(uniqueId) && !u.getUniqueId().equals(uniqueId)) {
                // our own id (an IDE re-run): the segments are Jupiter's, plus [implementation:x] on leaves
                UniqueId id = u.getUniqueId();
                boolean leaf = id.getLastSegment().getType().equals(Node.IMPLEMENTATION);
                UniqueId jupiterId = UniqueId.forEngine(JUPITER);
                List<UniqueId.Segment> segments = id.getSegments();
                for (UniqueId.Segment s : segments.subList(uniqueId.getSegments().size(), segments.size() - (leaf ? 1 : 0))) {
                    jupiterId = jupiterId.append(s);
                }
                root.selectors.add(DiscoverySelectors.selectUniqueId(jupiterId));
                root.restrictions.add(new Root.Restriction(jupiterId, leaf ? id.getLastSegment().getValue() : null));
            }
        }
        request.getFiltersByType(DiscoveryFilter.class).forEach(root.filters::add);
        if (root.selectors.isEmpty()) {
            return root;
        }
        Spy spy = new Spy();
        try (LauncherSession session = openSession()) {
            session.getLauncher().discover(innerRequest(root, request.getConfigurationParameters()).filters(spy).build());
        }
        for (TestDescriptor testClass : spy.children(UniqueId.forEngine(JUPITER))) {
            mirror(root, testClass, spy);
        }
        return root;
    }

    /** class > method > static leaves of @Test methods; argument sets and their leaves appear at execution. */
    private static void mirror(Root root, TestDescriptor testClass, Spy spy) {
        Optional<ComparisonTest> annotation = testClass.getSource().filter(ClassSource.class::isInstance)
                .flatMap(s -> AnnotationSupport.findAnnotation(((ClassSource) s).getJavaClass(), ComparisonTest.class));
        if (annotation.isEmpty() || !testClass.getUniqueId().getLastSegment().getType().equals("class-template")) {
            return;
        }
        Node classNode = new Node(root.outer(testClass.getUniqueId()), testClass.getDisplayName(),
            testClass.getSource().orElse(null), Type.CONTAINER, false, testClass.getLegacyReportingName());
        classNode.implementations = Arrays.stream(annotation.get().value()).map(Class::getSimpleName).toList();
        root.add(root, classNode);
        for (TestDescriptor method : spy.children(testClass.getUniqueId())) {
            boolean template = method.getUniqueId().getLastSegment().getType().equals("test-template");
            if (!method.isTest() && !template) {
                unsupported(root, classNode, method);
                continue;
            }
            Node methodNode = new Node(root.outer(method.getUniqueId()), method.getDisplayName(),
                method.getSource().orElse(null), Type.CONTAINER, template, method.getLegacyReportingName());
            for (String implementation : template ? List.<String>of() : classNode.implementations) {
                if (root.allowed(method.getUniqueId(), implementation)) {
                    methodNode.addChild(methodNode.leaf(implementation));
                }
            }
            if (template ? root.allowed(method.getUniqueId(), null) : !methodNode.getChildren().isEmpty()) {
                root.add(classNode, methodNode);
            }
        }
    }

    /** {@code @Nested}, {@code @TestFactory}...: shown as a skipped leaf instead of being lost silently. */
    private static void unsupported(Root root, Node classNode, TestDescriptor member) {
        if (root.allowed(member.getUniqueId(), null)) {
            Node node = new Node(root.outer(member.getUniqueId()), member.getDisplayName(),
                member.getSource().orElse(null), Type.TEST, false, member.getLegacyReportingName());
            node.skipReason = "not supported by this prototype (only @Test and @ParameterizedTest)";
            root.add(classNode, node);
        }
    }

    @Override
    public void execute(ExecutionRequest request) {
        Root root = (Root) request.getRootTestDescriptor();
        EngineExecutionListener out = request.getEngineExecutionListener();
        out.executionStarted(root);
        if (!root.getChildren().isEmpty()) {
            Remapper remapper = new Remapper(root, out);
            try (LauncherSession session = openSession()) {
                session.getLauncher().execute(LauncherExecutionRequestBuilder
                        .executionRequest(innerRequest(root, request.getConfigurationParameters())
                                .filters(onlyMirrored(root)).build())
                        .listeners(remapper).build());
            }
            remapper.finishAll();
        }
        out.executionFinished(root, TestExecutionResult.successful());
    }

    /** The outer selectors (own UniqueIds translated to Jupiter's) for the nested Jupiter run. */
    private static LauncherDiscoveryRequestBuilder innerRequest(Root root, ConfigurationParameters configuration) {
        return LauncherDiscoveryRequestBuilder.discoveryRequest()
                .selectors(root.selectors)
                .filters(root.filters.toArray(Filter<?>[]::new))
                .parentConfigurationParameters(configuration)
                .enableImplicitConfigurationParameters(false) // already part of the outer parameters
                .configurationParameter("junit.platform.output.capture.stdout", "false") // the outer Launcher does it
                .configurationParameter("junit.platform.output.capture.stderr", "false");
    }

    /**
     * The nested run executes only what the mirror shows: not the plain classes of the same request (the outer
     * Jupiter runs them), not unsupported members, not what an outer filter (e.g. Surefire's -Dtest) removed.
     * Before prune, Jupiter's ids are {@code [engine]/[class-template:C]/[member]}.
     */
    private static PostDiscoveryFilter onlyMirrored(Root root) {
        return descriptor -> {
            List<UniqueId.Segment> segments = descriptor.getUniqueId().getSegments();
            UniqueId id = root.getUniqueId();
            for (UniqueId.Segment s : segments.subList(1, Math.min(segments.size(), 3))) {
                id = id.append(s);
            }
            Node node = root.nodes.get(id);
            return node != null && root.attached(node) && node.skipReason == null
                    ? FilterResult.included("in the comparison tree")
                    : FilterResult.excluded("not in the comparison tree");
        };
    }

    /** A Launcher with only Jupiter and no auto-registered filters/listeners (not even our HideFromJupiterFilter). */
    private static LauncherSession openSession() {
        TestEngine jupiter = ServiceLoader.load(TestEngine.class).stream().map(ServiceLoader.Provider::get)
                .filter(engine -> engine.getId().equals(JUPITER)).findFirst()
                .orElseThrow(() -> new IllegalStateException("junit-jupiter-engine is not on the class path"));
        return LauncherFactory.openSession(LauncherConfig.builder()
                .enableTestEngineAutoRegistration(false)
                .addTestEngines(jupiter)
                .enablePostDiscoveryFilterAutoRegistration(false)
                .enableTestExecutionListenerAutoRegistration(false)
                .enableLauncherSessionListenerAutoRegistration(false)
                .enableLauncherDiscoveryListenerAutoRegistration(false)
                .build());
    }

    /**
     * Records Jupiter's tree before {@code prune()}, which moves the methods of a class template into invisible
     * prototypes. That PostDiscoveryFilters run before prune is undocumented Launcher behaviour (JUnit 6.1.3).
     */
    private static final class Spy implements PostDiscoveryFilter {

        private final Map<UniqueId, List<TestDescriptor>> children = new HashMap<>();

        @Override
        public FilterResult apply(TestDescriptor descriptor) {
            descriptor.getParent().ifPresent(
                p -> children.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(descriptor));
            return FilterResult.included("recorded");
        }

        List<TestDescriptor> children(UniqueId parent) {
            return children.getOrDefault(parent, List.of());
        }
    }
}
