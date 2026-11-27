package verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.atpfivt.comparison.api.ComparisonArguments;
import org.atpfivt.comparison.api.ComparisonContext;
import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.ComparisonTest;
import org.atpfivt.comparison.api.Skip;
import org.atpfivt.comparison.api.SkipMarker;
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
        assertEquals(2, count(tree, "Via proxy ↷ the proxy rejects strings"), tree);
        assertEquals(1, count(tree, "Via proxy ↷ skipped by @SkipOne"), tree);
        assertTrue(tree.contains("Via proxy ↷ whole method disabled"), tree);
        assertEquals(2, count(tree, "↷ disabled by the author despite an invalid skip"), tree);
        assertFalse(tree.contains("✘"), tree);
        assertEquals(List.of(), run.violations);
    }

    @Test
    void readsOneConstantAnArrayOrRepeatedAnnotations() {
        Run run = Run.of(selectClass(ValueShapes.class));
        String tree = run.tree(ValueShapes.class.getName());
        assertEquals(Set.of("scalar(String) > DIRECT", "array(String) > DIRECT", "repeated(String) > DIRECT"),
            methodLeaves(run), tree);
        assertEquals(1, count(tree, "Via proxy ↷ skipped by @SkipOne"), tree);
        assertEquals(1, count(tree, "Via proxy ↷ skipped by @SkipBackend"), tree);
        assertEquals(1, count(tree, "Via proxy ↷ the proxy, by one of two annotations"), tree);
        assertEquals(1, count(tree, "DIRECT ↷ every selected variant"), tree);
        assertEquals(1, count(tree, "Via proxy ↷ every selected variant"), tree);
        assertFalse(tree.contains("✘"), tree);
        assertEquals(List.of(), run.violations);
    }

    @Test
    void skipsByNameWithTheGenericAnnotation() {
        Run run = Run.of(selectClass(Generic.class));
        String tree = run.tree(Generic.class.getName());
        assertEquals(Set.of("plain(String) > DIRECT", "repeated(String) > DIRECT"), methodLeaves(run), tree);
        assertEquals(1, count(tree, "Via proxy ↷ skipped by @Skip"), tree);
        assertEquals(1, count(tree, "Via proxy ↷ the proxy, by name"), tree);
        assertTrue(tree.contains("unknown(String) ↷ @Skip names NOPE but Backend has no such constant"), tree);
        assertFalse(tree.contains("✘"), tree);
        assertEquals(List.of(), run.violations);
    }

    @Test
    void reportsSkipsOfAnotherEnumAsAnUnsupportedMethod() {
        Run run = Run.of(selectClass(ForeignSkip.class));
        String tree = run.tree(ForeignSkip.class.getName());
        assertTrue(tree.contains("↷ @SkipOther skips Other.ONE but the comparison source supplies Backend variants"), tree);
        assertFalse(tree.contains("✘"), tree);
        assertEquals(List.of(), run.violations);
    }

    private static long count(String tree, String line) {
        return tree.lines().filter(text -> text.contains(line)).count();
    }

    /** The executed leaves without the class prefix, in any order. */
    private static Set<String> methodLeaves(Run run) {
        return run.leaves().stream().map(path -> path.substring(path.indexOf(" > ") + 3)).collect(Collectors.toSet());
    }

    @ComparisonTest(source = Source.class)
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class FromSource {
        @ParameterizedTest
        @ValueSource(strings = {"first", "second"})
        @ComparisonArguments(1)
        @SkipBackend(value = {Backend.PROXY, Backend.UNAVAILABLE}, description = "the proxy rejects strings")
        void comparison(String input, String argument) {
            assertTrue(List.of("first", "second").contains(input));
            assertEquals("direct", argument);
        }

        @ParameterizedTest
        @EnumSource(value = Backend.class, names = "PROXY")
        @ComparisonArguments(1)
        @SkipOne(Backend.PROXY)
        void enumArgument(Backend input, Backend variant) {
            assertEquals(Backend.PROXY, input);
            assertEquals(Backend.DIRECT, variant);
        }

        @Test
        @Disabled("whole method disabled")
        @SkipBackend(value = Backend.PROXY, description = "never reported: @Disabled wins")
        void disabled() {
            throw new AssertionError("disabled test was invoked");
        }

        @Test
        @Disabled("disabled by the author despite an invalid skip")
        @Skip("UNKNOWN")
        void disabledWithUnknownSkip() {
            throw new AssertionError("disabled test was invoked");
        }
    }

    /** {@code value()} as one constant, as an array, and the annotation repeated through its container. */
    @ComparisonTest(source = Source.class)
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class ValueShapes {
        @Test
        @SkipOne(Backend.PROXY)
        void scalar(String argument) {
            assertEquals("direct", argument);
        }

        @Test
        @SkipBackend({Backend.PROXY, Backend.UNAVAILABLE})
        void array(String argument) {
            assertEquals("direct", argument);
        }

        @Test
        @SkipBackend(value = Backend.PROXY, description = "the proxy, by one of two annotations")
        @SkipBackend(value = Backend.UNAVAILABLE, description = "unselected: never shown")
        void repeated(String argument) {
            assertEquals("direct", argument);
        }

        @Test
        @SkipBackend(value = {Backend.DIRECT, Backend.PROXY}, description = "every selected variant")
        void all(String argument) {
            throw new AssertionError("every variant is skipped");
        }
    }

    /** The library's {@link Skip}: constants by name, repeatable, validated against the enum. */
    @ComparisonTest(source = Source.class)
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class Generic {
        @Test
        @Skip("PROXY")
        void plain(String argument) {
            assertEquals("direct", argument);
        }

        @Test
        @Skip(value = "PROXY", description = "the proxy, by name")
        @Skip("UNAVAILABLE")
        void repeated(String argument) {
            assertEquals("direct", argument);
        }

        @Test
        @Skip("NOPE")
        void unknown(String argument) {
            throw new AssertionError("a method with an invalid skip was invoked");
        }
    }

    @ComparisonTest(source = Source.class)
    @SuppressWarnings({"JUnitMalformedDeclaration", "NewClassNamingConvention"})
    static class ForeignSkip {
        @Test
        @SkipOther(Other.ONE)
        void foreign(String argument) {
            throw new AssertionError("a method with an invalid skip was invoked");
        }
    }

    /**
     * A project's repeatable skip annotation with an array {@code value()} and the conventional {@code description()}.
     * It must be public: the container's proxy lives in a JDK module and cannot return a package-private type.
     */
    @SkipMarker
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Repeatable(SkipBackend.List.class)
    public @interface SkipBackend {
        Backend[] value();

        String description() default "";

        @Target(ElementType.METHOD)
        @Retention(RetentionPolicy.RUNTIME)
        @interface List {
            SkipBackend[] value();
        }
    }

    /** A single-constant skip annotation without a description: the reason names the annotation. */
    @SkipMarker
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface SkipOne {
        Backend value();
    }

    @SkipMarker
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface SkipOther {
        Other value();
    }

    enum Other {
        ONE
    }

    /** Public for the same reason as {@link SkipBackend}: its proxy returns these constants. */
    public enum Backend implements Variant {
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
        public Class<Backend> variantType() {
            return Backend.class;
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
