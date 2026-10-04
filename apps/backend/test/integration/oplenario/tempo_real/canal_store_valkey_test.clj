(ns oplenario.tempo-real.canal-store-valkey-test
  "§22.6 eixo G (G3b) — a CanalStore VIVA em Valkey (Carmine streams), atras do MESMO protocolo da impl em
  memoria (G2). Contrato observavel IDENTICO ao `canal-store-memoria` (seq monotonica POR canal + replay via
  Last-Event-ID) + retencao por TEMPO (janela de 5 min). Integracao real: exige o Valkey de pe (compose). O
  endpoint SSE (diplomat) nao muda — so a impl do protocolo. Modela as assercoes de contrato do projetor_test."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [clojure.string :as str]
            [oplenario.config :as config]
            [oplenario.tempo-real.components :as trc]
            [taoensso.carmine :as car]))

(def ^:dynamic *store* nil)

(defn- store-vivo
  "Sobe um CanalStoreValkey (start abre o pool) e o derruba no fim. `opts` injeta clock/retencao p/ os testes
  deterministicos de poda."
  ([t] (store-vivo {} t))
  ([opts t]
   (let [s (component/start (trc/canal-store-valkey (config/carregar) opts))]
     (binding [*store* s] (try (t) (finally (component/stop s)))))))

(use-fixtures :each (fn [t] (store-vivo t)))

(defn- canal [] (str "test/" (random-uuid) "/plenario"))   ; canal unico por teste (Valkey compartilhado)

(defn- conn-cru
  "Conexao direta ao Valkey do teste (fora do store), com a MESMA spec do store — inclusive a senha, quando o
  Valkey do teste tem `requirepass`. E' por ela que os testes escrevem no stream o que um terceiro escreveria."
  []
  {:pool :none :spec (trc/spec-de-conexao (config/carregar))})

;; ---------- contrato: seq monotonica POR canal + replay (espelha o projetor_test da impl em memoria) ----------

(deftest valkey-seq-e-replay
  (let [c (canal) d (canal)]
    (is (= {:canal c :seq 1} (trc/publicar! *store* c {:tipo "a" :dados {}})) "1a mensagem do canal = seq 1")
    (is (= {:canal c :seq 2} (trc/publicar! *store* c {:tipo "b" :dados {}})) "seq monotonica por canal")
    (is (= {:canal d :seq 1} (trc/publicar! *store* d {:tipo "x" :dados {}})) "seq e' POR canal (d comeca em 1)")
    (is (= ["b"] (mapv :tipo (trc/ler-desde *store* c 1))) "replay desde Last-Event-ID seq=1 -> so a posterior")
    (is (= ["a" "b"] (mapv :tipo (trc/ler-desde *store* c 0))) "ler-desde 0 = tudo, em ordem de seq")))

;; ---------- round-trip fiel da mensagem (texto EDN de dado puro; preserva keyword keys e uuid) ----------

(deftest valkey-round-trip-mensagem
  (let [c    (canal)
        ente (random-uuid)]
    (trc/publicar! *store* c {:tipo "sessao.transicionou" :ente-id ente :dados {:sessao-id "S" :para "aberta"}})
    (let [m (first (trc/ler-desde *store* c 0))]
      (is (= "sessao.transicionou" (:tipo m)) "tipo (string) preservado")
      (is (= ente (:ente-id m)) "ente-id (defesa-em-profundidade) preservado")
      (is (= {:sessao-id "S" :para "aberta"} (:dados m)) "dados (map de keyword keys) round-trip fiel")
      (is (= 1 (:seq m)) "seq embutida na mensagem"))))

(deftest valkey-round-trip-dos-tipos-que-trafegam
  ;; o payload vem do jsonb do outbox: texto (com aspas, quebra de linha e acento), numero, booleano, nulo, lista e
  ;; mapa aninhado; o ente-id e' uuid. Tudo volta IGUAL.
  (let [c   (canal)
        msg {:tipo    "voto.registrado"
             :ente-id (random-uuid)
             :dados   {:texto "Sr. \"Presidente\"\nquestão de ordem" :n 12 :fracao 0.5 :ok true :nada nil
                       :lista [1 "dois" {:tres 3}] :aninhado {:a {:b {:c "d"}}}}}]
    (trc/publicar! *store* c msg)
    (is (= (assoc msg :seq 1) (first (trc/ler-desde *store* c 0))))))

(deftest valkey-o-que-fica-gravado-e-texto-nao-nippy
  ;; o valor no stream e' texto legivel com o prefixo de versao — nao um blob congelado
  (let [c (canal)]
    (trc/publicar! *store* c {:tipo "a" :dados {:x 1}})
    (let [[[_id campos]] (car/wcar (conn-cru) (car/parse-raw (car/xrange (str "tr:canal:" c) "-" "+")))
          m              (->> (partition 2 campos)
                              (some (fn [[k v]] (when (= "m" (String. ^bytes k "UTF-8")) (String. ^bytes v "UTF-8")))))]
      (is (= "edn1:{:tipo \"a\", :dados {:x 1}}" m)))))

(deftest valkey-mensagem-que-nao-e-dado-puro-nao-e-gravada
  ;; fail-closed na ESCRITA: objeto fora da allowlist nao vai para o Valkey; a excecao leva o marcador que o
  ;; consumidor do relay reconhece (descarta o evento com lacuna em vez de travar o barramento).
  (let [c (canal)
        e (try (trc/publicar! *store* c {:tipo "a" :dados {:quando (java.util.Date.)}}) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (true? (:tempo-real/payload-malformado? (ex-data e))))
    (is (= [] (trc/ler-desde *store* c 0)) "nada foi gravado")))

;; ---------- isolamento entre canais ----------

(deftest valkey-canais-isolados
  (let [c (canal) d (canal)]
    (trc/publicar! *store* c {:tipo "a" :dados {}})
    (trc/publicar! *store* c {:tipo "b" :dados {}})
    (trc/publicar! *store* d {:tipo "z" :dados {}})
    (is (= ["a" "b"] (mapv :tipo (trc/ler-desde *store* c 0))) "canal c isolado")
    (is (= ["z"] (mapv :tipo (trc/ler-desde *store* d 0))) "canal d isolado")))

;; ---------- retencao por TEMPO — janela curta + sleep real (exerce o MESMO caminho de producao) ----------

(deftest valkey-retencao-poda-por-tempo
  ;; janela de 100 ms; "velha" publicada, sleep 250 ms, "nova" publicada -> MINID(agora-100ms) cai DEPOIS do id
  ;; da velha -> Valkey a poda. Margem (250 vs 100) folgada o bastante p/ nao flakar.
  (store-vivo
   {:retencao-ms 100}
   (fn []
     (let [c (canal)]
       (trc/publicar! *store* c {:tipo "velha" :dados {}})
       (Thread/sleep 250)
       (trc/publicar! *store* c {:tipo "nova" :dados {}})
       (is (= ["nova"] (mapv :tipo (trc/ler-desde *store* c 0)))
           "entrada fora da janela e' podada por tempo (MINID); so a recente sobrevive ao replay")))))

;; ---------- Lifecycle: start abre / stop fecha / start idempotente ----------

(deftest valkey-lifecycle-idempotente
  (let [s  (trc/canal-store-valkey (config/carregar))
        s1 (component/start s)
        s2 (component/start s1)                             ; 2o start nao reabre (idempotente)
        k  (canal)]                                         ; canal unico (contador nao herda de run anterior)
    (try
      (is (= {:canal k :seq 1} (trc/publicar! s2 k {:tipo "ok" :dados {}})) "store de pe publica")
      (finally (component/stop s2)))))

;; ---------- guarda nil-conn: usar o store sem start LANCA com contexto (review clj-MAJOR / sec-MINOR-3) ----------

(deftest valkey-uso-sem-start-lanca
  (let [s (trc/canal-store-valkey (config/carregar))]       ; construido, NAO iniciado (conn-atom nil)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao iniciado"
          (trc/publicar! s (canal) {:tipo "x" :dados {}}))
        "publicar! sem start lanca ex-info clara (nao NPE do Carmine)")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"nao iniciado"
          (trc/ler-desde s (canal) 0))
        "ler-desde sem start lanca ex-info clara")))

;; ---------- defesa-em-profundidade: entrada de forma estranha vira SINAL, nao e' engolida (sec-HIGH-1 +
;; frente 'truncamento-familia' sitio (d): o descarte silencioso deixava o cliente avancar o cursor por
;; cima do buraco como se o replay estivesse integro — sem sinal nem no servidor nem no cliente) ----------
;;
;; >>> revisao adversarial (2a rodada, 2 achados CRITICO/IMPORTANTE independentes provados AO VIVO contra
;; o Valkey da stack) <<< O 1o conserto do sitio (d) reanexava ao sinal a seq LIDA da propria entrada
;; corrompida (`"s"` no XADD cru) — exatamente o campo que um escritor NAO confiavel controla. Isso abria
;; DOIS jeitos de derrubar o replay inteiro, nao so' de perder uma entrada:
;;  1. CURSOR SEQUESTRADO: um XADD com `s` MAIOR que qualquer seq legitima (ex. 9999999) virava o
;;     Last-Event-ID devolvido ao cliente — o proximo replay pula por cima de TODO evento real que vier
;;     depois (o teste `valkey-mensagem-corrompida-nao-sequestra-cursor` abaixo reproduz).
;;  2. LANCE EM VEZ DE SINALIZAR: `parse-long` corria ANTES da checagem `mensagem-valida?`, para TODA
;;     entrada — `(parse-long nil)` lanca `IllegalArgumentException` quando o campo `s` nem existe (a forma
;;     mais natural de uma escrita externa), o que sobe ate' `diplomat/sse.clj` e FECHA a conexao SSE (o
;;     oposto exato do objetivo da fatia). O teste `valkey-mensagem-sem-campo-s-vira-sinal-sem-lancar`
;;     abaixo reproduz.
;; O conserto: a seq do SINAL nunca vem do campo `s` de uma entrada que reprovou `mensagem-valida?` — e'
;; CLAMPADA a' maior seq de uma entrada de fato validada nesta mesma leitura (nunca ultrapassa uma mensagem
;; legitima, mesmo que ainda nao tenha sido lida). `parse-long` so' roda quando `s-cru` e' string (nunca
;; lanca em campo ausente/nil).

(deftest valkey-mensagem-corrompida-vira-sinal-de-lacuna-sem-perder-o-cursor
  (let [c    (canal)
        conn (conn-cru)]
    (trc/publicar! *store* c {:tipo "boa" :dados {}})        ; entrada valida (seq 1)
    ;; injeta uma entrada CRUA com "m" nao-map (corrupcao / escrita externa) direto no stream do canal
    (car/wcar conn (car/xadd (str "tr:canal:" c) "*" "m" "isto-nao-e-um-map" "s" "999"))
    (let [msgs (trc/ler-desde *store* c 0)]
      (is (= ["boa" "tempo-real.lacuna"] (mapv :tipo msgs))
          "a entrada corrompida NAO e' descartada — vira um sinal de lacuna que o cliente recebe pelo
           MESMO canal, na ordem de seq")
      (is (= 1 (:seq (last msgs)))
          "a seq do sinal NAO e' o \"999\" que a propria entrada corrompida afirma sobre si — e' CLAMPADA
           a' ultima seq VALIDADA (1, de \"boa\"): um escritor externo nao escolhe o cursor do cliente")
      (is (= {} (:dados (last msgs))) "o sinal nunca carrega o payload corrompido"))))

(deftest valkey-mensagem-corrompida-nao-sequestra-cursor
  ;; sitio CRITICO da revisao adversarial: boa(seq1) -> corrompida com s=9999999 -> boa(seq2) DEPOIS dela.
  ;; Se o sinal promovesse "9999999" a seq, o cursor do cliente pularia por cima de "depois" (seq2) para
  ;; sempre — o proximo replay (Last-Event-ID=9999999) nunca mais devolveria nada desta sessao.
  (let [c    (canal)
        conn (conn-cru)]
    (trc/publicar! *store* c {:tipo "boa" :dados {}})                                   ; seq 1
    (car/wcar conn (car/xadd (str "tr:canal:" c) "*" "m" "isto-nao-e-um-map" "s" "9999999"))
    (trc/publicar! *store* c {:tipo "depois" :dados {}})                                ; seq 2 (real)
    (let [msgs (trc/ler-desde *store* c 0)]
      (is (= ["boa" "tempo-real.lacuna" "depois"] (mapv :tipo msgs))
          "a mensagem legitima publicada DEPOIS da corrompida chega — o cursor nao pulou por cima dela")
      (is (= 2 (:seq (last msgs))) "\"depois\" mantem sua seq REAL (2), nao e' sequestrada por 9999999")
      (is (every? #(<= (:seq %) 2) msgs)
          "nenhuma seq devolvida ultrapassa a maior seq VALIDA desta leitura — o \"9999999\" nunca escapa
           do XADD cru para o cursor do cliente")
      ;; replay desde o cursor real (2): a proxima reconexao nao deve reencontrar nem o sinal nem "boa".
      (is (= [] (mapv :tipo (trc/ler-desde *store* c 2)))
          "reconectando com Last-Event-ID=2 (o cursor real apos esta leitura) nao ha nada pendente —
           prova que o sinal nao inflou o cursor para alem do que de fato foi visto"))))

(deftest valkey-mensagem-sem-campo-s-vira-sinal-sem-lancar
  ;; sitio IMPORTANTE da revisao adversarial: a forma mais natural de uma escrita externa/corrompida nem
  ;; carrega o campo "s" — antes do conserto, `(parse-long nil)` lancava IllegalArgumentException e
  ;; `ler-desde` MORRIA (o replay inteiro cai, nao so' uma entrada; e' o `catch Throwable` de
  ;; diplomat/sse.clj que fecha o event-channel).
  (let [c    (canal)
        conn (conn-cru)]
    (trc/publicar! *store* c {:tipo "boa" :dados {}})
    (car/wcar conn (car/xadd (str "tr:canal:" c) "*" "m" "isto-nao-e-um-map"))   ; SEM campo "s"
    (let [msgs (trc/ler-desde *store* c 0)]
      (is (= ["boa" "tempo-real.lacuna"] (mapv :tipo msgs))
          "ler-desde NAO lanca — a entrada sem \"s\" vira sinal de lacuna, como qualquer outra forma
           invalida")
      (is (= 1 (:seq (last msgs))) "sem \"s\" pra clampar contra nada de maior, cai na ultima seq valida"))))

(deftest valkey-mensagem-com-s-nao-numerico-vira-sinal-sem-lancar
  ;; variante do mesmo sitio: "s" existe mas nao e' numero (ex. campo forjado a mao) — `parse-long` devolve
  ;; nil (nao lanca) so' quando o valor JA e' string; o guard `(string? s-cru)` cobre os dois casos.
  (let [c    (canal)
        conn (conn-cru)]
    (trc/publicar! *store* c {:tipo "boa" :dados {}})
    (car/wcar conn (car/xadd (str "tr:canal:" c) "*" "m" "isto-nao-e-um-map" "s" "abc"))
    (let [msgs (trc/ler-desde *store* c 0)]
      (is (= ["boa" "tempo-real.lacuna"] (mapv :tipo msgs)) "\"s\" nao-numerico tambem vira sinal, sem lancar")
      (is (= 1 (:seq (last msgs))) "clampado a' ultima seq valida (1)"))))

;; ---------- o que sai do Valkey NUNCA vira objeto arbitrario (fecha o carry HIGH-1 do G3b) ----------
;;
;; Antes: o Carmine descongelava (Nippy) sozinho todo valor com o marcador dele — dentro do XRANGE, antes de
;; qualquer validacao nossa. Quem escrevesse no Valkey escolhia a CLASSE que o backend instanciava e a funcao de
;; leitor etiquetado que ele rodava. Os testes abaixo escrevem no stream o que um invasor escreveria (um valor
;; congelado por Nippy) e provam que nada disso atravessa: vira lacuna, como qualquer lixo.

(defrecord ForaDaAllowlist [tipo dados])

(deftest valkey-classe-fora-da-allowlist-e-recusada
  ;; um record com os campos certos PASSAVA em `mensagem-valida?` (record e' map, :tipo string, :dados map) e saia
  ;; de `ler-desde` como instancia de uma classe escolhida por quem escreveu.
  (let [c    (canal)
        conn (conn-cru)]
    (trc/publicar! *store* c {:tipo "boa" :dados {}})
    (car/wcar conn (car/xadd (str "tr:canal:" c) "*"
                             "m" (->ForaDaAllowlist "voto.registrado" {:forjado true}) "s" "2"))
    (let [msgs (trc/ler-desde *store* c 0)]
      (is (= ["boa" "tempo-real.lacuna"] (mapv :tipo msgs))
          "o valor congelado por Nippy e' recusado e vira lacuna — nunca chega como mensagem")
      (is (not-any? #(instance? ForaDaAllowlist %) msgs)
          "nenhum objeto da classe escolhida por quem escreveu no Valkey e' instanciado na leitura")
      (is (not-any? #(:forjado (:dados %)) msgs) "nada do conteudo forjado atravessa"))))

(deftest valkey-leitor-etiquetado-nao-executa
  ;; o Nippy le o tipo `reader` com os `*data-readers*` do processo: uma etiqueta registrada (qualquer
  ;; `data_readers.clj` do classpath) roda a funcao dela com o dado de quem escreveu. Aqui a funcao so' marca
  ;; que rodou.
  (let [c        (canal)
        conn     (conn-cru)
        executou (atom nil)]
    (trc/publicar! *store* c {:tipo "boa" :dados {}})
    ;; o Nippy so' congela como `reader` o que ele mesmo consegue reler: na ESCRITA a etiqueta tem um leitor inocente
    (binding [*data-readers* {'oplenario/explode (fn [v] (tagged-literal 'oplenario/explode v))}]
      (car/wcar conn (car/xadd (str "tr:canal:" c) "*" "m" (tagged-literal 'oplenario/explode "carga") "s" "2")))
    (let [msgs (binding [*data-readers* {'oplenario/explode (fn [v] (reset! executou v) {:tipo "forjada" :dados {}})}]
                 (trc/ler-desde *store* c 0))]
      (is (nil? @executou) "a funcao do leitor etiquetado NAO roda com dado vindo do Valkey")
      (is (= ["boa" "tempo-real.lacuna"] (mapv :tipo msgs)) "a entrada vira lacuna"))))

(deftest valkey-mensagem-legitima-em-nippy-tambem-e-recusada
  ;; o formato antigo (mapa congelado por Nippy pelo Carmine) nao e' mais lido — nem o legitimo: aceitar Nippy e'
  ;; exatamente a porta que se fechou. Na virada de versao, o que restar no stream (janela de 5 min) vira lacuna
  ;; e o painel se recupera pelo snapshot.
  (let [c (canal)]
    (car/wcar (conn-cru) (car/xadd (str "tr:canal:" c) "*" "m" {:tipo "sessao.transicionou" :dados {}} "s" "1"))
    (is (= ["tempo-real.lacuna"] (mapv :tipo (trc/ler-desde *store* c -1))))))

(deftest valkey-entrada-recusada-nao-derruba-as-outras
  (let [c (canal)]
    (trc/publicar! *store* c {:tipo "antes" :dados {}})
    (car/wcar (conn-cru) (car/xadd (str "tr:canal:" c) "*" "m" (->ForaDaAllowlist "x" {}) "s" "2"))
    (car/wcar (conn-cru) (car/xadd (str "tr:canal:" c) "*" "m" "edn1:#=(java.lang.Runtime/getRuntime)" "s" "2"))
    (car/wcar (conn-cru) (car/xadd (str "tr:canal:" c) "*" "m" "edn1:#java.util.Date \"x\"" "s" "2"))
    (trc/publicar! *store* c {:tipo "depois" :dados {}})
    (is (= ["antes" "tempo-real.lacuna" "tempo-real.lacuna" "tempo-real.lacuna" "depois"]
           (mapv :tipo (trc/ler-desde *store* c 0)))
        "cada entrada recusada vira a sua lacuna; as legitimas, antes e depois, chegam")))

;; ---------- autenticacao: o cliente manda a senha da config ----------
;; Um usuario de ACL com senha, criado so' para o teste, vale tanto num Valkey sem `requirepass` (CI) quanto num com.

(defn- com-usuario-de-teste [f]
  (let [usuario (str "teste-" (random-uuid))
        senha   (str (random-uuid))]
    (car/wcar (conn-cru) (car/redis-call ["ACL" "SETUSER" usuario "on" (str ">" senha) "~tr:*" "+@all"]))
    (try (f usuario senha)
         (finally (car/wcar (conn-cru) (car/redis-call ["ACL" "DELUSER" usuario]))))))

(deftest valkey-autentica-com-usuario-e-senha-da-config
  (com-usuario-de-teste
   (fn [usuario senha]
     (let [uri-sem-credencial (str/replace (get-in (config/carregar) [:valkey :uri]) #"//[^@/]*@" "//")
           config             (assoc (config/carregar) :valkey {:uri uri-sem-credencial :username usuario :password senha})
           s                  (component/start (trc/canal-store-valkey config))
           c                  (canal)]
       (try
         (is (= {:canal c :seq 1} (trc/publicar! s c {:tipo "ok" :dados {}})) "autenticado, publica")
         (is (= ["ok"] (mapv :tipo (trc/ler-desde s c 0))))
         (finally (component/stop s)))
       (is (thrown? Exception
                    (component/start (trc/canal-store-valkey (assoc-in config [:valkey :password] "senha-errada"))))
           "senha errada: o start falha (PING fail-fast) — o boot nao segue com um store que nao autentica")))))
