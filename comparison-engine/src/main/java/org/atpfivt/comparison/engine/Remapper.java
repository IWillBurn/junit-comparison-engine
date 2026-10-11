package org.atpfivt.comparison.engine;

import static org.atpfivt.comparison.engine.ComparisonTestEngine.JUPITER;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestDescriptor.Type;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

import org.atpfivt.comparison.engine.Node.Root;

/**
 * Moves the events of the nested Jupiter run onto the mirror. Jupiter's ids are
 * {@code [engine]/[class-template:C]/[class-template-invocation:#k]/...}: the invocation level is dropped and #k
 * becomes the leaf of the k-th implementation. Jupiter runs the implementations one after the other (all of #1,
 * then all of #2), so method and argument-set containers stay open until their class finishes.
 */
final class Remapper implements TestExecutionListener {

    private final Root root;
    private final EngineExecutionListener out;

    Remapper(Root root, EngineExecutionListener out) {
        this.root = root;
        this.out = out;
    }

    @Override
    public synchronized void executionStarted(TestIdentifier id) {
        Node node = node(id);
        if (node != null) {
            start(node);
        }
    }

    @Override
    public synchronized void executionSkipped(TestIdentifier id, String reason) {
        Node node = node(id);
        if (node == null || node.started || node.done) {
            return;
        }
        if (node.getType().isTest() || isClass(id)) {
            node.getParent().filter(Node.class::isInstance).ifPresent(p -> start((Node) p));
            out.executionSkipped(node, reason);
            node.done = true;
        }
        else {
            node.skipReason = reason; // one implementation's copy of a method; reported at the class end if none ran
        }
    }

    @Override
    public synchronized void executionFinished(TestIdentifier id, TestExecutionResult result) {
        Node node = node(id);
        if (node == null || node.done) {
            return;
        }
        if (node.getType().isTest()) {
            start(node);
            out.executionFinished(node, result);
            node.done = true;
        }
        else if (isClass(id)) {
            finish(node, result); // every implementation of the class is done
        }
        else if (result.getStatus() == TestExecutionResult.Status.FAILED) {
            node.failure = result.getThrowable().orElse(null); // e.g. a @ParameterizedTest without arguments
        }
    }

    /** Safety net: classes whose inner run never finished. */
    void finishAll() {
        for (TestDescriptor child : List.copyOf(root.getChildren())) {
            if (child instanceof Node node && node.started && !node.done) {
                finish(node, TestExecutionResult.successful());
            }
        }
    }

    private void finish(Node node, TestExecutionResult result) {
        for (TestDescriptor child : List.copyOf(node.getChildren())) {
            Node c = (Node) child;
            if (c.done) {
                continue;
            }
            if (c.started) {
                finish(c, c.failure == null ? TestExecutionResult.successful() : TestExecutionResult.failed(c.failure));
            }
            else if (c.skipReason != null) {
                out.executionSkipped(c, c.skipReason);
                c.done = true;
            }
        }
        start(node);
        out.executionFinished(node, result);
        node.done = true;
    }

    private static boolean isClass(TestIdentifier id) {
        return id.getUniqueIdObject().getSegments().size() == 2;
    }

    /** The mirror node of a Jupiter node (argument sets and their leaves are created here), or null to ignore it. */
    private @Nullable Node node(TestIdentifier id) {
        List<UniqueId.Segment> segments = id.getUniqueIdObject().getSegments();
        if (segments.size() < 2) {
            return null; // the Jupiter engine
        }
        Node classNode = root.nodes.get(root.outer(UniqueId.forEngine(JUPITER).append(segments.get(1))));
        if (classNode == null || !root.attached(classNode) || segments.size() == 3) {
            return null; // size 3: the hidden invocation of one implementation
        }
        if (segments.size() == 2) {
            return classNode;
        }
        int index = Integer.parseInt(segments.get(2).getValue().substring(1)) - 1;
        if (index >= classNode.implementations.size()) {
            return null;
        }
        UniqueId jupiterId = UniqueId.forEngine(JUPITER).append(segments.get(1));
        for (UniqueId.Segment s : segments.subList(3, segments.size())) {
            jupiterId = jupiterId.append(s);
        }
        if (!id.isTest()) {
            Node method = root.nodes.get(root.outer(jupiterId)); // one implementation's copy of a @ParameterizedTest
            return method != null && root.attached(method) ? method : null;
        }
        return leaf(id, jupiterId, classNode.implementations.get(index));
    }

    private @Nullable Node leaf(TestIdentifier id, UniqueId jupiterId, String implementation) {
        UniqueId outer = root.outer(jupiterId);
        Node container = root.nodes.get(outer); // a @Test method
        if (container == null) { // an argument set, created by the first implementation that runs it
            Node method = root.nodes.get(outer.removeLastSegment());
            if (method == null || !method.mayRegisterTests() || !root.attached(method)
                    || !root.allowed(jupiterId, null)) {
                return null;
            }
            container = new Node(outer, id.getDisplayName(), id.getSource().orElse(null), Type.CONTAINER, true,
                method.getLegacyReportingName() + "[" + jupiterId.getLastSegment().getValue().substring(1) + "]");
            register(method, container);
        }
        Node leaf = root.nodes.get(outer.append(Node.IMPLEMENTATION, implementation));
        if (leaf == null && container.mayRegisterTests() && root.allowed(jupiterId, implementation)) {
            leaf = container.leaf(implementation);
            register(container, leaf);
        }
        return leaf != null && root.attached(leaf) ? leaf : null;
    }

    private void register(Node parent, Node child) {
        start(parent);
        parent.addChild(child);
        root.nodes.put(child.getUniqueId(), child);
        out.dynamicTestRegistered(child);
    }

    private void start(Node node) {
        if (node.started) {
            return;
        }
        node.getParent().filter(Node.class::isInstance).ifPresent(p -> start((Node) p));
        out.executionStarted(node);
        node.started = true;
    }
}
