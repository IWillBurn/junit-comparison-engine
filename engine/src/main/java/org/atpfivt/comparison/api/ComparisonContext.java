package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.util.Arrays;
import java.util.Optional;

import org.apiguardian.api.API;
import org.atpfivt.comparison.engine.ImplementationLeaf;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/** Access to the variant and the owning class from a Jupiter extension. */
@API(status = EXPERIMENTAL, since = "0.1")
public final class ComparisonContext {

    private ComparisonContext() {
    }

    /** The active enum variant, if this context represents one comparison invocation. */
    public static Optional<Variant> variant(ExtensionContext context) {
        return context.getTestMethod().map(method -> method.getAnnotation(ImplementationLeaf.class))
                .flatMap(leaf -> Arrays.stream(leaf.variantType().getEnumConstants())
                        .filter(variant -> variant.name().equals(leaf.value())).findFirst());
    }

    /** The comparison class context, or its current class-template invocation when applicable. */
    public static ExtensionContext classContext(ExtensionContext context) {
        var current = Optional.of(context);
        while (current.isPresent()) {
            ExtensionContext candidate = current.orElseThrow();
            if (candidate.getTestMethod().isEmpty() && candidate.getTestClass()
                    .filter(type -> AnnotationSupport.isAnnotated(type, ComparisonTest.class)).isPresent()) {
                return candidate;
            }
            current = candidate.getParent();
        }
        throw new ExtensionConfigurationException("No comparison class context for " + context.getUniqueId());
    }
}
