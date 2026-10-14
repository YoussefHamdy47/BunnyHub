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
# Memory: the bot needs ~100-150 MB of heap, so cap it instead of letting the JVM claim 75% of the container
# (6 GB on an 8 GB plan) and keep it. Serial GC is the lightest collector for one small app and hands freed heap
# back; smaller thread stacks and bounded metaspace/code cache trim the rest. On OutOfMemoryError the process
# exits and Railway's restart policy brings it back, instead of a half-working bot.
ENV JAVA_OPTS="-Xms64m -Xmx384m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -Xss512k -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:+ExitOnOutOfMemoryError"
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar bunnyhub.jar"]
