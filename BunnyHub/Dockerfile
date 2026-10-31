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
# Native memory: glibc keeps up to 8 memory pools per CPU; 2 is plenty for this bot. Only the C1 compiler runs
# (CPU is idle, peak speed does not matter), so compiled code fits in 32 MB, and freed native memory is returned
# to the OS every minute (TrimNativeHeapInterval, checked on this image's Temurin 21.0.13).
# -Xmx and MaxMetaspaceSize stay until the hourly [Memory] log lines show real use.
ENV MALLOC_ARENA_MAX=2
ENV JAVA_OPTS="-Xms64m -Xmx384m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -Xss512k -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:+UnlockExperimentalVMOptions -XX:TrimNativeHeapInterval=60000 -XX:+ExitOnOutOfMemoryError"
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar bunnyhub.jar"]
