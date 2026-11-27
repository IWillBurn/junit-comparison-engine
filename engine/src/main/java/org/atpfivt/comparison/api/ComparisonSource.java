package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.util.List;

import org.apiguardian.api.API;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Selects enum variants and supplies Jupiter extensions for their comparison tests. */
@API(status = EXPERIMENTAL, since = "0.1")
public interface ComparisonSource<V extends Enum<V> & Variant> extends Extension {

    Class<V> variantType();

    /** Lists active variants in discovery order. Keep construction and this method free of resource startup. */
    default List<V> variants() {
        return List.of(variantType().getEnumConstants());
    }

    /** Supplies the parameter resolvers for one test invocation. The engine also resolves the variant enum itself. */
    List<ParameterResolver> parameterResolvers(V variant);
}
