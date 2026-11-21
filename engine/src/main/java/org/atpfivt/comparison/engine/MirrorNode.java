package org.atpfivt.comparison.engine;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.atpfivt.comparison.engine.ComparisonClass.Member;
import org.atpfivt.comparison.engine.ComparisonClass.TestMethod;
import org.atpfivt.comparison.engine.ComparisonClass.UnsupportedMember;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;

/** Published tree: class → [class invocation] → method → [argument set] → implementation. */
abstract sealed class MirrorNode extends AbstractTestDescriptor
        permits MirrorNode.ClassNode, MirrorNode.ClassInvocationNode, MirrorNode.MethodNode, MirrorNode.ArgumentSetNode,
                MirrorNode.ImplementationNode, MirrorNode.UnsupportedNode {

    private final Position position;
    private final String legacyName;
    private final Set<TestTag> tags;

    private MirrorNode(UniqueId id, String displayName, TestSource source, Position position, String legacyName,
            Set<TestTag> tags) {
        super(id, displayName, source);
        this.position = position;
        this.legacyName = legacyName;
        this.tags = tags;
    }

    private MirrorNode(MirrorNode parent, Member member, String legacyName) {
        this(member.id().appendTo(parent.getUniqueId()), member.displayName(), member.source(),
            parent.position().withMember(member.id()), legacyName, union(parent.getTags(), member.tags()));
    }

    final Position position() {
        return position;
    }

    /** The class node this node belongs to. */
    final ClassNode classNode() {
        return this instanceof ClassNode node ? node : ((MirrorNode) getParent().orElseThrow()).classNode();
    }

    @Override
    public final String getLegacyReportingName() {
        return legacyName;
    }

    /** The tags of the user's class and method; every node below them inherits them, as in Jupiter. */
    @Override
    public final Set<TestTag> getTags() {
        return tags;
    }

    @Override
    public Type getType() {
        return Type.CONTAINER;
    }

    /** Reuse a discovered child. Only dynamic parents can add a child that discovery did not retain. */
    final Optional<MirrorNode> child(MirrorNode candidate) {
        return getChildren().stream().filter(candidate::equals).map(MirrorNode.class::cast).findFirst()
                .or(() -> mayRegisterTests() && addIfSelected(candidate) ? Optional.of(candidate) : Optional.empty());
    }

    final boolean addIfSelected(MirrorNode child) {
        if (!classNode().selection().includes(child.position())) {
            return false;
        }
        addChild(child);
        return true;
    }

    final <T extends MirrorNode> List<T> children(Class<T> type) {
        return getChildren().stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static Set<TestTag> union(Set<TestTag> inherited, Set<TestTag> own) {
        Set<TestTag> tags = new LinkedHashSet<>(inherited);
        tags.addAll(own);
        return Collections.unmodifiableSet(tags);
    }

    /** A {@code @ComparisonTest} class, with what the request selected in it. */
    static final class ClassNode extends MirrorNode {

        private final ComparisonClass model;
        private final Selection selection;

        private ClassNode(UniqueId engineId, ComparisonClass model, Selection selection) {
            super(Segment.CLASS.append(engineId, model.testClass().getName()), model.displayName(),
                ClassSource.from(model.testClass()), Position.CLASS, model.testClass().getName(), model.tags());
            this.model = model;
            this.selection = selection;
        }

        /** The node of a selected class with its static children, or empty if the selection has no node in it. */
        static Optional<ClassNode> discover(UniqueId engineId, ComparisonClass model, Selection selection) {
            ClassNode node = new ClassNode(engineId, model, selection);
            if (model.isValid()) {
                model.members().forEach(node::addStaticChild);
            }
            return node.getChildren().isEmpty() && !node.mayRegisterTests() ? Optional.empty() : Optional.of(node);
        }

        private void addStaticChild(Member member) {
            switch (member) {
                case UnsupportedMember unsupported -> {
                    UnsupportedNode node = new UnsupportedNode(this, unsupported);
                    // shown when the class or the member itself is selected, not for a selected leaf elsewhere
                    if (selection.includesWhole(node.position())) {
                        addChild(node);
                    }
                }
                case TestMethod method -> {
                    // A class template gets its method nodes at execution time, under each class invocation.
                    if (model.classTemplate()) {
                        return;
                    }
                    MethodNode node = new MethodNode(this, method);
                    if (addIfSelected(node) && !method.parameterized()) {
                        model.implementations().forEach(implementation ->
                                node.addIfSelected(new ImplementationNode(node, implementation)));
                    }
                }
            }
        }

        ComparisonClass model() {
            return model;
        }

        Selection selection() {
            return selection;
        }

        List<MethodNode> staticMethods() {
            return children(MethodNode.class);
        }

        List<UnsupportedNode> unsupportedMembers() {
            return children(UnsupportedNode.class);
        }

        @Override
        public boolean mayRegisterTests() {
            // the class invocations of a class template; a failure of an invalid class
            return model.classTemplate() || !model.isValid();
        }
    }

    /** An invocation of the user's own {@code @ParameterizedClass} (or another class template). */
    static final class ClassInvocationNode extends MirrorNode {

        ClassInvocationNode(ClassNode parent, int index, String displayName) {
            super(Segment.CLASS_INVOCATION.append(parent.getUniqueId(), index), displayName,
                ClassSource.from(parent.model.testClass()), parent.position().withClassInvocation(index),
                parent.getLegacyReportingName() + "[" + index + "]", parent.getTags());
        }

        @Override
        public boolean mayRegisterTests() {
            return true;
        }
    }

    /** A test method of the user class. */
    static final class MethodNode extends MirrorNode {

        private final TestMethod method;

        /** {@code parent}: the class node, or a class invocation node. */
        MethodNode(MirrorNode parent, TestMethod method) {
            super(parent, method, method.legacyName() + invocationSuffix(parent.position()));
            this.method = method;
        }

        private static String invocationSuffix(Position parent) {
            return parent.classInvocation() == null ? "" : "[" + parent.classInvocation() + "]";
        }

        TestMethod method() {
            return method;
        }

        /** The implementations of the static leaves (a {@code @Test} below the class node). */
        List<Implementation> staticImplementations() {
            return children(ImplementationNode.class).stream().map(ImplementationNode::implementation).toList();
        }

        @Override
        public boolean mayRegisterTests() {
            // argument sets; the leaves of a method below a class invocation
            return method.parameterized() || position().classInvocation() != null;
        }
    }

    /** An argument set of a parameterized method: one invocation of the generated {@code @ParameterizedClass}. */
    static final class ArgumentSetNode extends MirrorNode {

        ArgumentSetNode(MethodNode parent, int index, String displayName) {
            super(Segment.ARGUMENT_SET.append(parent.getUniqueId(), index), displayName, parent.method.source(),
                parent.position().withArgumentSet(index), parent.getLegacyReportingName() + "[" + index + "]",
                parent.getTags());
        }

        @Override
        public boolean mayRegisterTests() {
            return true;
        }
    }

    /** A leaf: the method (or one argument set of it) run on one implementation. */
    static final class ImplementationNode extends MirrorNode {

        private final Implementation implementation;

        /** {@code parent}: a method node, or an argument set node. */
        ImplementationNode(MirrorNode parent, Implementation implementation) {
            super(Segment.IMPLEMENTATION.append(parent.getUniqueId(), implementation.name()), implementation.name(),
                parent.getSource().orElseThrow(), parent.position().withImplementation(implementation.name()),
                parent.getLegacyReportingName() + "[" + implementation.name() + "]", parent.getTags());
            this.implementation = implementation;
        }

        Implementation implementation() {
            return implementation;
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }
    }

    /** A member the engine cannot run: a leaf reported as skipped with the reason. */
    static final class UnsupportedNode extends MirrorNode {

        private final String reason;

        private UnsupportedNode(ClassNode parent, UnsupportedMember member) {
            super(parent, member, member.legacyName());
            this.reason = member.reason();
        }

        String reason() {
            return reason;
        }

        @Override
        public Type getType() {
            return Type.TEST;
        }
    }
}
