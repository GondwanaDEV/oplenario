# Imagem de producao do monolito (F0.7): build do uberjar + runtime so-JRE.

# ---- build: gera o uberjar ----
FROM clojure:temurin-21-tools-deps AS build
WORKDIR /app
# cache de layer: prefetch das deps (main + alias :build) antes de copiar o src
COPY deps.edn build.clj ./
RUN clojure -P && clojure -P -T:build
COPY src ./src
COPY resources ./resources
RUN clojure -T:build uber

# ---- runtime: so JRE + uberjar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/oplenario.jar ./oplenario.jar
EXPOSE 8888
# sem-arg = serve (sobe o sistema Component e bloqueia); `migrate` aplica migrations e sai (init step, §22.9).
ENTRYPOINT ["java", "-jar", "oplenario.jar"]
