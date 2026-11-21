# Imagem da API, usada pelo serviço "api" do docker-compose.yml.

# Etapa 1: compila com o Maven Wrapper do repositório (a mesma versão do Maven usada fora do Docker).
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

COPY mvnw pom.xml ./
COPY .mvn .mvn
# Baixa as dependências antes de copiar o código: se só o código mudar, esta etapa vem do cache.
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src src
# Os testes ficam de fora porque precisam do banco rodando (rode ./mvnw test fora do Docker).
RUN ./mvnw -B -ntp package -DskipTests && mv target/grocerystore-*.jar app.jar

# Etapa 2: só o JRE e o jar, rodando com um usuário sem privilégios.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /build/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
