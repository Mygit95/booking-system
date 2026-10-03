#FROM eclipse-temurin:21-jre
#
#WORKDIR /app
#
#COPY target/*.jar app.jar
#
#EXPOSE 8080
#
#ENTRYPOINT ["java", "-jar", "app.jar"]

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

RUN chmod +x mvnw
RUN ./mvnw -B dependency:go-offline

COPY src src

RUN ./mvnw -B clean package -DskipTests


FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]