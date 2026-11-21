package org.atpfivt.comparison.engine;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectNestedClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.atpfivt.comparison.engine.Position.MemberId;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;

/** The loaded carrier and the mapping from its generated methods to the user's implementations. */
record Carrier(Class<?> type, List<NestedClass> nestedClasses) {

    record NestedClass(Class<?> type, CarrierPlan plan, Map<String, Implementation> leaves) {

        Optional<Implementation> implementationOfLeaf(String methodName) {
            return Optional.ofNullable(leaves.get(methodName));
        }

        private String leafSignature(String implementation) {
            return Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> implementationOfLeaf(method.getName())
                            .filter(leaf -> leaf.name().equals(implementation)).isPresent())
                    .map(method -> method.getName() + "(" + MethodSource.from(method).getMethodParameterTypes() + ")")
                    .findFirst().orElseThrow();
        }
    }

    Carrier {
        nestedClasses = List.copyOf(nestedClasses);
    }

    List<DiscoverySelector> selectors(boolean classTemplate, Selection selection) {
        var members = nestedClasses.stream().map(nested -> nested.plan().method().id()).collect(Collectors.toSet());
        List<Position> requested = selection.positions().stream()
                .filter(position -> position.member() == null || members.contains(position.member()))
                .distinct().toList();
        List<Position> positions = requested.stream()
                .filter(position -> requested.stream().noneMatch(other -> !other.equals(position) && other.covers(position)))
                .toList();

        // Jupiter gives indexed class invocations precedence over wildcard children. Run broadly in this case
        // and let the mirror filter the events so that both parts of the request are represented.
        if (classTemplate && positions.stream().anyMatch(p -> p.classInvocation() == null)
                && positions.stream().anyMatch(p -> p.classInvocation() != null)) {
            return nestedClasses.stream().map(this::wholeNestedClass).toList();
        }
        return nestedClasses.stream()
                .flatMap(nested -> selectors(nested, positions, classTemplate))
                .distinct().toList();
    }

    private Stream<DiscoverySelector> selectors(NestedClass nested, List<Position> positions, boolean classTemplate) {
        if (!classTemplate && !nested.plan().method().parameterized()) {
            // Only the implementations retained in the static test tree were generated.
            return Stream.of(wholeNestedClass(nested));
        }
        MemberId member = nested.plan().method().id();
        return positions.stream().filter(position -> position.member() == null || member.equals(position.member()))
                .map(position -> selector(nested, position, classTemplate));
    }

    private DiscoverySelector wholeNestedClass(NestedClass nested) {
        return selectNestedClass(List.of(type), nested.type());
    }

    /** Jupiter's native ID grammar, kept local so public comparison IDs stay independent of it. */
    private DiscoverySelector selector(NestedClass nested, Position position, boolean classTemplate) {
        if (position.classInvocation() == null && position.argumentSet() == null && position.implementation() == null) {
            return wholeNestedClass(nested);
        }
        UniqueId carrier = UniqueId.forEngine(NestedJupiter.ENGINE_ID)
                .append(classTemplate ? "class-template" : "class", type.getName());
        UniqueId method = invocation(carrier, position.classInvocation())
                .append(nested.plan().method().parameterized() ? "nested-class-template" : "nested-class",
                    nested.type().getSimpleName());
        UniqueId target = invocation(method, position.argumentSet());
        String implementation = position.implementation();
        return selectUniqueId(implementation == null ? target
                : target.append("method", nested.leafSignature(implementation)));
    }

    private static UniqueId invocation(UniqueId parent, @Nullable Integer index) {
        return index == null ? parent : parent.append("class-template-invocation", "#" + index);
    }
}
