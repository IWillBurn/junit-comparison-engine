package org.atpfivt.comparison.engine;

import static org.junit.platform.commons.support.ReflectionSupport.findAllClassesInClasspathRoot;
import static org.junit.platform.commons.support.ReflectionSupport.findAllClassesInModule;
import static org.junit.platform.commons.support.ReflectionSupport.findAllClassesInPackage;
import static org.junit.platform.engine.SelectorResolutionResult.resolved;
import static org.junit.platform.engine.SelectorResolutionResult.unresolved;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.atpfivt.comparison.engine.MirrorNode.ClassNode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.DiscoveryFilter;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.Filter;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.ClassSelector;
import org.junit.platform.engine.discovery.ClasspathRootSelector;
import org.junit.platform.engine.discovery.IterationSelector;
import org.junit.platform.engine.discovery.MethodSelector;
import org.junit.platform.engine.discovery.ModuleSelector;
import org.junit.platform.engine.discovery.PackageNameFilter;
import org.junit.platform.engine.discovery.PackageSelector;
import org.junit.platform.engine.discovery.UniqueIdSelector;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;

/** Resolves selectors and builds the static descriptors for each selected comparison class. */
final class Discovery {

    /** One selected position in one comparison class. */
    private record Selected(Class<?> testClass, Position position) {

        static Selected wholeClass(Class<?> testClass) {
            return new Selected(testClass, Position.CLASS);
        }

        static Selected method(Class<?> testClass, Method method) {
            return new Selected(testClass, Position.CLASS.withMember(ComparisonClass.methodId(method)));
        }

        /** Iteration {@code number} of the selected node: an argument set of a method, an invocation of a class. */
        Selected iteration(int number) {
            return new Selected(testClass, position.member() != null
                    ? position.withArgumentSet(number) : position.withClassInvocation(number));
        }
    }

    private final EngineDiscoveryRequest request;
    private final UniqueId engineId;
    /** The ClassNameFilters and PackageNameFilters of the request, for classes found in containers. */
    private final Predicate<String> classNameFilter;
    private final Map<Class<?>, ComparisonClass> models = new HashMap<>();

    private Discovery(EngineDiscoveryRequest request, UniqueId engineId) {
        this.request = request;
        this.engineId = engineId;
        List<DiscoveryFilter<String>> filters = new ArrayList<>(request.getFiltersByType(ClassNameFilter.class));
        filters.addAll(request.getFiltersByType(PackageNameFilter.class));
        this.classNameFilter = Filter.composeFilters(filters).toPredicate();
    }

    static TestDescriptor discover(EngineDiscoveryRequest request, UniqueId engineId) {
        return new Discovery(request, engineId).discover();
    }

    private TestDescriptor discover() {
        Map<Class<?>, List<Position>> selected = new LinkedHashMap<>();
        for (DiscoverySelector selector : request.getSelectorsByType(DiscoverySelector.class)) {
            List<Selected> found = candidates(selector).stream()
                    .filter(candidate -> model(candidate.testClass()).contains(candidate.position()))
                    .toList();
            found.forEach(s -> selected.computeIfAbsent(s.testClass(), type -> new ArrayList<>()).add(s.position()));
            boolean resolved = !found.isEmpty() || isEngineItself(selector);
            request.getDiscoveryListener().selectorProcessed(engineId, selector, resolved ? resolved() : unresolved());
        }
        EngineDescriptor engine = new EngineDescriptor(engineId, "Comparison tests");
        selected.forEach((type, positions) -> ClassNode.discover(engineId, models.get(type), new Selection(positions))
                .ifPresent(engine::addChild));
        return engine;
    }

    /** What a selector points at in comparison classes, before checking that the class has such a node. */
    private List<Selected> candidates(DiscoverySelector selector) {
        return switch (selector) {
            case ClassSelector s -> comparisonClass(s::getJavaClass).map(Selected::wholeClass).stream().toList();
            case MethodSelector s -> comparisonClass(s::getJavaClass)
                    .flatMap(type -> load(s::getJavaMethod).map(method -> Selected.method(type, method)))
                    .stream().toList();
            // IntelliJ runs one argument set of a @ParameterizedTest as an iteration of its method
            case IterationSelector s -> candidates(s.getParentSelector()).stream()
                    .flatMap(parent -> s.getIterationIndices().stream().map(index -> parent.iteration(index + 1)))
                    .toList();
            case UniqueIdSelector s -> parse(s.getUniqueId()).stream().toList();
            case PackageSelector s -> wholeClasses(
                findAllClassesInPackage(s.getPackageName(), ComparisonClass::isCandidate, classNameFilter));
            case ClasspathRootSelector s -> wholeClasses(
                findAllClassesInClasspathRoot(s.getClasspathRoot(), ComparisonClass::isCandidate, classNameFilter));
            case ModuleSelector s -> wholeClasses(
                findAllClassesInModule(s.getModuleName(), ComparisonClass::isCandidate, classNameFilter));
            default -> List.of();
        };
    }

    /** {@code [engine:comparison-tests]/[class:C]/...}, see {@link Segment}; empty if not ours or malformed. */
    private Optional<Selected> parse(UniqueId id) {
        List<UniqueId.Segment> segments = id.getSegments();
        if (!id.hasPrefix(engineId) || segments.size() < 2 || !Segment.CLASS.matches(segments.get(1))) {
            return Optional.empty();
        }
        return ReflectionSupport.tryToLoadClass(segments.get(1).getValue()).toOptional()
                .filter(ComparisonClass::isCandidate)
                .flatMap(type -> Segment.position(segments.subList(2, segments.size()))
                        .map(position -> new Selected(type, position)));
    }

    /** The engine's own UniqueId selects nothing, as in Jupiter, but it is not an unknown id. */
    private boolean isEngineItself(DiscoverySelector selector) {
        return selector instanceof UniqueIdSelector s && s.getUniqueId().equals(engineId);
    }

    private ComparisonClass model(Class<?> testClass) {
        return models.computeIfAbsent(testClass,
            type -> ComparisonClass.analyze(type, request.getConfigurationParameters()));
    }

    private static List<Selected> wholeClasses(List<Class<?>> classes) {
        return classes.stream().map(Selected::wholeClass).toList();
    }

    private static Optional<Class<?>> comparisonClass(Supplier<Class<?>> loader) {
        return load(loader).filter(ComparisonClass::isCandidate);
    }

    /** A class or method of a selector; empty if it cannot be loaded: then it is not ours, Jupiter reports it. */
    private static <T> Optional<T> load(Supplier<T> loader) {
        try {
            return Optional.of(loader.get());
        }
        catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
