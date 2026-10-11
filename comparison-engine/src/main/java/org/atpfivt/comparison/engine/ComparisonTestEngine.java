package org.atpfivt.comparison.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.atpfivt.comparison.engine.ComparisonClass.Kind;
import org.atpfivt.comparison.engine.ComparisonClass.Member;
import org.atpfivt.comparison.engine.Node.Pattern;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.ConfigurationParameters;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.Filter;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.ClasspathRootSelector;
import org.junit.platform.engine.discovery.IterationSelector;
import org.junit.platform.engine.discovery.MethodSelector;
import org.junit.platform.engine.discovery.ModuleSelector;
import org.junit.platform.engine.discovery.PackageSelector;
import org.junit.platform.engine.discovery.UniqueIdSelector;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

/**
 * The {@code comparison-tests} engine. Discovery builds the published tree from the {@code @ComparisonTest} classes
 * selected by the request (class, method, iteration, UniqueId, package, classpath root, module). Execution generates a carrier
 * class per user class and lets the real Jupiter run it in a nested Launcher (see {@link NestedRun}).
 */
public final class ComparisonTestEngine implements TestEngine {

    static final String ENGINE_ID = "comparison-tests";

    @Override
    public String getId() {
        return ENGINE_ID;
    }

    @Override
    public Optional<String> getGroupId() {
        return Optional.of("org.atpfivt.comparison");
    }

    @Override
    public Optional<String> getArtifactId() {
        return Optional.of("comparison-engine");
    }

    @Override
    public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
        EngineDescriptor engine = new EngineDescriptor(uniqueId, "Comparison tests");
        Map<Class<?>, List<Pattern>> selected = new LinkedHashMap<>();
        Predicate<String> classNames = Filter.composeFilters(request.getFiltersByType(ClassNameFilter.class))
                .toPredicate();
        Predicate<Class<?>> candidate = ComparisonClass::isComparisonClass;

        for (ClassSelector s : request.getSelectorsByType(ClassSelector.class)) {
            load(s::getJavaClass).filter(candidate).ifPresent(c -> add(selected, c, Pattern.ALL));
        }
        for (MethodSelector s : request.getSelectorsByType(MethodSelector.class)) {
            load(s::getJavaClass).filter(candidate).ifPresent(c -> load(s::getJavaMethod).ifPresent(
                m -> add(selected, c, new Pattern(0, ComparisonClass.memberId(m), 0, null))));
        }
        for (IterationSelector s : request.getSelectorsByType(IterationSelector.class)) {
            // IntelliJ runs a single @ValueSource value this way; on a @ParameterizedClass user class: one invocation
            if (s.getParentSelector() instanceof MethodSelector ms) {
                load(ms::getJavaClass).filter(candidate).ifPresent(c -> load(ms::getJavaMethod).ifPresent(
                    m -> s.getIterationIndices().forEach(
                        i -> add(selected, c, new Pattern(0, ComparisonClass.memberId(m), i + 1, null)))));
            }
            else if (s.getParentSelector() instanceof ClassSelector cs) {
                load(cs::getJavaClass).filter(candidate).ifPresent(c -> s.getIterationIndices()
                        .forEach(i -> add(selected, c, new Pattern(i + 1, null, 0, null))));
            }
        }
        for (UniqueIdSelector s : request.getSelectorsByType(UniqueIdSelector.class)) {
            parse(s.getUniqueId(), uniqueId).ifPresent(p -> add(selected, p.type(), p.pattern()));
        }
        for (PackageSelector s : request.getSelectorsByType(PackageSelector.class)) {
            ReflectionSupport.findAllClassesInPackage(s.getPackageName(), candidate, classNames)
                    .forEach(c -> add(selected, c, Pattern.ALL));
        }
        for (ClasspathRootSelector s : request.getSelectorsByType(ClasspathRootSelector.class)) {
            ReflectionSupport.findAllClassesInClasspathRoot(s.getClasspathRoot(), candidate, classNames)
                    .forEach(c -> add(selected, c, Pattern.ALL));
        }
        for (ModuleSelector s : request.getSelectorsByType(ModuleSelector.class)) {
            ReflectionSupport.findAllClassesInModule(s.getModuleName(), candidate, classNames)
                    .forEach(c -> add(selected, c, Pattern.ALL));
        }

        ConfigurationParameters configuration = request.getConfigurationParameters();
        selected.forEach((type, patterns) -> {
            Node node = Node.forClass(uniqueId, new ComparisonClass(type, configuration), patterns);
            buildStaticTree(node);
            if (!node.getChildren().isEmpty() || node.mayRegisterTests()) {
                engine.addChild(node);
            }
        });
        return engine;
    }

    /** Static part of the tree: methods, leaves of @Test methods, unsupported members (one skipped leaf each). */
    private static void buildStaticTree(Node classNode) {
        ComparisonClass model = classNode.model;
        if (model.problem != null) {
            return;
        }
        for (Member member : model.members) {
            if (member.kind() == Kind.UNSUPPORTED) {
                // a skipped leaf: selected by the class, by the member itself, or by its UniqueId
                if (classNode.patterns.stream().anyMatch(p -> p.classInvocation() == 0 && p.argumentSet() == 0
                        && p.implementation() == null && p.accepts(0, member.id(), 0, null))) {
                    classNode.member(member);
                }
                continue;
            }
            if (classNode.patterns.stream().noneMatch(p -> p.accepts(0, member.id(), 0, null))) {
                continue;
            }
            if (!model.classTemplate) {
                Node method = classNode.member(member);
                if (member.kind() == Kind.TEST) {
                    model.implementations.stream().filter(impl -> classNode.patterns.stream()
                            .anyMatch(p -> p.argumentSet() == 0 && p.accepts(0, member.id(), 0, impl.name())))
                            .forEach(method::implementation);
                    if (method.getChildren().isEmpty()) {
                        method.removeFromHierarchy();
                    }
                }
            }
        }
    }

    private static void add(Map<Class<?>, List<Pattern>> selected, Class<?> type, Pattern pattern) {
        selected.computeIfAbsent(type, k -> new ArrayList<>()).add(pattern);
    }

    private static <T> Optional<T> load(java.util.function.Supplier<T> supplier) {
        try {
            return Optional.ofNullable(supplier.get());
        }
        catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    private record Parsed(Class<?> type, Pattern pattern) {
    }

    /**
     * {@code [engine]/[class:C]/[class-invocation:#k]/[method:m(..)]/[argument-set:#k]/[implementation:X]};
     * every segment after the class is optional ({@code nested-class} instead of {@code method} for unsupported
     * {@code @Nested} classes).
     */
    private static Optional<Parsed> parse(UniqueId id, UniqueId engineId) {
        if (!id.hasPrefix(engineId) || id.getSegments().size() < 2) {
            return Optional.empty();
        }
        List<UniqueId.Segment> segments = id.getSegments().subList(1, id.getSegments().size());
        if (!segments.get(0).getType().equals("class")) {
            return Optional.empty();
        }
        Optional<Class<?>> type = ReflectionSupport.tryToLoadClass(segments.get(0).getValue()).toOptional()
                .filter(ComparisonClass::isComparisonClass);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        int classInvocation = 0;
        String member = null;
        int argumentSet = 0;
        String implementation = null;
        for (UniqueId.Segment segment : segments.subList(1, segments.size())) {
            switch (segment.getType()) {
                case "class-invocation" -> classInvocation = index(segment.getValue());
                case "method", "nested-class" -> member = segment.getValue();
                case "argument-set" -> argumentSet = index(segment.getValue());
                case "implementation" -> implementation = segment.getValue();
                default -> {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new Parsed(type.get(), new Pattern(classInvocation, member, argumentSet, implementation)));
    }

    private static int index(String value) {
        try {
            return Integer.parseInt(value.startsWith("#") ? value.substring(1) : value);
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void execute(ExecutionRequest request) {
        new NestedRun(request).run();
    }
}
