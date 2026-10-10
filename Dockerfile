FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY order-service/pom.xml order-service/pom.xml
COPY gateway/pom.xml gateway/pom.xml
RUN mvn -B -ntp dependency:go-offline
COPY order-service/src order-service/src
COPY gateway/src gateway/src
RUN mvn -B -ntp package -DskipTests
FROM eclipse-temurin:21-jre-alpine
ARG MODULE=order-service
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/${MODULE}/target/${MODULE}-1.0.0.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"
ENTRYPOINT ["java","-jar","/app/app.jar"]
