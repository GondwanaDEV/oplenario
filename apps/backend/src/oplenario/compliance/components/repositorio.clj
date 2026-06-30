(ns oplenario.compliance.components.repositorio
  "Component de PERSISTENCIA + RUNTIME do compliance (ADR-0001 §3) — o modulo que OPERA o seam do motor
  (`motor/avaliar`, F2) e PERSISTE o ciclo nas SUAS tabelas (schema compliance, §22.7.7). O protocolo
  RepoCompliance expoe as ACOES (tenant-aware: trata `com-tenant*` por dentro); o record segura o
  :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE Component, nunca do db/ direto.

  `avaliar-obrigacao!` recebe `registro` (RegistroFatos) + `repo-motor` POR CHAMADA — exatamente como o
  RepoLegislativo/transicionar! recebe `registro` p/ o engine de tramitacao (precedente F3.3a; o motor e'
  BIBLIOTECA compartilhada, §22.10). A reconciliacao do ciclo (motor->veredito; logic->fase persistida) +
  o ato + a auditoria correm na MESMA tx do tenant (atomicidade da prova de compliance, Invariante 10)."
  (:require [oplenario.compliance.components.fontes :as fontes]
            [oplenario.compliance.components.serializador-remessa :as ser]
            [oplenario.compliance.db.avaliacao :as db-aval]
            [oplenario.compliance.db.obrigacao :as db-obr]
            [oplenario.compliance.db.remessa :as db-rem]
            [oplenario.compliance.gerador-remessa :as ger]
            [oplenario.compliance.logic :as logic]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.kernel.ids :as ids]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.motor.api :as motor])
  (:import (java.security MessageDigest)
           (org.postgresql.util PSQLException)))

(defn- reconciliar-obrigacao!
  "Materializa/reconcilia a obrigacao DEADLINE-BOUND (quando o motor a devolveu) na `tx`, RACE-SAFE (C1).
  `veredito` vem do motor; a FASE persistida e' decidida por logic/proxima-fase contra o estado ATUAL
  (FOR UPDATE). Devolve a obrigacao persistida (mapa kebab via RETURNING, sem re-read) ou nil (sabor
  continuo/inaplicavel = sem obrigacao)."
  [tx ente-id template objeto-tipo objeto-id veredito agora obrig-motor]
  (when obrig-motor
    (let [conforme?   (= "conforme" veredito)
          venc?       (logic/vencido? (:vence-em obrig-motor) agora)
          vence-em    (:vence-em obrig-motor)
          prazo-fonte (:prazo-fonte-ref obrig-motor)
          atualizar   (fn [atual]
                        (let [fase      (logic/proxima-fase (:estado atual) conforme? venc?)
                              pendente? (= (:estado atual) "pendente")]  ; re-stamp do prazo so enquanto pendente (S3)
                          (db-obr/atualizar! tx {:id (:id atual) :ente-id ente-id :estado fase
                                                 :re-stamp? pendente? :vence-em vence-em :prazo-fonte-ref prazo-fonte
                                                 :marcar-cumprida? (and (= fase "cumprida")
                                                                        (not= (:estado atual) "cumprida"))})))]
      (if-let [atual (db-obr/buscar-para-reconciliar tx ente-id template objeto-tipo objeto-id)]
        (atualizar atual)
        ;; 1a materializacao: INSERT ON CONFLICT DO NOTHING. Ganhou a corrida -> devolve a linha nova; perdeu
        ;; (nil) -> a linha agora existe; re-le sob FOR UPDATE e reconcilia pelo ramo de update (C1).
        (let [fase (logic/proxima-fase "pendente" conforme? venc?)]
          (or (db-obr/inserir! tx {:id (ids/novo-id) :ente-id ente-id :template-chave template
                                   :objeto-tipo objeto-tipo :objeto-id objeto-id :vence-em vence-em
                                   :prazo-fonte-ref prazo-fonte :estado fase
                                   :cumprida-em (when (= fase "cumprida") [:now])})
              (atualizar (db-obr/buscar-para-reconciliar tx ente-id template objeto-tipo objeto-id))))))))

;; ---------- geracao de remessa (§22.7.8): coleta fontes -> renderiza (puro) -> serializa -> objeto_store -> insere ----------

(defn- sha256-hex
  "Hash hex SHA-256 do binario, prefixado 'sha256:' — integridade do artefato (metadata imutavel, §22.7.8).
  bit-and 0xff: byte assinado da JVM nao deve virar 'ffffff..' no hex."
  [^bytes b]
  (let [h (.digest (MessageDigest/getInstance "SHA-256") b)]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and (int %) 0xff)) h)))))

(defn- coletar-relacoes
  "Resolve as relacoes ESCALARES do cabecalho na `tx` do tenant (resolver-relacao consulta o PG com RLS —
  em prod fecha sobre o registry+tx, F5.5; aqui by-call como o motor). DENTRO da tx."
  [tx descritor resolver-relacao]
  (into {} (for [{[tipo chave] :fonte} (:cabecalho descritor) :when (= tipo :relacao)]
             [chave (resolver-relacao tx chave)])))

(defn- coletar-lotes
  "Resolve a secao de :registros via read-port EM LOTE (FontesRemessa) — FORA de qualquer tx PG (HTTP
  cross-modulo, §22.10/D7; nao segura conexao do pool, review clj m2). NUNCA JOIN cross-schema."
  [fontes-port ente-id descritor contexto]
  (when-let [sec (:registros descritor)]
    (let [[_ chave] (:fonte sec)]
      {chave (fontes/buscar-lote fontes-port ente-id chave contexto)})))

(def ^:private re-competencia #"^\d{4}-(0[1-9]|1[0-2])$")
(def ^:private re-template-chave #"^[a-zA-Z0-9_-]+$")

(defn- guard-chave-store!
  "Valida os segmentos do mapa `m` que compoem a chave do objeto_store (review sec m1): um `/` ou `..` em
  template-chave/competencia quebraria a estrutura do prefixo `remessas/<ente>/<template>/<competencia>/`
  (rastreabilidade/auditing). ente-id e' UUID da sessao (ancora o tenant); estes dois vem do mapa `m`."
  [template-chave competencia]
  (when-not (re-matches re-template-chave (str template-chave))
    (throw (ex-info "template-chave invalida p/ chave de remessa" {:template-chave template-chave})))
  (when-not (re-matches re-competencia (str competencia))
    (throw (ex-info "competencia invalida p/ chave de remessa" {:competencia competencia}))))

(defprotocol RepoCompliance
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  (avaliar-obrigacao! [this ente-id registro repo-motor m]
    "Opera motor/avaliar (fatos sob a tx do tenant) + reconcilia/persiste a obrigacao (logic/proxima-fase)
     + audita a avaliacao (append-only), tudo na MESMA tx. `m` = {:regra (envelope) :reg-ver :objeto-tipo
     :objeto-id :amb :agora (LocalDate) :origem (evento|sweep|sob_demanda) :feriados-jurisdicao?}. Devolve
     {:obrigacao <persistida|nil> :avaliacao {:id :veredito :obrigacao-id}}.")
  (varrer-vencimentos! [this ente-id hoje]
    "Sweep de vencimento (§22.7.7 S1): transiciona pendente->vencida as obrigacoes abertas cujo prazo
     passou em `hoje` (LocalDate) + audita cada (origem='sweep'). PURO por DATA — nao re-roda o motor (o
     vencimento e' a unica transicao que evento nao dispara; a obrigacao segue nao_conforme ate ser
     cumprida por evento). Idempotente (so move pendente; ja-vencida nao re-transiciona). Devolve
     [{:id :de :para}...] das obrigacoes transicionadas.")
  (painel [this ente-id opts]
    "Read-model do painel 'a Casa esta em dia com o TCE' (§16.11): compoe os tres reads tenant-wide numa
     UNICA tx do tenant (snapshot coerente) — resumo de obrigacoes por estado (placar; pares crus, o 0-fill
     e' do logic na borda) + obrigacoes em aberto (o que vence) + remessas recentes (pipeline). `opts` =
     {:limite-em-aberto :limite-remessas} (TETOS server-side, anti unbounded-read). Devolve
     {:resumo [...] :em-aberto [...] :remessas-recentes [...]}.")
  (buscar-obrigacao [this ente-id id])
  (obrigacoes-do-objeto [this ente-id objeto-tipo objeto-id])
  (avaliacoes-da-obrigacao [this ente-id obrigacao-id])
  (gerar-remessa! [this ente-id m]
    "Gera o ARTEFATO de remessa (§22.7.8): coleta as fontes do `descritor` -> renderiza (puro) -> serializa
     (port) -> hash -> grava o binario no objeto_store -> insere `remessa_gerada` rascunho VERSIONADO
     (versao MAX+1 atomica). Fail-closed (campo nao resolvido aborta ANTES de persistir). `m` = {:descritor
     :template-chave :sistema :competencia :contexto :resolver-relacao (fn [tx nome]) :fontes (FontesRemessa)
     :serializador (SerializadorRemessa) :objeto-store (ObjetoStore) :registry-versao-ref}. Devolve a linha.")
  (listar-remessas [this ente-id template-chave competencia]
    "Historico de (re)emissoes de (ente, template, competencia), por versao.")
  (validar-remessa! [this ente-id id] "Transiciona rascunho->validada (CAS guardado por grafo).")
  (submeter-remessa! [this ente-id id] "Transiciona validada->submetida + carimba submetida_em.")
  (registrar-resposta-remessa! [this ente-id id estado]
    "Registra a resposta do TCE: submetida->{aceita|rejeitada} + carimba resposta_em. So 'aceita' cumpre a
     obrigacao (costura remessa_enviada). `estado` != aceita|rejeitada -> LANCA (grafo do ciclo)."))

(defn- inserir-com-retry!
  "Insere a remessa versionada (versao MAX+1 ATOMICA) re-tentando UMA vez no 23505 — corrida de versao
  concorrente (carry TOCTOU F5.3a-1). Cada tentativa = tx propria (apos 23505 a tx aborta; a re-leitura
  do MAX em tx nova ja' enxerga a versao commitada). `id` e' fresco e nunca commitou no ramo perdido."
  [repo ente-id row-base]
  (letfn [(inserir [] (transacao repo ente-id #(db-rem/inserir-versionada! % row-base)))]
    (try (inserir)
         (catch PSQLException e
           (if (= "23505" (.getSQLState e)) (inserir) (throw e))))))

(defrecord RepoCompliancePg [datasource]
  RepoCompliance
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (avaliar-obrigacao! [this ente-id registro repo-motor
                       {:keys [regra reg-ver objeto-tipo objeto-id amb agora origem feriados-jurisdicao]}]
    (transacao this ente-id
      (fn [tx]
        (let [r          (motor/avaliar {:registro registro :repo-motor repo-motor :tx tx :ente-id ente-id
                                         :regra regra :reg-ver reg-ver :objeto-tipo objeto-tipo
                                         :objeto-id objeto-id :amb amb :agora agora
                                         :feriados-jurisdicao feriados-jurisdicao})
              aval-motor (:avaliacao r)
              veredito   (:veredito aval-motor)
              ;; o default de dominio da severidade mora AQUI (camada de aplicacao), nao no db/ (review clj M1).
              severidade (or (:severidade aval-motor) "aviso")
              template   (:template regra)
              ;; guardas de profundidade antes de tocar a prova append-only (review sec MEDIO-2/BAIXO-1):
              ;; um enum invalido nunca entra na prova imutavel de compliance — falha alto.
              _          (logic/validar-veredito veredito)
              _          (logic/validar-severidade severidade)
              _          (logic/validar-origem origem)
              obrig      (reconciliar-obrigacao! tx ente-id template objeto-tipo objeto-id
                                                 veredito agora (first (:obrigacoes r)))
              aval-id    (ids/novo-id)]
          (db-aval/registrar! tx {:id aval-id :ente-id ente-id :obrigacao-id (:id obrig)
                                  :template-chave template :registry-versao-ref reg-ver
                                  :veredito veredito :severidade severidade
                                  :origem-avaliacao origem :detalhe (:detalhe aval-motor)})
          {:obrigacao obrig :avaliacao {:id aval-id :veredito veredito :obrigacao-id (:id obrig)}}))))
  (varrer-vencimentos! [this ente-id hoje]
    (transacao this ente-id
      (fn [tx]
        ;; o SQL ja' devolve so as candidatas (pendente + estritamente overdue). Por candidata:
        ;;  (1) carrega a ultima avaliacao p/ a severidade+registry da regra ANTES de transicionar — se
        ;;      ausente (anomalia: obrigacao sem avaliacao, viola o invariante F5.1), PULA a obrigacao
        ;;      (isola a anomalia: nao bloqueia o sweep do ente nem cria transicao sem prova — review M1);
        ;;  (2) CAS `vencer-se-pendente!`: so audita se DE FATO transicionou (nil = corrida perdida p/ um
        ;;      cumprimento concorrente -> sem auditoria espuria; review CRITICO C1 / clj MAJOR-2).
        (->> (db-obr/pendentes-vencidas-ate tx ente-id hoje)
             (keep (fn [o]
                     (when-let [ult (db-aval/ultima-da-obrigacao tx ente-id (:id o))]
                       (when (db-obr/vencer-se-pendente! tx ente-id (:id o))
                         (db-aval/registrar! tx {:id (ids/novo-id) :ente-id ente-id :obrigacao-id (:id o)
                                                 :template-chave (:template-chave o)
                                                 :registry-versao-ref (:registry-versao-ref ult)
                                                 :veredito "nao_conforme" :severidade (or (:severidade ult) "aviso")
                                                 :origem-avaliacao "sweep" :detalhe "vencimento detectado por sweep"})
                         {:id (:id o) :de "pendente" :para "vencida"}))))   ; pendente->vencida: a unica transicao deste sweep
             vec))))
  (painel [this ente-id {:keys [limite-em-aberto limite-remessas] :or {limite-em-aberto 100 limite-remessas 50}}]
    (transacao this ente-id
      (fn [tx]
        {:resumo             (db-obr/resumo-por-estado tx ente-id)
         :em-aberto          (db-obr/listar-em-aberto tx ente-id limite-em-aberto)
         :remessas-recentes  (db-rem/listar-recentes tx ente-id limite-remessas)})))
  (buscar-obrigacao [this ente-id id] (transacao this ente-id #(db-obr/buscar % ente-id id)))
  (obrigacoes-do-objeto [this ente-id objeto-tipo objeto-id]
    (transacao this ente-id #(db-obr/listar-do-objeto % ente-id objeto-tipo objeto-id)))
  (avaliacoes-da-obrigacao [this ente-id obrigacao-id]
    (transacao this ente-id #(db-aval/listar-da-obrigacao % ente-id obrigacao-id)))
  (gerar-remessa! [this ente-id {:keys [descritor template-chave sistema competencia contexto
                                        resolver-relacao fontes serializador objeto-store registry-versao-ref]}]
    (when-not objeto-store                                            ; infra do kernel; nil = config quebrada (review clj m3)
      (throw (ex-info "gerar-remessa!: objeto-store ausente no mapa m" {:ente-id ente-id})))
    (guard-chave-store! template-chave competencia)
    (let [relacoes   (transacao this ente-id (fn [tx] (coletar-relacoes tx descritor resolver-relacao)))
          lotes      (coletar-lotes fontes ente-id descritor contexto)  ; FORA da tx (HTTP cross-modulo, m2)
          documento  (ger/renderizar descritor {:contexto contexto :relacoes relacoes :lotes lotes}) ; fail-closed aqui
          {b :bytes content-type :content-type} (ser/serializar serializador descritor documento)
          hash-conteudo (sha256-hex b)
          store-ref  (str "remessas/" ente-id "/" template-chave "/" competencia "/" hash-conteudo ".bin")
          ;; INSERT PRIMEIRO (review clj C1): a linha rascunho e' a ancora/prova. Se o S3 falhar depois, a
          ;; linha existe com ref resolvivel (detectavel/recuperavel) — nunca um binario orfao irrastreavel;
          ;; se o INSERT falhar, nenhum byte foi escrito no S3 (estado limpo). store-ref e' content-addressed
          ;; -> guardar! e' idempotente (re-emissao do mesmo conteudo reescreve bytes identicos).
          row        (inserir-com-retry! this ente-id
                       {:id (ids/novo-id) :ente-id ente-id :template-chave template-chave :sistema sistema
                        :competencia competencia :spec-layout-versao (:spec-layout-versao descritor)
                        :registry-versao-ref registry-versao-ref :hash hash-conteudo :objeto-store-ref store-ref})]
      (os/guardar! objeto-store store-ref b content-type)             ; binario no objeto_store apos a ancora
      row))
  (listar-remessas [this ente-id template-chave competencia]
    (transacao this ente-id #(db-rem/listar % ente-id template-chave competencia)))
  (validar-remessa! [this ente-id id]
    (transacao this ente-id #(db-rem/transicionar-estado! % ente-id id "rascunho" "validada" {})))
  (submeter-remessa! [this ente-id id]
    (transacao this ente-id #(db-rem/transicionar-estado! % ente-id id "validada" "submetida" {:submetida-em [:now]})))
  (registrar-resposta-remessa! [this ente-id id estado]
    (transacao this ente-id #(db-rem/transicionar-estado! % ente-id id "submetida" estado {:resposta-em [:now]}))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoCompliancePg nil))
