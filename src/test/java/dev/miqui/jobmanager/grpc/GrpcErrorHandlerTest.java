package dev.miqui.jobmanager.grpc;

import com.google.rpc.ErrorInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcErrorHandlerTest {

    @Test
    void anUnexpectedExceptionBecomesAGenericInternalError() throws Exception {
        StatusException e = GrpcErrorHandler.toStatusException(new IllegalStateException("secret detail"));

        com.google.rpc.Status status = StatusProto.fromThrowable(e);
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getMessage()).isEqualTo("An unexpected error occurred.").doesNotContain("secret");
        assertThat(status.getDetails(0).unpack(ErrorInfo.class).getReason()).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @Test
    void aStatusExceptionPassesThroughUnchanged() {
        StatusException original = Status.UNAVAILABLE.asException();
        assertThat(GrpcErrorHandler.toStatusException(original)).isSameAs(original);
    }

    @Test
    void theErrorCodeFallsBackToTheGrpcCodeWithoutErrorInfo() {
        assertThat(RpcMetricsInterceptor.errorCode(Status.RESOURCE_EXHAUSTED, new Metadata()))
                .isEqualTo("RESOURCE_EXHAUSTED");
        StatusException mapped = GrpcErrorHandler.toStatusException(new IllegalStateException());
        assertThat(RpcMetricsInterceptor.errorCode(mapped.getStatus(), mapped.getTrailers()))
                .isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @Test
    void infrastructureMethodsAreRecognised() {
        assertThat(RpcMetricsInterceptor.isInfrastructure("grpc.health.v1.Health/Check")).isTrue();
        assertThat(RpcMetricsInterceptor.isInfrastructure("grpc.reflection.v1.ServerReflection/ServerReflectionInfo")).isTrue();
        assertThat(RpcMetricsInterceptor.isInfrastructure("job.v1.JobService/GetJob")).isFalse();
    }
}
