package org.atpfivt.comparison.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.jupiter.engine.descriptor.TestMethodTestDescriptor;
import org.junit.jupiter.engine.descriptor.TestTemplateInvocationTestDescriptor;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoveryIssue;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryListener;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.OutputDirectoryCreator;
import org.junit.platform.engine.SelectorResolutionResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.discovery.UniqueIdSelector;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;

/**
 * Translates the request for Jupiter's discovery. Jupiter does not know the segment {@code [implementation:X]} of a
 * leaf, so a leaf selector is rewritten:
 * <ul>
 * <li>below an argument set ({@code .../[test-template-invocation:#2]/[implementation:Buggy]}): the last segment
 * becomes {@code [test-template-invocation:Buggy]}. Jupiter strips such segments itself and records the whole id in
 * the template's {@code DynamicDescendantFilter}, which then selects the argument set and the leaf exactly;</li>
 * <li>below a {@code @Test} ({@code .../[method:m(Calc)]/[implementation:Buggy]}): Jupiter knows no segments below
 * a test method, so the selector becomes {@code [method:m(Calc)]} and the implementation names are remembered
 * here. If other selectors of the request cover the whole method (class, method, prefix...), all leaves run; this is
 * found out by a second, silent Jupiter discovery of the other selectors.</li>
 * </ul>
 */
final class Selection {

    private final EngineDiscoveryRequest original;
    private final UniqueId engineId;
    private final List<DiscoverySelector> forJupiter = new ArrayList<>();
    private final List<DiscoverySelector> others = new ArrayList<>();
    private final Map<UniqueId, Set<String>> plainLeaves = new HashMap<>();
    private Set<UniqueId> fullySelectedPlainMethods = Set.of();

    Selection(EngineDiscoveryRequest original, UniqueId engineId) {
        this.original = original;
        this.engineId = engineId;
        for (DiscoverySelector selector : original.getSelectorsByType(DiscoverySelector.class)) {
            if (selector instanceof UniqueIdSelector u && isLeaf(u.getUniqueId())) {
                UniqueId parent = u.getUniqueId().removeLastSegment();
                String implementation = u.getUniqueId().getLastSegment().getValue();
                if (parent.getLastSegment().getType().equals(TestMethodTestDescriptor.SEGMENT_TYPE)) {
                    plainLeaves.computeIfAbsent(parent, k -> new HashSet<>()).add(implementation);
                    forJupiter.add(DiscoverySelectors.selectUniqueId(parent));
                    continue;
                }
                selector = DiscoverySelectors.selectUniqueId(
                    parent.append(TestTemplateInvocationTestDescriptor.SEGMENT_TYPE, implementation));
            }
            forJupiter.add(selector);
            others.add(selector);
        }
    }

    private boolean isLeaf(UniqueId id) {
        return id.hasPrefix(engineId) && id.getSegments().size() > 2
                && id.getLastSegment().getType().equals(ImplementationDescriptor.SEGMENT_TYPE);
    }

    /** The request for the real discovery: translated selectors, discovery issues of comparison classes only. */
    EngineDiscoveryRequest jupiterRequest() {
        return new Request(original, forJupiter, new ComparisonIssuesOnly(original.getDiscoveryListener()));
    }

    /** Needed only for a re-run of {@code @Test} leaves mixed with other selectors. */
    void detectFullySelectedPlainMethods(JupiterTestEngine jupiter) {
        if (plainLeaves.isEmpty() || others.isEmpty()) {
            return;
        }
        TestDescriptor tree = jupiter.discover(new Request(original, others, EngineDiscoveryListener.NOOP), engineId);
        fullySelectedPlainMethods = tree.getDescendants().stream().map(TestDescriptor::getUniqueId)
                .filter(plainLeaves::containsKey).collect(Collectors.toSet());
    }

    /** Names of the implementations to create leaves for below a {@code @Test} method. */
    Set<String> selectedLeaves(UniqueId plainMethodId, Implementations implementations) {
        Set<String> names = plainLeaves.get(plainMethodId);
        if (names == null || fullySelectedPlainMethods.contains(plainMethodId)) {
            return implementations.classes().stream().map(Class::getSimpleName).collect(Collectors.toSet());
        }
        return names;
    }

    private record Request(EngineDiscoveryRequest original, List<DiscoverySelector> selectors,
            EngineDiscoveryListener listener) implements EngineDiscoveryRequest {

        @Override
        public <T extends DiscoverySelector> List<T> getSelectorsByType(Class<T> selectorType) {
            return selectors.stream().filter(selectorType::isInstance).map(selectorType::cast).toList();
        }

        @Override
        public <T extends DiscoveryFilter<?>> List<T> getFiltersByType(Class<T> filterType) {
            return original.getFiltersByType(filterType);
        }

        @Override
        public ConfigurationParameters getConfigurationParameters() {
            return original.getConfigurationParameters();
        }

        @Override
        public EngineDiscoveryListener getDiscoveryListener() {
            return listener;
        }

        @Override
        public OutputDirectoryCreator getOutputDirectoryCreator() {
            return original.getOutputDirectoryCreator();
        }
    }

    /** The regular junit-jupiter engine reports the issues of plain classes already. */
    private record ComparisonIssuesOnly(EngineDiscoveryListener delegate) implements EngineDiscoveryListener {

        @Override
        public void selectorProcessed(UniqueId engineId, DiscoverySelector selector, SelectorResolutionResult result) {
            delegate.selectorProcessed(engineId, selector, result);
        }

        @Override
        public void issueEncountered(UniqueId engineId, DiscoveryIssue issue) {
            TestSource source = issue.source().orElse(null);
            Class<?> testClass = source instanceof ClassSource cs ? load(cs)
                    : source instanceof MethodSource ms ? load(ms) : null;
            if (testClass == null || Implementations.isComparisonClass(testClass)) {
                delegate.issueEncountered(engineId, issue);
            }
        }

        private static Class<?> load(ClassSource source) {
            try {
                return source.getJavaClass();
            }
            catch (RuntimeException e) {
                return null;
            }
        }

        private static Class<?> load(MethodSource source) {
            try {
                return source.getJavaClass();
            }
            catch (RuntimeException e) {
                return null;
            }
        }
    }
}
