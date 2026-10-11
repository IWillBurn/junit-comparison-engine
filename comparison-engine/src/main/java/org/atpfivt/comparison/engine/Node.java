package org.atpfivt.comparison.engine;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.atpfivt.comparison.engine.ComparisonClass.Member;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;

/**
 * A node of the published (mirror) tree: {@code class > [class invocation] > method > [argument set] >
 * implementation}. Every node below the class has the user's method as its source, so the IDE navigates to the
 * user's code, never to the generated carrier.
 */
final class Node extends AbstractTestDescriptor {

    enum Kind { CLASS, CLASS_INVOCATION, METHOD, ARGUMENT_SET, IMPLEMENTATION, UNSUPPORTED }

    /** A selected part of a class; a 0 / null component means "any". */
    record Pattern(int classInvocation, String member, int argumentSet, String implementation) {

        static final Pattern ALL = new Pattern(0, null, 0, null);

        /** Whether a node with these coordinates (0 / null = not applicable) may contain selected leaves. */
        boolean accepts(int classInvocation, String member, int argumentSet, String implementation) {
            return matches(this.classInvocation, classInvocation) && matches(this.member, member)
                    && matches(this.argumentSet, argumentSet) && matches(this.implementation, implementation);
        }

        private static boolean matches(int pattern, int value) {
            return pattern == 0 || value == 0 || pattern == value;
        }

        private static boolean matches(String pattern, String value) {
            return pattern == null || value == null || pattern.equals(value);
        }
    }

    final Kind kind;
    final ComparisonClass model;
    final Member member;
    final int classInvocation;
    final int argumentSet;
    final Implementation implementation;
    /** CLASS only: what the discovery request selected in this class. */
    final List<Pattern> patterns;
    private final String legacyName;

    private Node(UniqueId id, String displayName, Kind kind, ComparisonClass model, Member member,
            int classInvocation, int argumentSet, Implementation implementation, List<Pattern> patterns,
            String legacyName) {
        super(id, displayName, kind == Kind.CLASS || kind == Kind.CLASS_INVOCATION
                ? ClassSource.from(model.testClass) : member.source(model.testClass));
        this.kind = kind;
        this.model = model;
        this.member = member;
        this.classInvocation = classInvocation;
        this.argumentSet = argumentSet;
        this.implementation = implementation;
        this.patterns = patterns;
        this.legacyName = legacyName;
    }

    static Node forClass(UniqueId engineId, ComparisonClass model, List<Pattern> patterns) {
        return new Node(engineId.append("class", model.testClass.getName()), model.displayName, Kind.CLASS, model,
            null, 0, 0, null, List.copyOf(patterns), model.testClass.getName());
    }

    /** CLASS → invocation of the user's own {@code @ParameterizedClass}. */
    Node classInvocation(int index, String displayName) {
        return add(new Node(getUniqueId().append("class-invocation", "#" + index), displayName,
            Kind.CLASS_INVOCATION, model, null, index, 0, null, null, legacyName + "[" + index + "]"));
    }

    /** CLASS or CLASS_INVOCATION → test method or unsupported member. */
    Node member(Member m) {
        boolean supported = m.kind() != ComparisonClass.Kind.UNSUPPORTED;
        String segment = m.method() != null ? "method" : "nested-class";
        return add(new Node(getUniqueId().append(segment, m.id()), m.displayName(),
            supported ? Kind.METHOD : Kind.UNSUPPORTED, model, m, classInvocation, 0, null, null,
            m.legacyName() + (classInvocation > 0 ? "[" + classInvocation + "]" : "")));
    }

    /** METHOD → argument set of a parameterized method. */
    Node argumentSet(int index, String displayName) {
        return add(new Node(getUniqueId().append("argument-set", "#" + index), displayName, Kind.ARGUMENT_SET,
            model, member, classInvocation, index, null, null, legacyName + "[" + index + "]"));
    }

    /** METHOD (of a @Test) or ARGUMENT_SET → implementation leaf. */
    Node implementation(Implementation impl) {
        return add(new Node(getUniqueId().append("implementation", impl.name()), impl.name(), Kind.IMPLEMENTATION,
            model, member, classInvocation, argumentSet, impl, null, legacyName + "[" + impl.name() + "]"));
    }

    private Node add(Node child) {
        addChild(child);
        return child;
    }

    Optional<Node> child(String segmentValue) {
        return getChildren().stream().map(Node.class::cast)
                .filter(c -> c.getUniqueId().getLastSegment().getValue().equals(segmentValue)).findFirst();
    }

    @Override
    public Type getType() {
        return kind == Kind.IMPLEMENTATION || kind == Kind.UNSUPPORTED ? Type.TEST : Type.CONTAINER;
    }

    @Override
    public boolean mayRegisterTests() {
        return switch (kind) {
            case CLASS -> model.classTemplate || model.problem != null;
            case METHOD -> member.parameterized() || classInvocation > 0;
            case CLASS_INVOCATION, ARGUMENT_SET -> true;
            case IMPLEMENTATION, UNSUPPORTED -> false;
        };
    }

    @Override
    public String getLegacyReportingName() {
        return legacyName;
    }

    /** Tags of the user's class and method, inherited by every node below them (like Jupiter does). */
    @Override
    public Set<TestTag> getTags() {
        Set<TestTag> tags = new LinkedHashSet<>();
        getParent().filter(Node.class::isInstance).ifPresent(p -> tags.addAll(p.getTags()));
        if (kind == Kind.CLASS) {
            tags.addAll(model.tags);
        }
        else if ((kind == Kind.METHOD || kind == Kind.UNSUPPORTED) && member != null) {
            tags.addAll(member.tags());
        }
        return tags;
    }
}
