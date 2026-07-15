FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /src
COPY src/BaiduAuthServer.java .
RUN javac -encoding UTF-8 --release 17 BaiduAuthServer.java

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /src/*.class ./
EXPOSE 8080
USER 65532:65532
ENTRYPOINT ["java", "BaiduAuthServer"]
