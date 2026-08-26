FROM gradle:8.14-jdk21-alpine AS builder
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S kaft && adduser -S kaft -G kaft
WORKDIR /app
COPY --from=builder --chown=kaft:kaft /app/build/libs/*-all.jar app.jar
USER kaft
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
