package dev.miqui.jobmanager.config;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TracingConfigTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private final Sampler sampler = new TracingConfig().sampler(1.0);

    private SamplingDecision decide(Context parent, String name, SpanKind kind) {
        return sampler.shouldSample(parent, TRACE_ID, name, kind, Attributes.empty(), List.of()).getDecision();
    }

    private static Context sampledParent() {
        return Context.root().with(Span.wrap(SpanContext.create(TRACE_ID, "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault())));
    }

    @Test
    void backgroundQueriesWithoutAnRpcAreDropped() {
        assertThat(decide(Context.root(), "SELECT messagedb.jobs", SpanKind.CLIENT)).isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void healthAndReflectionCallsAreDropped() {
        assertThat(decide(Context.root(), "grpc.health.v1.Health/Check", SpanKind.SERVER)).isEqualTo(SamplingDecision.DROP);
        assertThat(decide(Context.root(), "grpc.reflection.v1.ServerReflection/ServerReflectionInfo", SpanKind.SERVER))
                .isEqualTo(SamplingDecision.DROP);
    }

    @Test
    void rpcsAndTheirChildSpansFollowTheRatio() {
        assertThat(decide(Context.root(), "job.v1.JobService/GetJob", SpanKind.SERVER))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(decide(sampledParent(), "SELECT messagedb.jobs", SpanKind.CLIENT))
                .isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(new TracingConfig().sampler(0.0).shouldSample(Context.root(), TRACE_ID, "job.v1.JobService/GetJob",
                SpanKind.SERVER, Attributes.empty(), List.of()).getDecision()).isEqualTo(SamplingDecision.DROP);
        assertThat(sampler.getDescription()).startsWith("DropRootClientSpans");
    }
}
