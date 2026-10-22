# Rev 241 (RuneLite 1.13.3 client, rsprot networking). The classes are Java 21 (jvmToolchain(21)).
FROM eclipse-temurin:21-jre

# The rev 241 cache (built by the gadconv tool; not in git). Pass its location with
#   docker build --build-arg CACHE241=<path inside the build context> .
ARG CACHE241=cache241
ADD "${CACHE241}" "/opt/gadscape/cache241"

ADD "cache/data/grandexchange" "cache/data/grandexchange"
ADD "cache/data/objects" "cache/data/objects"
ADD "cache/data/npcs" "cache/data/npcs"
ADD "data" "data"
ADD "app/build/install/gadscape-server/lib" "application/lib"
ADD "app/build/install/gadscape-server/bin" "application/bin"

# gadscape-server (the Gradle start script) passes JAVA_OPTS to the JVM. rsprot's per-packet validation
# (development mode) must be off in production.
ENV JAVA_OPTS="-Dgadscape.cache.dir=/opt/gadscape/cache241 -Dnet.rsprot.protocol.internal.development=false"

CMD [ "application/bin/gadscape-server" ]
