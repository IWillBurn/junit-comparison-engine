package org.atpfivt.comparison.engine;

import org.atpfivt.comparison.api.ComparisonContext;
import org.atpfivt.comparison.api.ComparisonSource;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterClassTemplateInvocationCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeClassTemplateInvocationCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Forwards callbacks to a source at the comparison class or test invocation that owns them. */
record ComparisonSourceLifecycle(ComparisonSource<?> source) implements
        BeforeAllCallback, AfterAllCallback, BeforeClassTemplateInvocationCallback, AfterClassTemplateInvocationCallback,
        BeforeEachCallback, AfterEachCallback {

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
}
