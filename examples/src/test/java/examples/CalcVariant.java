package examples;

import org.atpfivt.comparison.api.ClassVariant;

/** The implementations used in the calculator comparisons. */
public enum CalcVariant implements ClassVariant {
    GOOD(Calc.Good.class),
    BUGGY(Calc.Buggy.class);

    private final Class<? extends Calc> implementation;

    CalcVariant(Class<? extends Calc> implementation) {
        this.implementation = implementation;
    }

    @Override
    public Class<? extends Calc> implementation() {
        return implementation;
    }

    @Override
    public String testName() {
        return implementation.getSimpleName();
    }
}
