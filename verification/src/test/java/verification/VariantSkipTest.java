package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.List;

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
    void skipsSourceVariantsOnEveryArgumentSetBeforeCreatingTheirArguments() {
        Run run = Run.of(selectClass(FromSource.class));
        String tree = run.tree(FromSource.class.getName());
        assertEquals(3, run.leaves().size(), tree);
        assertTrue(run.leaves().stream().allMatch(path -> path.endsWith(" > DIRECT")), tree);
        assertFalse(run.all().stream().anyMatch(node -> node.getDisplayName().equals(Backend.UNAVAILABLE.testName())));
        assertEquals(3, tree.lines()
                .filter(line -> line.contains("Via proxy ↷ Comparison variant disabled by @Skip")).count(), tree);
        assertTrue(tree.contains("Via proxy ↷ whole method disabled"), tree);
        assertFalse(tree.contains("✘"), tree);
        assertEquals(List.of(), run.violations);
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

        @ParameterizedTest
        @EnumSource(value = Backend.class, names = "PROXY")
        @ComparisonArguments(1)
        @Skip("PROXY")
        void enumArgument(Backend input, Backend variant) {
            assertEquals(Backend.PROXY, input);
            assertEquals(Backend.DIRECT, variant);
        }

        @Test
        @Disabled("whole method disabled")
        @Skip("PROXY")
        void disabled() {
            throw new AssertionError("disabled test was invoked");
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
