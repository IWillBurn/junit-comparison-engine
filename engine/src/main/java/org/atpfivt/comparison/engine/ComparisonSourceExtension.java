package org.atpfivt.comparison.engine;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.atpfivt.comparison.api.ComparisonContext;
import org.atpfivt.comparison.api.ComparisonSource;
import org.atpfivt.comparison.api.Variant;
import org.atpfivt.comparison.engine.ComparisonClass.Implementation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterClassTemplateInvocationCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeClassTemplateInvocationCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/** Adapts one source to Jupiter's generated class and invocation contexts. */
final class ComparisonSourceExtension<V extends Enum<V> & Variant> implements ParameterResolver,
        BeforeAllCallback, AfterAllCallback, BeforeClassTemplateInvocationCallback, AfterClassTemplateInvocationCallback,
        BeforeEachCallback, AfterEachCallback {

    private static final Namespace NAMESPACE = Namespace.create(ComparisonSourceExtension.class);

    private record Resolvers(List<ParameterResolver> entries) {
    }

    private final ComparisonSource<V> source;
    private final Map<String, Variant> variants;

    ComparisonSourceExtension(ComparisonSource<V> source, List<Implementation> implementations) {
        this.source = source;
        this.variants = implementations.stream().collect(Collectors.toUnmodifiableMap(Implementation::name,
            Implementation::variant));
    }

    // Jupiter inherits extensions into @Nested classes. Class callbacks belong to the enclosing carrier.
    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (source instanceof BeforeAllCallback callback && ComparisonContext.classContext(context) == context) {
            callback.beforeAll(context);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        if (source instanceof AfterAllCallback callback && ComparisonContext.classContext(context) == context) {
            callback.afterAll(context);
        }
    }

    @Override
    public void beforeClassTemplateInvocation(ExtensionContext context) throws Exception {
        if (source instanceof BeforeClassTemplateInvocationCallback callback
                && ComparisonContext.classContext(context) == context) {
            callback.beforeClassTemplateInvocation(context);
        }
    }

    @Override
    public void afterClassTemplateInvocation(ExtensionContext context) throws Exception {
        if (source instanceof AfterClassTemplateInvocationCallback callback
                && ComparisonContext.classContext(context) == context) {
            callback.afterClassTemplateInvocation(context);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        if (source instanceof BeforeEachCallback callback) {
            callback.beforeEach(context);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (source instanceof AfterEachCallback callback) {
            callback.afterEach(context);
        }
    }

    @Override
    public ExtensionContextScope getTestInstantiationExtensionContextScope(ExtensionContext context) {
        return ExtensionContextScope.TEST_METHOD;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        var leaf = context.getTestMethod().orElse(null);
        if (leaf == null || (parameter.getDeclaringExecutable() instanceof Constructor<?> constructor
                && constructor.getDeclaringClass() == leaf.getDeclaringClass())) {
            return false;
        }
        var marker = leaf.getAnnotation(ImplementationLeaf.class);
        return marker != null && (parameter.getParameter().getType() == marker.variantType()
                || resolver(parameter, context, marker.value()) != null);
    }

    @Override
    public @Nullable Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        var marker = context.getRequiredTestMethod().getAnnotation(ImplementationLeaf.class);
        if (parameter.getParameter().getType() == marker.variantType()) {
            return variants.get(marker.value());
        }
        var delegate = resolver(parameter, context, marker.value());
        if (delegate == null) {
            throw new ParameterResolutionException("No resolver for " + parameter.getParameter());
        }
        return delegate.resolveParameter(parameter, context);
    }

    private @Nullable ParameterResolver resolver(ParameterContext parameter, ExtensionContext context, String name) {
        var resolvers = context.getStore(NAMESPACE).computeIfAbsent(name, this::resolvers, Resolvers.class);
        ParameterResolver match = null;
        for (var resolver : resolvers.entries()) {
            if (resolver.supportsParameter(parameter, context)) {
                if (match != null) {
                    throw new ParameterResolutionException("Multiple resolvers for " + parameter.getParameter()
                            + " on comparison variant " + name + ": " + match + ", " + resolver);
                }
                match = resolver;
            }
        }
        return match;
    }

    @SuppressWarnings("unchecked") // Discovery obtained these enum constants from the same source class.
    private Resolvers resolvers(String name) {
        return new Resolvers(List.copyOf(source.parameterResolvers((V) variants.get(name))));
    }
}
