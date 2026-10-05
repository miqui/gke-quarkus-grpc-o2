package dev.miqui.jobmanager.repo;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonTest {

    @Test
    void structsRoundTripAsCompactJson() {
        Struct struct = Struct.newBuilder().putFields("a", Value.newBuilder().setNumberValue(1).build()).build();
        assertThat(Json.print(struct)).isEqualTo("{\"a\":1.0}");
        assertThat(Json.parse("{\"a\": 1}")).isEqualTo(struct);
    }

    @Test
    void labelsAreAFlatStringObject() {
        assertThat(Json.labels(Json.print(Map.of("k", "v")))).containsExactly(Map.entry("k", "v"));
    }

    @Test
    void objectsAreBuiltFromPlainValues() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("s", "x");
        fields.put("n", 2);
        fields.put("b", true);
        fields.put("z", null);
        fields.put("o", Struct.getDefaultInstance());
        assertThat(Json.print(Json.object(fields))).isEqualTo("{\"s\":\"x\",\"n\":2.0,\"b\":true,\"z\":null,\"o\":{}}");
        assertThrows(IllegalArgumentException.class, () -> Json.object(Map.of("x", new Object())));
    }

    @Test
    void nonObjectsAndNonFiniteNumbersAreRejected() {
        assertThrows(IllegalStateException.class, () -> Json.parse("[1]"));
        Struct nan = Struct.newBuilder().putFields("n", Value.newBuilder().setNumberValue(Double.NaN).build()).build();
        assertThrows(IllegalArgumentException.class, () -> Json.print(nan));
    }
}
