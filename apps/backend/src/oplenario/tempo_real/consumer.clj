(ns oplenario.tempo-real.consumer
  "CORACAO do tempo real (§22.6 eixo G): registra consumidores no bus (outbox) que PROJETAM os eventos de
  dominio em mensagens de canal e as PUBLICAM na CanalStore. 'SSE e' projecao do bus interno' — o relay
  (sistema.clj) drena o shared.outbox e despacha a estes handlers. O handler roda na tx do relay
  (effectively-once via dedup do inbox por (consumidor, idempotency-key)).

  TOLERANCIA A PAYLOAD MALFORMADO (frente 'relay-poison-tolerante'): este handler e' consumidor do MESMO
  relay compartilhado por TODOS os modulos (§22.9) — se ele lancar, o evento vira POISON (reprocessado a
  cada tick para sempre) e bloqueia HEAD-OF-LINE todo evento de id maior, de QUALQUER modulo, de TODOS os
  tenants (o mesmo achado das frentes 'relay-tolerante'/transparencia e 'truncamento-familia'). `handler`
  (abaixo) e' a FRONTEIRA DE DESPACHO onde mora a guarda — nao `projecao/projetar` nem `projecao/dados-publicos`:
  o `throw` de sigilo em `dados-publicos` (voto.registrado com modalidade fora de {nominal,secreta}) FICA —
  esta' correto e e' o ULTIMO portao antes do canal (ver a docstring dele); o defeito nao e' o `throw`, e'
  onde ele MORRIA (dentro da tx do relay). A guarda aqui NAO enfraquece o gate: so decide o que acontece
  DEPOIS que ele fechou fail-closed."
  (:require [clojure.tools.logging :as log]
            [oplenario.kernel.outbox :as outbox]
            [oplenario.tempo-real.canais :as canais]
            [oplenario.tempo-real.components :as comp]
            [oplenario.tempo-real.projecao :as projecao]))

(def ^:private nome-consumidor "tempo-real-sse")

(def tipos-consumidos
  "Tipos consumidos pelo projetor SSE — DERIVADOS de canais/tipos-plenario (fonte unica; evita drift entre o
  roteamento e o registro no bus). gravacao.segmento-captado fica de fora (nao esta no set; fronteira core->IA)."
  (vec canais/tipos-plenario))

(defn- payload-malformado?
  "Classifica `t` como falha de FORMA DO PAYLOAD (o evento nao bate o contrato — `handler` deve LOGAR e
  DESCARTAR, publicando uma lacuna) versus falha de INFRAESTRUTURA (Valkey fora, timeout de rede — deve
  PROPAGAR: capturar largo aqui trocaria uma indisponibilidade TRANSITORIA por perda SILENCIOSA de evento).

  MEDIDO (nao suposto) contra este caminho especifico — `projecao/projetar` -> `canais/rotas-do-evento` +
  `projecao/dados-publicos`: ao contrario de transparencia/db/ (27 `{:pre ...}` + `UUID/fromString` cru,
  que lancam AssertionError/IllegalArgumentException/NullPointerException/ClassCastException),
  `tempo_real/projecao.clj` e `tempo_real/canais.clj` sao acesso de chave PURO (`(:sessao-id payload)` etc.
  devolve nil em vez de lancar p/ chave ausente) — o UNICO ponto de `throw` no caminho inteiro e' o gate de
  sigilo do voto secreto em `dados-publicos` p/ modalidade fora de {nominal,secreta}, e ele e' um `ex-info`
  com o marcador explicito `:tempo-real/payload-malformado?` (nunca casamento de MENSAGEM, que e' string de
  humano). Por isso o whitelist deste sitio NAO e' o de transparencia (nenhuma das 4 classes JVM daquele
  seam ocorre aqui) — e' so o marcador. Se o caminho crescer (novo `:pre`, nova conversao crua) e comecar a
  lancar uma classe nova, ela PROPAGA por default (fail-safe: o seguro e' propagar o que nao se reconhece,
  nao engolir)."
  [^Throwable t]
  (boolean (:tempo-real/payload-malformado? (ex-data t))))

(defn- canais-do-evento-tolerante
  "`canais/rotas-do-evento` p/ publicar a lacuna — mas o proprio evento que estamos descartando pode ser
  o que fez o roteamento falhar (nao ha caso medido hoje: `rotas-do-evento` e' acesso de chave puro; ainda
  assim, um roteamento QUE lanca no futuro nao pode fazer a publicacao-de-lacuna repetir o poison que ela
  existe p/ evitar). Devolve [] nesse caso — sem canal conhecido, nao ha onde por a lacuna; `handler` so
  loga."
  [evento]
  (try
    (canais/rotas-do-evento evento)
    (catch Throwable _ [])))

(defn- publicar-lacuna!
  "Publica o sinal `canais/tipo-lacuna` (JA no enum de saida, `canais/tipos-emitidos-ao-cliente` — nenhuma
  mudanca de contrato) no(s) canal(is) do evento descartado. `:dados {}` SEMPRE — nada do payload malformado
  atravessa esta fronteira (e' exatamente a garantia de sigilo que motivou o descarte no caso do voto).

  ASSIMETRIA COM `tempo_real/components.clj` `ler-desde` (deliberada, nao descuido): la' a seq do sinal e'
  CLAMPADA a' ultima seq confiavel, porque o `s` vem de uma entrada do STREAM escrita por um path que
  `mensagem-valida?` ja marcou como NAO confiavel (corrupcao/escrita externa) — um `s` forjado sequestraria
  o cursor do cliente. Aqui o produtor da lacuna e' o RELAY: interno, confiavel, o mesmo processo que produz
  toda mensagem legitima deste canal. `publicar!` (a impl Valkey via `INCR` atomico, ou a de memoria) e'
  quem ATRIBUI a seq — nunca le nem repete um `s` alheio — entao nao ha `s` para forjar e o clamp nao tem
  o que fazer aqui: a lacuna so pega a proxima seq monotonica do canal, como qualquer mensagem legitima."
  [canal-store evento]
  (let [canais (canais-do-evento-tolerante evento)]
    (if (seq canais)
      (doseq [c canais]
        (comp/publicar! canal-store c {:ente-id (:ente-id evento) :tipo canais/tipo-lacuna :dados {}}))
      (log/error "tempo-real: evento descartado sem canal para publicar a lacuna (roteamento tambem falhou)"
                 {:tipo (:tipo evento) :ente-id (:ente-id evento) :outbox-id (:id evento)}))))

(defn- handler
  "Handler do bus (fn [tx evento]): projeta o evento e publica cada mensagem na CanalStore. Ignora a `tx`
  (a CanalStore nao e' transacional com o Postgres do relay). Semantica AT-LEAST-ONCE: se a tx do relay
  reverter APOS o handler rodar (falha no UPDATE/commit), o evento e' redrenado e a mensagem reaparece com
  uma seq NOVA (duplicata por seq distinta) — G3 deve tolerar isso no resume por Last-Event-ID.

  GUARDA (frente 'relay-poison-tolerante', ver docstring do ns): `projecao/projetar` continua SEM
  tolerancia — e' o `case`/gate de fato, e o `throw` de sigilo dentro dele FICA intacto. Esta fn e' a
  fronteira: tenta projetar+publicar; se `payload-malformado?` reconhece a excecao, LOGA em :error (nunca
  :info — isto e' anomalia) com o bastante p/ achar a linha exata do outbox (`:outbox-id`, `:tipo`,
  `:ente-id`, `:idempotency-key`), publica a lacuna (`publicar-lacuna!`) e DESCARTA — o handler devolve
  normalmente, a tx do relay COMMITA, o proximo `drenar-um!` pega o proximo id (sem head-of-line). Qualquer
  OUTRA excecao (Valkey fora, timeout de rede) PROPAGA sem disfarce."
  [canal-store]
  (fn [_tx evento]
    (try
      (doseq [{:keys [canal] :as msg} (projecao/projetar evento)]
        (comp/publicar! canal-store canal (dissoc msg :canal)))
      (catch Throwable t
        (if (payload-malformado? t)
          (do (log/error t "tempo-real: evento descartado no relay compartilhado — payload malformado"
                         {:tipo (:tipo evento) :ente-id (:ente-id evento) :outbox-id (:id evento)
                          :idempotency-key (:idempotency-key evento) :razao (ex-message t)})
              (publicar-lacuna! canal-store evento))
          (throw t))))))

(defn registro
  "Constroi o registro de consumidores do bus (outbox) p/ o projetor SSE: o mesmo handler por tipo de evento
  consumido, todos publicando na `canal-store`. Passado ao relay em sistema.clj."
  [canal-store]
  (let [h (handler canal-store)]
    (reduce (fn [reg tipo] (outbox/registrar reg nome-consumidor tipo h))
            {} tipos-consumidos)))
