package org.atpfivt.comparison.engine;

import java.util.HashSet;
import java.util.Set;

/** Hands out names unique in one scope: {@code name}, then {@code name<separator>2}, {@code name<separator>3}... */
final class UniqueNames {

    private final String separator;
    private final Set<String> taken = new HashSet<>();

    UniqueNames(String separator) {
        this.separator = separator;
    }

    String next(String name) {
        String candidate = name;
        for (int n = 2; !taken.add(candidate); n++) {
            candidate = name + separator + n;
        }
        return candidate;
    }
}
