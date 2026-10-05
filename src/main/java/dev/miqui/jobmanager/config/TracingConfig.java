package dev.miqui.jobmanager.config;

import dev.miqui.jobmanager.grpc.RpcMetricsInterceptor;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/** The trace sampler (picked up by Quarkus as a CDI bean, replacing quarkus.otel.traces.sampler). */
@Singleton
public class TracingConfig {

    /**
     * Parent-based ratio sampling, as configured, except for two kinds of root span that would
     * only crowd the trace list (and OpenObserve's small PVC):
     * <ul>
     *   <li>CLIENT spans with no parent - the lease reaper's and the jobs gauge's background JDBC
     *       queries, every few seconds on every replica. JDBC spans under an RPC have a parent and
     *       are unaffected;</li>
     *   <li>grpc.health.v1 and server reflection calls (grpcurl and k6 reflect on every
     *       connection).</li>
     * </ul>
     */
    @Produces
    @Singleton
    Sampler sampler(@ConfigProperty(name = "quarkus.otel.traces.sampler.arg", defaultValue = "0.1") double ratio) {
        Sampler delegate = Sampler.parentBased(Sampler.traceIdRatioBased(ratio));
        return new Sampler() {
            @Override
            public SamplingResult shouldSample(Context parentContext, String traceId, String name, SpanKind spanKind,
                                               Attributes attributes, List<LinkData> parentLinks) {
                boolean root = !Span.fromContext(parentContext).getSpanContext().isValid();
                if (root && (spanKind == SpanKind.CLIENT || RpcMetricsInterceptor.isInfrastructure(name))) {
                    return SamplingResult.drop();
                }
                return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
            }

            @Override
            public String getDescription() {
                return "DropRootClientSpans{" + delegate.getDescription() + "}";
            }
        };
    }
}
