(ns oplenario.cadastros.adapters.in.vereador
  "Gate de ENTRADA wire/in -> dominio das 4 escritas (§22.10 adapters/in, ADR-0001, Onda D Slice 4). Chamado
  SO' pelo diplomat/. Valida (fail-closed -> :validacao/invalido -> 400) e COAGE (datas ISO -> LocalDate,
  legislatura-id -> UUID); INJETA o que nao vem do corpo (`id` gerado, `ente-id`/`vereador-id` do ator/path,
  §22.5). `cadastros` nao importa outro modulo (§22.10) — usa `parse-uuid` do core, nao o helper de legislativo."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [oplenario.cadastros.wire.in.vereador :as wire])
  (:import (java.time LocalDate)
           (java.time.format DateTimeParseException)))

(set! *warn-on-reflection* true)

(defn- invalido! [msg info] (throw (ex-info msg (assoc info :tipo :validacao/invalido))))

(defn- keywordizar
  "Converte TODAS as chaves string do corpo p/ keyword (sem filtrar) — precisa preservar chave forjada
  (ex.: \"identidade-id\") ate' a validacao, senao o :closed do schema nunca a enxerga p/ recusar (a
  checagem anti-forja mora no schema, nao numa allowlist manual antes dele)."
  [m]
  (reduce-kv (fn [acc k v] (assoc acc (keyword k) v)) {} m))

(defn- validar! [schema m msg]
  (when-let [erros (m/explain schema m)]
    ;; guarda so' os nomes-de-campo (NUNCA o payload cru — m/explain embute :value = vazaria dado).
    (invalido! msg {:campos (keys (me/humanize erros))})))

(def ^:private data-iso-civil
  "AAAA-MM-DD com QUATRO digitos de ano e sem sinal. `LocalDate/parse` tambem aceita o formato ISO de ano
   ESTENDIDO (`+10000000-01-01`, `+999999999-12-31`): parseia sem excecao, atravessa a borda inteira e so'
   estoura la' embaixo, no bind do driver, como PSQLException 22008 (`date out of range` — o `date` do
   Postgres vai ate' 5874897 AD) — que nao e' `ExceptionInfo`, nao casa em nenhum catch de conflito e vira
   500 'erro interno' em vez de 400. Recusar ANTES do parse fecha o buraco nas TRES rotas de data do modulo
   de uma vez, que e' o motivo de a checagem morar no helper e nao em cada schema."
  #"^\d{4}-\d{2}-\d{2}$")

(defn- ->data!
  "String ISO (AAAA-MM-DD) -> LocalDate. nil-safe (nil -> nil, p/ campos opcionais). Formato fora do
   contrato ou data inexistente -> 400 (NUNCA 500 mais adiante — ver `data-iso-civil`)."
  [s campo]
  (when (some? s)
    (when-not (re-matches data-iso-civil s)
      (invalido! "data invalida (esperado AAAA-MM-DD)" {:campos [campo]}))
    (try (LocalDate/parse s)
      (catch DateTimeParseException _ (invalido! "data invalida (esperado AAAA-MM-DD)" {:campos [campo]})))))

(defn- ->uuid! [s campo]
  (or (parse-uuid s) (invalido! "identificador invalido" {:campos [campo]})))

(defn- ->nil-se-branco
  "nome_parlamentar e' um nome real OU NULL — nunca string vazia. Limpar o apelido (mandar \"\") vira nil."
  [s]
  (when-not (str/blank? s) s))

(defn criar-vereador->dominio [ator wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/CriarVereador mm "corpo de criar vereador invalido")
    (when (str/blank? (:nome mm)) (invalido! "nome obrigatorio (nao-branco)" {:campos [:nome]}))
    {:id (random-uuid) :ente-id (:ente-id ator) :nome (:nome mm)
     :nome-parlamentar (->nil-se-branco (:nome-parlamentar mm))}))

(defn editar-vereador->dominio [wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/EditarVereador mm "corpo de editar vereador invalido")
    (when-not (or (contains? mm :nome) (contains? mm :nome-parlamentar))
      (invalido! "informe ao menos um campo (nome ou nome-parlamentar)" {:campos [:nome :nome-parlamentar]}))
    (when (and (contains? mm :nome) (str/blank? (:nome mm)))
      (invalido! "nome nao pode ser vazio" {:campos [:nome]}))
    ;; PATCH parcial: SO' as chaves presentes (contains?), pra atualizar! nunca zerar o que o cliente omitiu.
    ;; nome-parlamentar presente-mas-branco = limpar o apelido -> nil (NULL no banco), nao "".
    (cond-> {}
      (contains? mm :nome)             (assoc :nome (:nome mm))
      (contains? mm :nome-parlamentar) (assoc :nome-parlamentar (->nil-se-branco (:nome-parlamentar mm))))))

(defn registrar-mandato->dominio [ator vereador-id wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/RegistrarMandato mm "corpo de registrar mandato invalido")
    (let [inicio (->data! (:vigencia-inicio mm) :vigencia-inicio)
          fim (->data! (:vigencia-fim mm) :vigencia-fim)]
      (when (and (some? inicio) (some? fim) (.isBefore ^LocalDate fim inicio))
        (invalido! "vigencia-fim nao pode ser anterior a vigencia-inicio" {:campos [:vigencia-fim]}))
      {:id (random-uuid) :ente-id (:ente-id ator) :vereador-id vereador-id
       :legislatura-id (->uuid! (:legislatura-id mm) :legislatura-id)
       :partido (:partido mm) :estado "vigente" :natureza (:natureza mm)
       :vigencia-inicio inicio
       :vigencia-fim fim})))

(defn registrar-licenca->dominio
  "`hoje` (resolvido na borda pelo relogio injetado) e' o TETO de `inicio`: licenca nao pode COMECAR no
   futuro. Nao e' preciosismo de validacao — `registrar-licenca!` crava `mandato.estado = 'licenciado'` no
   INSTANTE do POST, e nao ha' agendador nenhum que faca esse flip esperar o inicio. Aceitar `inicio` futuro
   (a \"licenca planejada\") tira o vereador do exercicio HOJE: `tem-mandato-vigente?` exige estado
   'vigente', e essa e' a policy de `meu-voto` — ele para de conseguir votar semanas antes de se afastar.
   Pior, o estado vira um beco sem saida: a reassuncao nao fecha licenca cujo `inicio` e' posterior a' volta,
   e o teto de `hoje` de `reassumir-mandato->dominio` impede alcancar a unica data que a fecharia. Recusar na
   borda e' o que mantem o estado 'licenciado' fiel ao que ele significa. Licenca RETROATIVA (`inicio` no
   passado) segue de primeira classe — e' o caso comum da secretaria."
  [ator ^LocalDate hoje wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/RegistrarLicenca mm "corpo de registrar licenca invalido")
    (let [inicio (->data! (:inicio mm) :inicio)
          fim (->data! (:fim mm) :fim)]
      (when (and (some? inicio) (.isAfter ^LocalDate inicio hoje))
        (invalido! "inicio da licenca nao pode ser posterior a hoje" {:campos [:inicio]}))
      {:id (random-uuid) :ente-id (:ente-id ator) :inicio inicio :fim fim :motivo (:motivo mm)})))

(defn reassumir-mandato->dominio
  "Corpo da reassuncao -> a LocalDate do dia da VOLTA. Devolve so' a data (nao um mapa), mesma forma de
   `ligar-identidade->dominio`: e' o unico campo do contrato, e o Repo recebe `ente-id`/`vereador-id` do
   ator/path. NAO faz a aritmetica do -1 dia — ela mora no Repo, junto do UPDATE que a usa, para que exista
   UMA fonte da convencao de borda inclusiva e nao duas.

   `hoje` (resolvido na borda pelo relogio injetado) e' o TETO, e ele nao contradiz o \"fato datado que a
   secretaria registra depois\": fato datado e' no PASSADO — e' exatamente o que justifica o teto. Sem ele,
   `2999-01-01` no lugar de `2029-01-01` grava `fim = 2998-12-31` na licenca aberta e a linha fica
   INALCANCAVEL para sempre (o unico UPDATE da tabela so' casa `fim IS NULL`, nao ha' DELETE — Inv. 10, nem
   PATCH de licenca), enquanto o mandato volta a 'vigente' e conta para o quorum de HOJE: a pagina publica
   nominal passa a publicar 'exercicio de 2999 em diante' e presenca 0 de 0 para quem esta' em plena
   atividade. Registrar mandato novo + reassumir NAO conserta — `janelas-de-exercicio` subtrai a UNIAO dos
   intervalos, entao o buraco so' cresce. Mesmo precedente de checagem de borda de
   `registrar-mandato->dominio` (vigencia-fim anterior a vigencia-inicio -> 400)."
  [^LocalDate hoje wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)
        _ (validar! wire/ReassumirMandato mm "corpo de reassumir mandato invalido")
        dia (->data! (:reassumiu-em mm) :reassumiu-em)]
    (when (and (some? dia) (.isAfter ^LocalDate dia hoje))
      (invalido! "data de reassuncao nao pode ser posterior a hoje" {:campos [:reassumiu-em]}))
    dia))

(defn ligar-identidade->dominio
  "Task 9 (Onda D Slice 5) — coage `identidade-id` do corpo pra UUID (`:validacao/invalido` -> 400 se
   ausente/mal-formado, mesmo caminho de ->uuid! usado em registrar-mandato->dominio p/ legislatura-id)."
  [wire-in]
  (when-not (map? wire-in) (invalido! "corpo deve ser objeto JSON" {:campo :corpo}))
  (let [mm (keywordizar wire-in)]
    (validar! wire/LigarIdentidade mm "corpo de ligar identidade invalido")
    (->uuid! (:identidade-id mm) :identidade-id)))
