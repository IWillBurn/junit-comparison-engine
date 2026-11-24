package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import examples.CalcVariant;
import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonContext;
import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.api.Variant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterClassTemplateInvocationCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeClassTemplateInvocationCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.StoreScope;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.support.TypeBasedParameterResolver;
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
        assertEquals(1, Source.beforeAll.get());
        assertEquals(1, Source.afterAll.get());
        assertEquals(2, Source.beforeInvocation.get());
        assertEquals(2, Source.afterInvocation.get());
        assertEquals(12, Source.beforeEach.get());
        assertEquals(12, Source.afterEach.get());
        assertEquals(1, RunResource.closed.get());

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
        assertEquals(1, Source.beforeAll.get());
        assertEquals(1, Source.afterAll.get());
        assertEquals(1, Source.beforeInvocation.get());
        assertEquals(1, Source.afterInvocation.get());
        assertEquals(1, Source.beforeEach.get());
        assertEquals(1, Source.afterEach.get());
        assertEquals(1, RunResource.closed.get());
    }

    private static void assertSuccessful(Run run, int count) {
        String tree = run.tree(Fixture.class.getName());
        assertFalse(tree.contains("\u2718"), tree);
        assertEquals(count, run.leaves().size(), tree);
        assertEquals(List.of(), run.violations);
    }

    @ComparisonTest(classes = CalcVariant.class, source = Source.class)
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
        void plain(Argument argument, Endpoint endpoint, Backend variant, TestInfo info) {
            assertSame(constructed, argument);
            assertSame(variant, argument.variant());
            assertSame(variant, endpoint.variant());
            assertEquals(info.getDisplayName(), variant.testName());
            assertFalse(argument.resource().isClosed);
        }

        @ParameterizedTest
        @ValueSource(ints = { 1, 2 })
        @ComparisonArguments(1)
        void parameterized(int number, Argument argument, Endpoint endpoint, Backend variant, TestInfo info) {
            plain(argument, endpoint, variant, info);
            argument.resource().use(value);
        }
    }

    record Argument(Backend variant, Resource resource) {
    }

    record Endpoint(Backend variant) {
    }

    enum Backend implements Variant {
        DIRECT,
        PROXY {
            @Override
            public String testName() {
                return "via proxy";
            }
        }
    }

    static class Source implements ComparisonSource<Backend>, BeforeAllCallback, AfterAllCallback,
            BeforeClassTemplateInvocationCallback, AfterClassTemplateInvocationCallback,
            BeforeEachCallback, AfterEachCallback {
        private static final Namespace NAMESPACE = Namespace.create(Source.class);
        static final AtomicInteger arguments = new AtomicInteger();
        static final AtomicInteger beforeAll = new AtomicInteger();
        static final AtomicInteger afterAll = new AtomicInteger();
        static final AtomicInteger beforeInvocation = new AtomicInteger();
        static final AtomicInteger afterInvocation = new AtomicInteger();
        static final AtomicInteger beforeEach = new AtomicInteger();
        static final AtomicInteger afterEach = new AtomicInteger();
        private boolean started;

        static void reset() {
            arguments.set(0);
            beforeAll.set(0);
            afterAll.set(0);
            beforeInvocation.set(0);
            afterInvocation.set(0);
            beforeEach.set(0);
            afterEach.set(0);
            Resource.opened.set(0);
            Resource.closed.set(0);
            RunResource.closed.set(0);
        }

        @Override
        public void beforeAll(ExtensionContext context) {
            assertFalse(started);
            started = true;
            beforeAll.incrementAndGet();
            context.getStore(StoreScope.EXECUTION_REQUEST, NAMESPACE)
                    .computeIfAbsent(RunResource.class, key -> new RunResource(), RunResource.class);
        }

        @Override
        public void afterAll(ExtensionContext context) {
            assertTrue(started);
            afterAll.incrementAndGet();
        }

        @Override
        public void beforeClassTemplateInvocation(ExtensionContext context) {
            beforeInvocation.incrementAndGet();
        }

        @Override
        public void afterClassTemplateInvocation(ExtensionContext context) {
            afterInvocation.incrementAndGet();
        }

        @Override
        public void beforeEach(ExtensionContext context) {
            assertTrue(started);
            assertTrue(ComparisonContext.variant(context).orElseThrow() instanceof Backend);
            beforeEach.incrementAndGet();
        }

        @Override
        public void afterEach(ExtensionContext context) {
            afterEach.incrementAndGet();
        }

        @Override
        public List<Backend> variants() {
            return List.of(Backend.values());
        }

        @Override
        public List<ParameterResolver> parameterResolvers(Backend variant) {
            assertTrue(started);
            return List.of(new TypeBasedParameterResolver<Argument>() {
                @Override
                public Argument resolveParameter(ParameterContext parameter, ExtensionContext context) {
                    var classStore = ComparisonContext.classContext(context).getStore(NAMESPACE);
                    var resource = classStore.computeIfAbsent(Resource.class, key -> new Resource(), Resource.class);
                    return context.getStore(NAMESPACE).computeIfAbsent(Argument.class, key -> {
                        arguments.incrementAndGet();
                        return new Argument(variant, resource);
                    }, Argument.class);
                }
            }, new TypeBasedParameterResolver<Endpoint>() {
                @Override
                public Endpoint resolveParameter(ParameterContext parameter, ExtensionContext context) {
                    return new Endpoint(variant);
                }
            });
        }
    }

    static class RunResource implements AutoCloseable {
        static final AtomicInteger closed = new AtomicInteger();

        @Override
        public void close() {
            closed.incrementAndGet();
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
