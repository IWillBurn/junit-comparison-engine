package examples;

import java.util.List;
import java.util.function.Supplier;

import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.Variant;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.support.TypeBasedParameterResolver;

/** Supplies one calculator per test invocation, shared with its constructor and lifecycle methods. */
@NullMarked
public final class CalcSource implements ComparisonSource<CalcSource.CalcVariant> {

    private static final Namespace NAMESPACE = Namespace.create(CalcSource.class);

    public enum CalcVariant implements Variant {
        GOOD("Good", Calc.Good::new),
        BUGGY("Buggy", Calc.Buggy::new);

        private final String testName;
        private final Supplier<Calc> create;

        CalcVariant(String testName, Supplier<Calc> create) {
            this.testName = testName;
            this.create = create;
        }

        @Override
        public String testName() {
            return testName;
        }
    }

    @Override
    public List<CalcVariant> variants() {
        return List.of(CalcVariant.values());
    }

    @Override
    public List<ParameterResolver> parameterResolvers(CalcVariant variant) {
        return List.of(new TypeBasedParameterResolver<Calc>() {
            @Override
            public Calc resolveParameter(ParameterContext parameter, ExtensionContext context) {
                return context.getStore(NAMESPACE).computeIfAbsent(Calc.class, key -> variant.create.get(), Calc.class);
            }
        });
    }
}
