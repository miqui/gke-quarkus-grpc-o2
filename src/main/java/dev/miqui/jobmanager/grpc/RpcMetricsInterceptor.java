package dev.miqui.jobmanager.grpc;

import com.google.rpc.ErrorInfo;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.grpc.GlobalInterceptor;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.time.Duration;

/**
 * Sees the final status of every RPC (wherever it was produced) and records:
 * <ul>
 *   <li>{@code grpc.server{rpc_service, rpc_method, grpc_status_code}} - latency/throughput, the
 *       same name and labels the Spring Boot service's observation produced, so the gRPC
 *       dashboards carry over;</li>
 *   <li>{@code grpc.errors{rpc_service, rpc_method, grpc_status_code, error_code}} - error_code is
 *       the ErrorInfo reason (BAD_USER_INPUT, NOT_FOUND, CONFLICT, LEASE_NOT_HELD,
 *       INTERNAL_SERVER_ERROR), or the bare gRPC code for statuses the service didn't produce
 *       (e.g. RESOURCE_EXHAUSTED for an oversized message);</li>
 *   <li>one structured log line per RPC.</li>
 * </ul>
 * Health checks and reflection are skipped.
 */
@ApplicationScoped
@GlobalInterceptor
public class RpcMetricsInterceptor implements ServerInterceptor {

    private static final Logger log = Logger.getLogger("dev.miqui.jobmanager.access");

    private final MeterRegistry registry;

    public RpcMetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    public static boolean isInfrastructure(String fullMethodName) {
        return fullMethodName.startsWith("grpc.health.") || fullMethodName.startsWith("grpc.reflection.");
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        var descriptor = call.getMethodDescriptor();
        String method = descriptor.getFullMethodName();
        if (isInfrastructure(method)) {
            return next.startCall(call, headers);
        }
        Timer.Sample sample = Timer.start(registry);
        long start = System.nanoTime();
        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                String service = descriptor.getServiceName();
                String bareMethod = descriptor.getBareMethodName();
                String code = status.getCode().name();
                sample.stop(Timer.builder("grpc.server")
                        .description("gRPC server calls, by method and status")
                        .tag("rpc_service", service)
                        .tag("rpc_method", bareMethod)
                        .tag("grpc_status_code", code)
                        .serviceLevelObjectives(ms(5), ms(10), ms(25), ms(50), ms(100), ms(250), ms(500),
                                ms(1000), ms(2500), ms(5000))
                        .register(registry));
                if (!status.isOk()) {
                    Counter.builder("grpc.errors")
                            .description("gRPC calls that ended with a non-OK status, by method and error code")
                            // Same labels and values as grpc.server, so the two can be divided.
                            .tag("rpc_service", service)
                            .tag("rpc_method", bareMethod)
                            .tag("grpc_status_code", code)
                            .tag("error_code", errorCode(status, trailers))
                            .register(registry)
                            .increment();
                }
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                MDC.put("rpc_method", method);
                MDC.put("grpc_status", code);
                MDC.put("duration_ms", String.valueOf(Math.round(ms * 100) / 100.0));
                try {
                    log.info("rpc");
                } finally {
                    MDC.remove("rpc_method");
                    MDC.remove("grpc_status");
                    MDC.remove("duration_ms");
                }
                super.close(status, trailers);
            }
        }, headers);
    }

    private static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    static String errorCode(Status status, Metadata trailers) {
        com.google.rpc.Status details = StatusProto.fromStatusAndTrailers(status, trailers);
        for (var any : details.getDetailsList()) {
            if (any.is(ErrorInfo.class)) {
                try {
                    return any.unpack(ErrorInfo.class).getReason();
                } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    break;
                }
            }
        }
        return status.getCode().name();
    }
}
