package org.atpfivt.comparison.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.util.List;
import java.util.Objects;

import org.apiguardian.api.API;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

/** Declares comparison variants independently of the objects a test receives. */
@API(status = EXPERIMENTAL, since = "0.1")
public interface ComparisonSource<T> {

    /** The argument type used to find compatible test parameters during discovery. */
    Class<T> argumentType();

    /** Lists the variants in discovery order. Keep this method free of resource startup. */
    List<Variant<T>> variants();

    /** A variant's name is its display name and selection key. Names must be unique, stable, and nonblank. */
    record Variant<T>(String name, Factory<T> factory) {
        public Variant {
            if (name.isBlank()) {
                throw new IllegalArgumentException("A comparison variant name must not be blank");
            }
            Objects.requireNonNull(factory);
        }
    }

    @FunctionalInterface
    interface Factory<T> {

        /**
         * Creates a non-null argument once per leaf that needs it. The store is shared by the variants of the
         * test class (or of one class-template invocation). Register shared closeable resources in that store
         * for Jupiter to close. Returning an argument does not register it for automatic closing.
         */
        T create(Store classStore) throws Exception;
    }
}
