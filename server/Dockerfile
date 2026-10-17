FROM openjdk:17-jdk-alpine

ADD "cache/data/cache" "cache/data/cache"
ADD "cache/data/grandexchange" "cache/data/grandexchange"
ADD "cache/data/objects" "cache/data/objects"
ADD "cache/data/npcs" "cache/data/npcs"
ADD "data" "data"
ADD "app/build/install/gadscape-server/lib" "application/lib"
ADD "app/build/install/gadscape-server/bin" "application/bin"

CMD [ "application/bin/gadscape-server" ]
