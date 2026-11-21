package verification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.CancellationToken;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherExecutionRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Runs selectors like an IDE does (default LauncherFactory, discover() then execute()) and records the tree, the
 * results, the output of every node and violations of the listener contract.
 */
@NullMarked
final class Run implements TestExecutionListener {

    private @Nullable TestPlan plan;
    private final Map<UniqueId, TestExecutionResult> results = new HashMap<>();
    private final Map<UniqueId, String> skipped = new HashMap<>();
    private final Map<UniqueId, String> output = new HashMap<>();
    private final Set<UniqueId> started = new HashSet<>();
    private final CancellationToken cancellation = CancellationToken.create();
    private boolean cancelAfterFirstTest;
    final List<String> violations = new ArrayList<>();

    static Run of(DiscoverySelector... selectors) {
        return of(request -> request.selectors(selectors));
    }

    /** {@code request}: selectors, filters... of the discovery request. */
    static Run of(Consumer<LauncherDiscoveryRequestBuilder> request) {
        return new Run().execute(request);
    }

    /** Cancels the run (as fail-fast does) when the first test finishes. */
    static Run cancelledAfterFirstTest(DiscoverySelector... selectors) {
        Run run = new Run();
        run.cancelAfterFirstTest = true;
        return run.execute(request -> request.selectors(selectors));
    }

    private Run execute(Consumer<LauncherDiscoveryRequestBuilder> request) {
        LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.discoveryRequest()
                .configurationParameter("junit.platform.output.capture.stdout", "true");
        request.accept(builder);
        Launcher launcher = LauncherFactory.create();
        launcher.execute(LauncherExecutionRequestBuilder.request(launcher.discover(builder.build()))
                .listeners(this)
                .cancellationToken(cancellation)
                .build());
        return this;
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        plan = testPlan;
    }

    @Override
    public void executionStarted(TestIdentifier id) {
        id.getParentIdObject().filter(p -> !started.contains(p) || done(p))
                .ifPresent(p -> violations.add("started under a parent that is not running: " + id.getUniqueId()));
        started.add(id.getUniqueIdObject());
    }

    @Override
    public void executionSkipped(TestIdentifier id, String reason) {
        skipped.put(id.getUniqueIdObject(), reason);
    }

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        started.stream().filter(s -> s.hasPrefix(id.getUniqueIdObject()) && !s.equals(id.getUniqueIdObject()) && !done(s))
                .forEach(s -> violations.add("finished before its child: " + id.getUniqueId()));
        results.put(id.getUniqueIdObject(), result);
        if (cancelAfterFirstTest && id.isTest()) {
            cancellation.cancel();
        }
    }

    @Override
    public void reportingEntryPublished(TestIdentifier id, ReportEntry entry) {
        String out = entry.getKeyValuePairs().get("stdout");
        if (out != null) {
            output.merge(id.getUniqueIdObject(), out, String::concat);
        }
    }

    private boolean done(UniqueId id) {
        return results.containsKey(id) || skipped.containsKey(id);
    }

    /** The tree of a test class as the IDE shows it: display names, own status, first line of a failure. */
    String tree(String className) {
        StringBuilder sb = new StringBuilder();
        all().stream().filter(id -> id.getSource().orElse(null) instanceof ClassSource cs
                && cs.getClassName().equals(className)).findFirst().ifPresent(id -> render(id, 0, sb));
        return sb.toString();
    }

    private void render(TestIdentifier id, int depth, StringBuilder sb) {
        TestExecutionResult r = results.get(id.getUniqueIdObject());
        String status = skipped.containsKey(id.getUniqueIdObject()) ? "↷ " + skipped.get(id.getUniqueIdObject())
                : r == null ? "(not executed)" : r.getStatus() == TestExecutionResult.Status.SUCCESSFUL ? "✔"
                        : "✘ " + r.getThrowable().map(Throwable::getMessage).orElse("");
        sb.append("  ".repeat(depth)).append(id.getDisplayName()).append(' ').append(status).append('\n');
        plan().getChildren(id).forEach(c -> render(c, depth + 1, sb));
    }

    List<TestIdentifier> all() {
        List<TestIdentifier> result = new ArrayList<>();
        plan().getRoots().forEach(r -> collect(r, result));
        return result;
    }

    private void collect(TestIdentifier id, List<TestIdentifier> result) {
        result.add(id);
        plan().getChildren(id).forEach(c -> collect(c, result));
    }

    /** Executed leaves as "Class > method > [set] > implementation". */
    List<String> leaves() {
        return all().stream().filter(id -> id.isTest() && results.containsKey(id.getUniqueIdObject()))
                .map(this::path).toList();
    }

    String path(TestIdentifier id) {
        List<String> names = new ArrayList<>();
        for (TestIdentifier cur = id; cur.getParentIdObject().isPresent();
                cur = plan().getTestIdentifier(cur.getParentIdObject().get())) {
            names.addFirst(cur.getDisplayName());
        }
        return String.join(" > ", names);
    }

    String output(TestIdentifier id) {
        return output.getOrDefault(id.getUniqueIdObject(), "");
    }

    String allOutput() {
        return String.join("", output.values());
    }

    TestPlan plan() {
        return Objects.requireNonNull(plan, "the launcher has not started a test plan");
    }
}
