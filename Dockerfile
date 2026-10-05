# syntax=docker/dockerfile:1
# Multi-stage Dockerfile for the Quarkus gRPC API (job-manager-api)

# Stage 1: build the fast-jar with the Maven wrapper. The dependency layer (pom + wrapper + policies)
# is separate from the source layer, so editing src/ doesn't re-download dependencies. Tests run in
# CI (`./mvnw clean verify`, which needs Docker for Dev Services), not here.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
COPY quality/ quality/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package

# Stage 2: runtime (JRE only). Quarkus' fast-jar layout, least to most frequently changing, so a
# code change only rebuilds the last small layers.
FROM eclipse-temurin:21-jre

# Numeric uid/gid 10001: k8s/deployment.yaml sets runAsUser: 10001 + runAsNonRoot, and the kubelet
# can only verify "non-root" for a numeric USER. Above 10000 so it can't collide with a host user.
RUN groupadd -g 10001 app && useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin app

WORKDIR /app
COPY --from=builder /build/target/quarkus-app/lib/ ./lib/
COPY --from=builder /build/target/quarkus-app/quarkus-run.jar ./
COPY --from=builder /build/target/quarkus-app/app/ ./app/
COPY --from=builder /build/target/quarkus-app/quarkus/ ./quarkus/

# Container-aware heap (75% of the memory limit; the rest is metaspace, thread stacks, Netty direct
# buffers). Exit on OOM so the kubelet restarts a clean JVM. The root filesystem is read-only in
# the cluster: /tmp is an emptyDir (Netty's native transport and Vert.x's file cache go there).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

USER 10001:10001
# 9090: gRPC (h2c). 8081: management interface (kubelet probes, load-balancer health check).
EXPOSE 9090 8081

# exec form: java is PID 1 and gets SIGTERM from Kubernetes directly. Readiness goes DOWN for
# quarkus.shutdown.delay, in-flight calls get quarkus.shutdown.timeout, then the JVM exits (143).
# Flyway migrates at startup, serialised across replicas by its own Postgres advisory lock.
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
