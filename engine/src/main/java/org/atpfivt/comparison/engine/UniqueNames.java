package org.atpfivt.comparison.engine;

import java.util.HashSet;
import java.util.Set;

/** Hands out unique names in one scope, using suffixes such as {@code _2} for duplicates. */
final class UniqueNames {

    private final Set<String> taken = new HashSet<>();

    String next(String name) {
        String candidate = name;
        for (int n = 2; !taken.add(candidate); n++) {
            candidate = name + "_" + n;
        }
        return candidate;
    }
}
