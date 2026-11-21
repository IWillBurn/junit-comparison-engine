package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.ComparisonTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class ComparisonSourceTest {

    @Test
    void rejectsClassesTogetherWithSource() {
        Run run = Run.of(selectClass(ClassesAndSource.class));
        String tree = run.tree(ClassesAndSource.class.getName());
        assertTrue(tree.endsWith("declare either implementation classes or a source\n"), tree);
    }

    @Test
    void createsArgumentsPerLeafAndSharesResourcesPerClassInvocation() {
        Source.reset();
        LauncherFactory.create().discover(LauncherDiscoveryRequestBuilder.discoveryRequest()
                .selectors(selectClass(Fixture.class)).build());
        assertEquals(0, Source.arguments.get());
        assertEquals(0, Resource.opened.get());

        Run full = Run.of(selectClass(Fixture.class));
        assertSuccessful(full, 12);
        assertEquals(12, Source.arguments.get());
        assertEquals(2, Resource.opened.get());
        assertEquals(2, Resource.closed.get());

        TestIdentifier selected = full.all().stream().filter(TestIdentifier::isTest)
                .filter(id -> id.getDisplayName().equals("via proxy"))
                .findFirst().orElseThrow();
        Source.reset();
        Run single = Run.of(selectUniqueId(selected.getUniqueIdObject()));
        assertSuccessful(single, 1);
        assertEquals(List.of(full.path(selected)), single.leaves());
        assertEquals(1, Source.arguments.get());
        assertEquals(1, Resource.opened.get());
        assertEquals(1, Resource.closed.get());
    }

    private static void assertSuccessful(Run run, int count) {
        String tree = run.tree(Fixture.class.getName());
        assertFalse(tree.contains("\u2718"), tree);
        assertEquals(count, run.leaves().size(), tree);
        assertEquals(List.of(), run.violations);
    }

    @ComparisonTest(classes = Object.class, source = Source.class)
    static class ClassesAndSource {
    }

    @ComparisonTest(source = Source.class)
    @ParameterizedClass
    @ValueSource(ints = { 7, 8 })
    static class Fixture {
        @Parameter
        int value;

        private final Argument constructed;

        Fixture(Argument argument) {
            this.constructed = argument;
        }

        @BeforeEach
        void beforeEach(Argument argument) {
            assertSame(constructed, argument);
        }

        @Test
        void plain(Argument argument, TestInfo info) {
            assertSame(constructed, argument);
            assertEquals(info.getDisplayName(), argument.name());
            assertFalse(argument.resource().isClosed);
        }

        @ParameterizedTest
        @ValueSource(ints = { 1, 2 })
        void parameterized(int number, Argument argument, TestInfo info) {
            plain(argument, info);
            argument.resource().use(value);
        }
    }

    record Argument(String name, Resource resource) {
    }

    static class Source implements ComparisonSource<Argument> {
        static final AtomicInteger arguments = new AtomicInteger();

        static void reset() {
            arguments.set(0);
            Resource.opened.set(0);
            Resource.closed.set(0);
        }

        @Override
        public Class<Argument> argumentType() {
            return Argument.class;
        }

        @Override
        public List<Variant<Argument>> variants() {
            return List.of(variant("direct"), variant("via proxy"));
        }

        private static Variant<Argument> variant(String name) {
            return new Variant<>(name, store -> {
                var resource = store.computeIfAbsent(Resource.class, key -> new Resource(), Resource.class);
                arguments.incrementAndGet();
                return new Argument(name, resource);
            });
        }
    }

    static class Resource implements AutoCloseable {
        static final AtomicInteger opened = new AtomicInteger();
        static final AtomicInteger closed = new AtomicInteger();

        private boolean isClosed;
        private Integer classArgument;

        Resource() {
            opened.incrementAndGet();
        }

        void use(int value) {
            if (classArgument == null) {
                classArgument = value;
            }
            assertEquals(classArgument.intValue(), value);
        }

        @Override
        public void close() {
            assertFalse(isClosed);
            isClosed = true;
            closed.incrementAndGet();
        }
    }
}
