package org.atpfivt.comparison.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

/** A node of the mirror tree: class, method, argument set (CONTAINERs) or implementation (the only TESTs). */
final class Node extends AbstractTestDescriptor {

    static final String IMPLEMENTATION = "implementation";

    private final Type type;
    private final boolean dynamic;
    private final String legacyName;
    /** Class nodes: the implementation names in invocation order (invocation #k = k-th implementation). */
    List<String> implementations = List.of();
    boolean started;
    boolean done;
    @Nullable
    Throwable failure;
    @Nullable
    String skipReason;

    Node(UniqueId id, String name, @Nullable TestSource source, Type type, boolean dynamic, String legacyName) {
        super(id, name, source);
        this.type = type;
        this.dynamic = dynamic;
        this.legacyName = legacyName;
    }

    /** The leaf of one implementation, e.g. {@code mul(int, int, int, Calc)[2][Buggy]} for CI reports. */
    Node leaf(String implementation) {
        return new Node(getUniqueId().append(IMPLEMENTATION, implementation), implementation, getSource().orElse(null),
            Type.TEST, false, legacyName + "[" + implementation + "]");
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public boolean mayRegisterTests() {
        return dynamic;
    }

    @Override
    public String getLegacyReportingName() {
        return legacyName;
    }

    /** The engine root; also holds what discovery derived from the outer request. */
    static final class Root extends EngineDescriptor {

        /** UniqueIdSelector of this engine: the Jupiter node (without the hidden level) and the implementation. */
        record Restriction(UniqueId jupiterId, @Nullable String implementation) {
        }

        final List<DiscoverySelector> selectors = new ArrayList<>();
        final List<DiscoveryFilter<?>> filters = new ArrayList<>();
        final List<Restriction> restrictions = new ArrayList<>();
        /** A selector other than our own UniqueIds (class, method, package...): no implementation restriction. */
        boolean unrestricted;
        final Map<UniqueId, Node> nodes = new HashMap<>();

        Root(UniqueId id) {
            super(id, "Comparison tests");
        }

        /**
         * Should the (normalized) Jupiter node run for the implementation (null = any)? Only re-runs by UniqueId
         * restrict: then the node must be on the path of a selected id of that implementation.
         */
        boolean allowed(UniqueId jupiterId, @Nullable String implementation) {
            if (unrestricted) {
                return true;
            }
            for (Restriction r : restrictions) {
                if ((jupiterId.hasPrefix(r.jupiterId()) || r.jupiterId().hasPrefix(jupiterId)) && (implementation == null
                        || r.implementation() == null || r.implementation().equals(implementation))) {
                    return true;
                }
            }
            return false;
        }

        /** Outer id of a Jupiter id: the same segments below this engine. */
        UniqueId outer(UniqueId jupiterId) {
            UniqueId result = getUniqueId();
            for (UniqueId.Segment s : jupiterId.getSegments().subList(1, jupiterId.getSegments().size())) {
                result = result.append(s);
            }
            return result;
        }

        void add(TestDescriptor parent, Node child) {
            parent.addChild(child);
            nodes.put(child.getUniqueId(), child);
            child.getChildren().forEach(c -> nodes.put(c.getUniqueId(), (Node) c));
        }

        boolean attached(TestDescriptor node) {
            for (TestDescriptor cur = node; cur != null; cur = cur.getParent().orElse(null)) {
                if (cur == this) {
                    return true;
                }
            }
            return false;
        }
    }
}
