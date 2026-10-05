package dev.miqui.jobmanager;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The OTel bridge registry exports but doesn't keep values; Quarkus adds every MeterRegistry bean
 * to its composite, so this in-memory one lets tests read what was recorded.
 */
@Singleton
public class TestMetrics {

    @Produces
    @Singleton
    SimpleMeterRegistry simpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
