FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build/server
COPY server/ ./
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon --console=plain test bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /build/server/build/libs/unistudy-server.jar /app/unistudy-server.jar
ENV UNISTUDY_DB=/var/data/unistudy.db
ENV UNISTUDY_STORAGE=/var/data/files
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:MaxDirectMemorySize=64m"
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/app/unistudy-server.jar", "--server.address=0.0.0.0"]
