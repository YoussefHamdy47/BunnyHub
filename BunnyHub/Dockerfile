# Build stage: compile and package the shaded jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

# Runtime stage: a small JRE image with only the jar.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/BunnyHub-4.00.jar bunnyhub.jar
# Configuration comes from environment variables (TOKEN, MongoURI, FREEBIE_*), never a packaged .env.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar bunnyhub.jar"]
