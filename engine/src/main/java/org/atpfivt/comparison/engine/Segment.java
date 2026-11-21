package org.atpfivt.comparison.engine;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.atpfivt.comparison.engine.Position.MemberId;

import org.junit.platform.engine.UniqueId;

/**
 * The UniqueId segments of the published tree, in their order: {@code [engine:comparison-tests]/[class:C]/
 * [class-invocation:#k]/[method:m(..)]/[argument-set:#k]/[implementation:X]}.
 * An unsupported {@code @Nested} class has {@code nested-class} instead of {@code method}. This enum both writes the
 * segments (for the nodes) and reads them back (for UniqueId selectors), so the two cannot drift apart.
 */
enum Segment {

    CLASS("class", 0),
    CLASS_INVOCATION("class-invocation", 1),
    METHOD("method", 2),
    NESTED_CLASS("nested-class", 2),
    ARGUMENT_SET("argument-set", 3),
    IMPLEMENTATION("implementation", 4);

    private final String type;
    /** Depth below the engine: the segments of a UniqueId come with strictly increasing depth. */
    private final int depth;

    Segment(String type, int depth) {
        this.type = type;
        this.depth = depth;
    }

    UniqueId append(UniqueId parent, String value) {
        return parent.append(type, value);
    }

    UniqueId append(UniqueId parent, int index) {
        return append(parent, indexedValue(index));
    }

    private static String indexedValue(int index) {
        return "#" + index;
    }

    boolean matches(UniqueId.Segment segment) {
        return segment.getType().equals(type);
    }

    /** {@code #2} → 2; empty for anything else (indices start at 1). */
    static Optional<Integer> index(String value) {
        if (!value.startsWith("#")) {
            return Optional.empty();
        }
        try {
            int index = Integer.parseInt(value.substring(1));
            return index > 0 && value.equals(indexedValue(index)) ? Optional.of(index) : Optional.empty();
        }
        catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * The position that the segments below the class segment describe; empty if a segment is not ours, repeated, out
     * of order or malformed.
     */
    static Optional<Position> position(List<UniqueId.Segment> belowClass) {
        Position position = Position.CLASS;
        Segment previous = CLASS;
        for (UniqueId.Segment segment : belowClass) {
            Optional<Segment> type = of(segment.getType());
            if (type.isEmpty() || type.get().depth <= previous.depth) {
                return Optional.empty();
            }
            Optional<Position> refined = type.get().refine(position, segment.getValue());
            if (refined.isEmpty()) {
                return Optional.empty();
            }
            position = refined.get();
            previous = type.get();
        }
        return Optional.of(position);
    }

    private static Optional<Segment> of(String type) {
        return Arrays.stream(values()).filter(segment -> segment.type.equals(type)).findFirst();
    }

    private Optional<Position> refine(Position position, String value) {
        return switch (this) {
            case CLASS -> Optional.empty(); // only right below the engine, never below the class
            case CLASS_INVOCATION -> index(value).map(position::withClassInvocation);
            case METHOD, NESTED_CLASS -> Optional.of(position.withMember(new MemberId(this, value)));
            case ARGUMENT_SET -> index(value).map(position::withArgumentSet);
            case IMPLEMENTATION -> Optional.of(position.withImplementation(value));
        };
    }
}
