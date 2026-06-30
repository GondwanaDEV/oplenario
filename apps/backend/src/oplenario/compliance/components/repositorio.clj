(ns oplenario.compliance.components.repositorio
  "Component de PERSISTENCIA + RUNTIME do compliance (ADR-0001 §3) — o modulo que OPERA o seam do motor
  (`motor/avaliar`, F2) e PERSISTE o ciclo nas SUAS tabelas (schema compliance, §22.7.7). O protocolo
  RepoCompliance expoe as ACOES (tenant-aware: trata `com-tenant*` por dentro); o record segura o
  :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE Component, nunca do db/ direto.

  `avaliar-obrigacao!` recebe `registro` (RegistroFatos) + `repo-motor` POR CHAMADA — exatamente como o
  RepoLegislativo/transicionar! recebe `registro` p/ o engine de tramitacao (precedente F3.3a; o motor e'
  BIBLIOTECA compartilhada, §22.10). A reconciliacao do ciclo (motor->veredito; logic->fase persistida) +
  o ato + a auditoria correm na MESMA tx do tenant (atomicidade da prova de compliance, Invariante 10)."
  (:require [oplenario.compliance.db.avaliacao :as db-aval]
            [oplenario.compliance.db.obrigacao :as db-obr]
            [oplenario.compliance.logic :as logic]
            [oplenario.kernel.ids :as ids]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.motor.api :as motor]))

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
  (buscar-obrigacao [this ente-id id])
  (obrigacoes-do-objeto [this ente-id objeto-tipo objeto-id])
  (avaliacoes-da-obrigacao [this ente-id obrigacao-id]))

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
  (buscar-obrigacao [this ente-id id] (transacao this ente-id #(db-obr/buscar % ente-id id)))
  (obrigacoes-do-objeto [this ente-id objeto-tipo objeto-id]
    (transacao this ente-id #(db-obr/listar-do-objeto % ente-id objeto-tipo objeto-id)))
  (avaliacoes-da-obrigacao [this ente-id obrigacao-id]
    (transacao this ente-id #(db-aval/listar-da-obrigacao % ente-id obrigacao-id))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoCompliancePg nil))
