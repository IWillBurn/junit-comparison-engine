package org.atpfivt.comparison.engine;

import java.util.List;

/**
 * What the discovery request selected in one comparison class: the union of the selected {@link Position}s. A class
 * selector selects {@link Position#CLASS}; a UniqueId of a leaf selects that one leaf.
 */
record Selection(List<Position> positions) {

    Selection {
        positions = List.copyOf(positions);
    }

    /** Whether the node at {@code node} is selected, lies within a selected subtree or above a selected node. */
    boolean includes(Position node) {
        return positions.stream().anyMatch(selected -> selected.overlaps(node));
    }

    /** Whether the whole subtree of the node at {@code node} is selected. */
    boolean includesWhole(Position node) {
        return positions.stream().anyMatch(selected -> selected.covers(node));
    }
}
