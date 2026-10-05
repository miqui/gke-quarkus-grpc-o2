package dev.miqui.jobmanager.validation;

import dev.miqui.jobmanager.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ViolationsTest {

    @Test
    void textIsTrimmedAndBounded() {
        Violations v = new Violations();
        assertThat(v.text("a", "  x  ", 5)).isEqualTo("x");
        v.text("b", null, 5);
        v.text("c", "123456", 5);
        v.text("d", "x\0", 5);
        ApiException.BadInput e = assertThrows(ApiException.BadInput.class, v::throwIfAny);
        assertThat(e.violations()).extracting(f -> f.getField()).containsExactly("b", "c", "d");
    }

    @Test
    void optionalTextTreatsBlankAsAbsent() {
        Violations v = new Violations();
        assertThat(v.optionalText("k", "   ", 5)).isNull();
        assertThat(v.optionalText("k", null, 5)).isNull();
        assertThat(v.optionalText("k", " ab ", 5)).isEqualTo("ab");
        v.throwIfAny();
    }

    @Test
    void uuidsMustBeCanonical() {
        Violations v = new Violations();
        assertThat(v.uuid("id", "00000000-0000-0000-0000-000000000001")).isNotNull();
        assertThat(v.uuid("id", "1-2-3-4-5")).isNull();
        assertThat(v.uuid("id", null)).isNull();
        assertThat(assertThrows(ApiException.BadInput.class, v::throwIfAny).violations()).hasSize(2);
    }

    @Test
    void registeredTypeIsOnlyLookedUpWhenWellFormed() {
        Violations v = new Violations();
        assertThat(v.registeredType("type", " demo.echo ", List.of("demo.echo"))).isEqualTo("demo.echo");
        v.registeredType("type", "", List.of("demo.echo"));
        assertThat(assertThrows(ApiException.BadInput.class, v::throwIfAny).violations())
                .singleElement().satisfies(f -> assertThat(f.getDescription()).contains("required"));
    }

    @Test
    void absentRangesFallBackAndOffsetsDefaultToZero() {
        Violations v = new Violations();
        assertThat(v.range("p", false, 99, 0, 9, 3)).isEqualTo(3);
        assertThat(v.range("p", false, 99, 0, 9, null)).isNull();
        assertThat(v.limit("limit", false, 0)).isEqualTo(Violations.LIMIT_DEFAULT);
        assertThat(v.offset("offset", false, -5)).isZero();
        v.throwIfAny();
    }
}
