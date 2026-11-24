package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.atpfivt.comparison.api.ClassVariant;
import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonContext;
import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.api.Skip;
import org.atpfivt.comparison.api.Variant;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.support.TypeBasedParameterResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@NullMarked
class VariantSkipTest {

    @Test
    void skipsByNameWhenTwoVariantsUseTheSameClass() {
        Argument.created.set(0);
        Run run = Run.of(selectClass(FromClasses.class));
        String tree = run.tree(FromClasses.class.getName());
        assertEquals(3, Argument.created.get(), tree);
        assertEquals(4, run.leaves().size(), tree);
        assertTrue(run.leaves().stream().allMatch(path -> path.endsWith(" > PRIMARY")), tree);
        assertTrue(tree.contains("Secondary backend ↷ Comparison variant disabled by @Skip"), tree);
        assertTrue(tree.contains("Secondary backend ↷ whole method disabled"), tree);
        assertFalse(tree.contains("✘"), tree);
        assertEquals(List.of(), run.violations);
    }

    @Test
    void skipsSourceVariantsOnEveryArgumentSetBeforeCreatingTheirArguments() {
        Run run = Run.of(selectClass(FromSource.class));
        String tree = run.tree(FromSource.class.getName());
        assertEquals(2, run.leaves().size(), tree);
        assertTrue(run.leaves().stream().allMatch(path -> path.endsWith(" > DIRECT")), tree);
        assertFalse(run.all().stream().anyMatch(node -> node.getDisplayName().equals(Backend.UNAVAILABLE.testName())));
        assertEquals(2, tree.lines()
                .filter(line -> line.contains("Via proxy ↷ Comparison variant disabled by @Skip")).count(), tree);
        assertFalse(tree.contains("✘"), tree);
        assertEquals(List.of(), run.violations);
    }

    @ComparisonTest(classes = ClassBackend.class)
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention", "ClassCanBeRecord"})
    static class FromClasses {
        private final ClassBackend constructed;

        FromClasses(ClassBackend constructed) {
            this.constructed = constructed;
        }

        @Test
        @Skip("SECONDARY")
        void comparison(Argument argument, ClassBackend variant) {
            assertEquals(ClassBackend.PRIMARY, variant);
            assertEquals(constructed, variant);
        }

        @ParameterizedTest
        @ValueSource(strings = {"first", "second"})
        @Skip("SECONDARY")
        void parameterized(String input, ClassBackend variant, Argument argument) {
            assertTrue(List.of("first", "second").contains(input));
            comparison(argument, variant);
        }

        @ParameterizedTest
        @EnumSource(value = ClassBackend.class, names = "SECONDARY")
        @ComparisonArguments(1)
        @Skip("SECONDARY")
        void enumArgument(ClassBackend input, ClassBackend variant) {
            assertEquals(ClassBackend.SECONDARY, input);
            assertEquals(ClassBackend.PRIMARY, variant);
        }

        @Test
        @Disabled("whole method disabled")
        @Skip("SECONDARY")
        void disabled(Argument argument) {
            throw new AssertionError("disabled test was invoked");
        }
    }

    enum ClassBackend implements ClassVariant {
        PRIMARY,
        SECONDARY {
            @Override
            public String testName() {
                return "Secondary backend";
            }
        };

        @Override
        public Class<Argument> implementation() {
            return Argument.class;
        }
    }

    static class Argument {
        static final AtomicInteger created = new AtomicInteger();

        Argument() {
            created.incrementAndGet();
        }
    }

    @ComparisonTest(source = Source.class)
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class FromSource {
        @ParameterizedTest
        @ValueSource(strings = {"first", "second"})
        @ComparisonArguments(1)
        @Skip({"PROXY", "UNAVAILABLE"})
        void comparison(String input, String argument) {
            assertTrue(List.of("first", "second").contains(input));
            assertEquals("direct", argument);
        }
    }

    enum Backend implements Variant {
        DIRECT,
        PROXY {
            @Override
            public String testName() {
                return "Via proxy";
            }
        },
        UNAVAILABLE
    }

    static class Source implements ComparisonSource<Backend>, BeforeEachCallback {
        @Override
        public void beforeEach(ExtensionContext context) {
            assertEquals(Backend.DIRECT, ComparisonContext.variant(context).orElseThrow());
        }

        @Override
        public List<Backend> variants() {
            return List.of(Backend.DIRECT, Backend.PROXY);
        }

        @Override
        public List<ParameterResolver> parameterResolvers(Backend variant) {
            if (variant == Backend.PROXY) {
                throw new AssertionError("a skipped variant must not create an argument");
            }
            return List.of(new TypeBasedParameterResolver<String>() {
                @Override
                public String resolveParameter(ParameterContext parameter, ExtensionContext context) {
                    return "direct";
                }
            });
        }
    }
}
