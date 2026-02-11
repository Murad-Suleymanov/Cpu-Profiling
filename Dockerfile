# syntax=docker/dockerfile:1

FROM public.ecr.aws/docker/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package


FROM public.ecr.aws/docker/library/eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/target/workload-app-0.1.0.jar ./app.jar

EXPOSE 8080

# İstəsəniz əlavə JVM flag-ləri verin:
# docker run -e JAVA_OPTS="-Xms1g -Xmx1g" ...
ENV JAVA_OPTS=""

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]

