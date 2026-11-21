package org.atpfivt.comparison.engine;

import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.UniqueId;

/**
 * A position in the published tree of one comparison class:
 * {@code [class invocation] > member > [argument set] > implementation}.
 *
 * <p>In a selection, a null component matches any value at that level. On a node, it means that the level
 * does not occur on its path from the root.
 */
record Position(@Nullable Integer classInvocation, @Nullable MemberId member, @Nullable Integer argumentSet,
        @Nullable String implementation) {

    record MemberId(Segment segment, String value) {

        UniqueId appendTo(UniqueId parent) {
            return segment.append(parent, value);
        }
    }

    /** The class itself; as a selected position, the whole class. */
    static final Position CLASS = new Position(null, null, null, null);

    Position withClassInvocation(int index) {
        return new Position(index, member, argumentSet, implementation);
    }

    Position withMember(MemberId id) {
        return new Position(classInvocation, id, argumentSet, implementation);
    }

    Position withArgumentSet(int index) {
        return new Position(classInvocation, member, index, implementation);
    }

    Position withImplementation(String name) {
        return new Position(classInvocation, member, argumentSet, name);
    }

    /** Whether both positions can lie on one path of the tree: every component specified in both is the same. */
    boolean overlaps(Position other) {
        return compatible(classInvocation, other.classInvocation) && compatible(member, other.member)
                && compatible(argumentSet, other.argumentSet) && compatible(implementation, other.implementation);
    }

    /** Whether this selected position covers the whole subtree of the node at {@code node}. */
    boolean covers(Position node) {
        return contains(classInvocation, node.classInvocation) && contains(member, node.member)
                && contains(argumentSet, node.argumentSet) && contains(implementation, node.implementation);
    }

    private static boolean compatible(@Nullable Object a, @Nullable Object b) {
        return a == null || b == null || a.equals(b);
    }

    private static boolean contains(@Nullable Object selected, @Nullable Object node) {
        return selected == null || selected.equals(node);
    }
}
