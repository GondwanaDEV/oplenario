# imagem do monolito (placeholder dev — trocar por uberjar + JRE no setup de producao)
FROM clojure:temurin-21-tools-deps
WORKDIR /app
COPY deps.edn .
RUN clojure -P
COPY . .
EXPOSE 8888
CMD ["clojure", "-M", "-m", "oplenario.main"]
