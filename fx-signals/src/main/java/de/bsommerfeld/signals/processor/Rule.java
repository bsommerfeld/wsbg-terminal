package de.bsommerfeld.signals.processor;

import java.util.function.Predicate;

/**
 * One requirement a signal declaration has to meet, and what the compiler
 * says when it does not.
 *
 * @param <T> what the rule looks at
 */
record Rule<T>(Predicate<T> holds, String violation) {

    static <T> Rule<T> of(String violation, Predicate<T> holds) {
        return new Rule<>(holds, violation);
    }
}
