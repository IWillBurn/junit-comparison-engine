package org.atpfivt.comparison.engine;

import java.util.List;
import java.util.Optional;

import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor;
import org.junit.jupiter.engine.descriptor.ClassTemplateInvocationTestDescriptor;
import org.junit.jupiter.engine.descriptor.TestFactoryTestDescriptor;
import org.junit.jupiter.engine.descriptor.TestMethodTestDescriptor;
import org.junit.jupiter.engine.descriptor.TestTemplateTestDescriptor;
import org.junit.platform.engine.DiscoveryIssue;
import org.junit.platform.engine.DiscoveryIssue.Severity;
import org.junit.platform.engine.EngineDiscoveryListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;

/**
 * Rebuilds the tree discovered by Jupiter: keeps the {@code @ComparisonTest} classes and replaces the nodes of their
 * methods. Class nodes (also {@code @Nested}, {@code @ParameterizedClass} and its invocations) stay Jupiter's own.
 */
final class TreeRewriter {

    private final JupiterConfiguration configuration;
    private final Selection selection;
    private final EngineDiscoveryListener listener;
    private final UniqueId engineId;

    TreeRewriter(JupiterConfiguration configuration, Selection selection, EngineDiscoveryListener listener,
            UniqueId engineId) {
        this.configuration = configuration;
        this.selection = selection;
        this.listener = listener;
        this.engineId = engineId;
    }

    /** Moves the comparison classes from Jupiter's root to ours; plain classes stay with the junit-jupiter engine. */
    void moveComparisonClasses(TestDescriptor jupiterRoot, TestDescriptor ourRoot) {
        for (TestDescriptor node : List.copyOf(jupiterRoot.getChildren())) {
            jupiterRoot.removeChild(node);
            if (node instanceof ClassBasedTestDescriptor c) {
                implementationsOf(c).ifPresent(implementations -> {
                    rewriteChildren(c, implementations);
                    ourRoot.addChild(c);
                });
            }
        }
    }

    private Optional<Implementations> implementationsOf(ClassBasedTestDescriptor node) {
        Optional<ComparisonTest> annotation = Implementations.annotationOf(node.getTestClass());
        try {
            return annotation.map(a -> Implementations.of(a, node.getTestClass()));
        }
        catch (IllegalArgumentException e) {
            // a critical discovery issue: the platform reports the engine as failed and runs nothing in it
            listener.issueEncountered(engineId, DiscoveryIssue.builder(Severity.ERROR, e.getMessage())
                    .source(ClassSource.from(node.getTestClass())).build());
            return Optional.empty();
        }
    }

    /** Replaces the children in place, keeping Jupiter's order ({@code @TestMethodOrder} is already applied). */
    private void rewriteChildren(TestDescriptor parent, Implementations implementations) {
        List<TestDescriptor> children = List.copyOf(parent.getChildren());
        // replace while the originals still have their parent (enclosing classes, inherited tags)
        List<TestDescriptor> replacements = children.stream().map(child -> rewrite(child, implementations)).toList();
        children.forEach(parent::removeChild);
        replacements.forEach(parent::addChild);
    }

    private TestDescriptor rewrite(TestDescriptor node, Implementations implementations) {
        if (node instanceof TestFactoryTestDescriptor) {
            return UnsupportedMethodDescriptor.replacing(node, configuration, "@TestFactory");
        }
        if (node instanceof TestTemplateTestDescriptor template) {
            return TemplateMethodDescriptor.replacing(template, configuration, implementations);
        }
        if (node instanceof TestMethodTestDescriptor test) {
            return PlainMethodDescriptor.replacing(test, configuration, implementations,
                selection.selectedLeaves(test.getUniqueId(), implementations));
        }
        if (node instanceof ClassBasedTestDescriptor nested) { // @Nested
            Optional<Implementations> own = implementationsOf(nested);
            if (own.isEmpty()) {
                return UnsupportedMethodDescriptor.replacing(node, configuration, "this nested class");
            }
            rewriteChildren(nested, own.get());
            return nested;
        }
        if (node instanceof ClassTemplateInvocationTestDescriptor invocation) { // @ParameterizedClass, re-run by id
            rewriteChildren(invocation, implementations);
            return invocation;
        }
        return UnsupportedMethodDescriptor.replacing(node, configuration, node.getClass().getSimpleName());
    }
}
