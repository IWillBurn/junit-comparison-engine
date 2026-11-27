package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.util.List;

import examples.Calc;
import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.api.Variant;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.support.TypeBasedParameterResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The current variant is injected as the enum, as {@link Variant}, or as an interface in between. */
@NullMarked
class VariantParameterTest {

    @Test
    void injectsTheVariantAsEnumInterfaceOrOwnInterface() {
        Run run = Run.of(selectClass(Receives.class));
        String tree = run.tree(Receives.class.getName());
        assertEquals(10, run.leaves().size(), tree);
        assertFalse(tree.contains("✘"), tree);
        assertFalse(tree.contains("↷"), tree);
        assertEquals(List.of(), run.violations);
    }

    /** An interface of the project between the enum and {@link Variant}. */
    interface Named extends Variant {
        String label();
    }

    @ComparisonTest(source = Source.class)
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class Receives {
        private final Variant constructed;

        Receives(Variant variant) {
            constructed = variant;
        }

        @BeforeEach
        void before(Backend backend, TestInfo info) {
            assertSame(constructed, backend);
            assertEquals(backend.testName(), info.getDisplayName());
        }

        @Test
        void asEnum(Backend backend, TestInfo info) {
            assertSame(constructed, backend);
            assertEquals(backend.testName(), info.getDisplayName());
        }

        @Test
        void asVariant(Variant variant, TestInfo info) {
            assertSame(constructed, variant);
            assertEquals(variant.testName(), info.getDisplayName());
        }

        @Test
        void asOwnInterface(Named named, TestInfo info) {
            assertSame(constructed, named);
            assertEquals(named.label(), info.getDisplayName());
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        @ComparisonArguments(1)
        void afterArguments(int input, Variant variant, Backend backend, Calc calc) {
            assertTrue(input > 0);
            assertSame(backend, variant);
            assertEquals(backend == Backend.BUGGY ? 21 : 20, calc.mul(4, 5));
        }

        @AfterEach
        void after(Variant variant) {
            assertSame(constructed, variant);
        }
    }

    enum Backend implements Named {
        GOOD("Good"),
        BUGGY("Buggy");

        private final String label;

        Backend(String label) {
            this.label = label;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String testName() {
            return label;
        }
    }

    /** Supplies the calculators of the examples module for the two backends. */
    static class Source implements ComparisonSource<Backend> {
        @Override
        public Class<Backend> variantType() {
            return Backend.class;
        }

        @Override
        public List<ParameterResolver> parameterResolvers(Backend variant) {
            return List.of(new TypeBasedParameterResolver<Calc>() {
                @Override
                public Calc resolveParameter(ParameterContext parameter, ExtensionContext context) {
                    return variant == Backend.GOOD ? new Calc.Good() : new Calc.Buggy();
                }
            });
        }
    }
}
