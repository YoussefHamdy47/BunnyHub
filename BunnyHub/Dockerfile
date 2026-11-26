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
# Caps from the hourly [Memory] lines (Nov 2026): heap 20-31 MB, metaspace 37 MB, code cache 17 MB, resident 135 MB.
# -Xmx192m and 128 MB of metaspace leave 5x and 3x headroom while stopping a runaway from growing the bill.
ENV MALLOC_ARENA_MAX=2
# A small young generation keeps the idle heap near what is really used (20-35 MB) at the price of more, very short,
# collections; one compiler thread and no perf-data file trim the rest.
ENV JAVA_OPTS="-Xms32m -Xmx192m -XX:NewSize=8m -XX:MaxNewSize=16m -XX:CICompilerCount=1 -XX:-UsePerfData -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -XX:TieredStopAtLevel=1 -XX:+UnlockExperimentalVMOptions -XX:TrimNativeHeapInterval=60000 -XX:+ExitOnOutOfMemoryError"
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar bunnyhub.jar"]
