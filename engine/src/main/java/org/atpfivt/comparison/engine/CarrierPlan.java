package org.atpfivt.comparison.engine;

import java.util.List;

import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.atpfivt.comparison.engine.ComparisonClass.TestMethod;
import org.atpfivt.comparison.engine.MirrorNode.ClassNode;

/** One generated nested class per selected method, with all implementations needed for this run. */
record CarrierPlan(TestMethod method, List<Implementation> implementations) {

    static List<CarrierPlan> forClass(ClassNode classNode) {
        if (classNode.model().classTemplate()) {
            return classNode.model().testMethods().stream()
                    .filter(method -> classNode.selection().includes(Position.CLASS.withMember(method.id())))
                    .map(method -> new CarrierPlan(method, selectedImplementations(classNode, method)))
                    .toList();
        }
        // Static descriptors already reflect the outer Launcher's post-discovery filters.
        return classNode.staticMethods().stream().map(node -> new CarrierPlan(node.method(),
            node.method().parameterized() ? selectedImplementations(classNode, node.method())
                    : node.staticImplementations())).toList();
    }

    private static List<Implementation> selectedImplementations(ClassNode classNode, TestMethod method) {
        Position position = Position.CLASS.withMember(method.id());
        return classNode.model().implementations().stream()
                .filter(implementation -> classNode.selection().includes(position.withImplementation(implementation.name())))
                .toList();
    }
}
