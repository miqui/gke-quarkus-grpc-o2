package dev.miqui.jobmanager.grpc;

import io.grpc.stub.StreamObserver;

import java.util.function.Supplier;

/**
 * Runs a unary RPC body and answers with its result, or with the google.rpc.Status its exception
 * maps to ({@link GrpcErrorHandler#toStatusException}). Answering from inside the call (rather
 * than letting the exception escape) makes the real status flow through every interceptor's
 * {@code close()}, so metrics and logs record it, not UNKNOWN.
 */
public final class Rpc {

    private Rpc() {
    }

    public static <T> void unary(StreamObserver<T> response, Supplier<T> body) {
        T value;
        try {
            value = body.get();
        } catch (RuntimeException e) {
            response.onError(GrpcErrorHandler.toStatusException(e));
            return;
        }
        response.onNext(value);
        response.onCompleted();
    }
}
