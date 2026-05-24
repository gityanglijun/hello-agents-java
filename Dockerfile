FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre
WORKDIR /app

# Create non-root user
RUN groupadd -r agent && useradd -r -g agent agent

COPY --from=build /app/target/hello-agents-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar

# .env will be mounted at runtime
RUN touch .env && chown agent:agent .env

USER agent
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
CMD ["top", "10"]
