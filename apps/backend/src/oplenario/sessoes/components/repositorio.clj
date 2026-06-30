(ns oplenario.sessoes.components.repositorio
  "Component de PERSISTENCIA do modulo SESSOES — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoSessoes expoe as ACOES (tenant-aware: trata `com-tenant*` por dentro); o
  record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE Component, nunca do
  db/ direto. (Eventos de dominio Sessao*/real-time = eixos posteriores do F4.)"
  (:require [oplenario.kernel.tenancy :as tenancy]
            [oplenario.sessoes.diplomat.producers :as producers]
            [oplenario.sessoes.db.gravacao :as gravacao]
            [oplenario.sessoes.db.incidente :as incidente]
            [oplenario.sessoes.db.pauta :as pauta]
            [oplenario.sessoes.db.presenca :as presenca]
            [oplenario.sessoes.db.sessao :as sessao]
            [oplenario.sessoes.db.tribuna :as tribuna]
            [oplenario.sessoes.relacoes.presenca :as rel-presenca]))

(defprotocol RepoSessoes
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  ;; §22.6 eixo A — sessao
  (agendar-sessao! [this ente-id m] "Numera gapless + resolve capabilities + insere 'agendada', atomico.")
  (transicionar-sessao! [this ente-id m] "Move o estado pela maquina (fail-closed) com CAS.")
  (buscar-sessao [this ente-id id])
  (sessoes-da-legislativa [this ente-id sessao-legislativa-id])
  ;; §22.6 eixo B — pauta (camada viva)
  (criar-pauta! [this ente-id m] "Cria a pauta 1:1 da sessao.")
  (buscar-pauta-por-sessao [this ente-id sessao-id])
  (adicionar-item-na-sessao! [this ente-id m] "Get-or-create do container 1:1 da sessao + insere item, atomico (uma tx).")
  (adicionar-item! [this ente-id m] "Insere item (ordem=max+1) + LOGA inclusao, atomico.")
  (reordenar-item! [this ente-id m] "Move item p/ nova ordem + LOGA inversao, atomico.")
  (remover-item! [this ente-id m] "Remocao soft (ativo=false) + LOG, atomico (nunca DELETE).")
  (buscar-item [this ente-id id])
  (listar-itens [this ente-id pauta-sessao-id] "Itens ATIVOS em ordem.")
  (listar-alteracoes [this ente-id pauta-sessao-id])
  ;; §22.6 eixo B — versionamento canonico (snapshots append-only)
  (publicar-versao! [this ente-id m] "Congela a pauta num snapshot canonico (numera local), append-only.")
  (buscar-versao [this ente-id id])
  (listar-versoes [this ente-id pauta-sessao-id])
  (versao-publica-corrente [this ente-id pauta-sessao-id] "Maior numero_versao com publica=true.")
  ;; §22.6 eixo C — presenca e quorum (camada de fatos)
  (registrar-presenca! [this ente-id m] "Grava evento de presenca append-only (entrada/saida/retorno/mudanca).")
  (listar-presenca [this ente-id sessao-id] "Eventos da sessao em ordem cronologica (auditoria).")
  (esta-presente? [this ente-id sessao-id vereador-id instante] "Presenca DERIVADA do ultimo evento ate o instante.")
  (presentes-plenario [this ente-id sessao-id instante] "Quorum presencial em `instante` (insumo da DSL do motor).")
  (presentes-remoto [this ente-id sessao-id instante] "Quorum remoto em `instante`.")
  (criar-justificativa! [this ente-id m] "Abre justificativa de ausencia 'pendente' (ato apartado).")
  (buscar-justificativa [this ente-id id])
  (decidir-justificativa! [this ente-id m] "aprovada|indeferida (terminal) via maquina + CAS.")
  ;; §22.6 eixo D — gravacao (audio/video)
  (registrar-segmento! [this ente-id m] "Grava segmento de gravacao (captura/ingestao); sessao_id opcional (Opcao A).")
  (vincular-segmento! [this ente-id m] "Vincula um segmento a sessao (uma-vez, CAS). `forcar-acesso-restrito` (sigilo §22.6) eleva acesso_restrito; emite gravacao.segmento-vinculado (core->IA) com o sigilo definitivo, atomico.")
  (buscar-segmento [this ente-id id])
  (listar-segmentos-da-sessao [this ente-id sessao-id] "Segmentos da sessao em ordem cronologica (read-model).")
  ;; §22.6 eixo F — tribuna: inscricao de oradores (intencao)
  (inscrever! [this ente-id m] "Inscreve um orador (intencao); numera a fila por (sessao, fase). Devolve {:id :ordem}.")
  (buscar-inscricao [this ente-id id])
  (listar-inscricoes [this ente-id sessao-id] "Fila de oradores da sessao (por fase + ordem).")
  (desistir! [this ente-id m] "Move a inscricao para 'desistencia' (terminal) via maquina + CAS.")
  ;; §22.6 eixo F — tribuna: fala executada + cronometro (execucao)
  (iniciar-fala! [this ente-id m] "Inicia a fala + loga 'iniciada', atomico. Devolve {:id}.")
  (registrar-evento-cronometro! [this ente-id m] "Evento manual do cronometro (pausada/retomada/aparte/tempo-adicional).")
  (encerrar-fala! [this ente-id m] "Encerra a fala, COMPUTA o tempo dos eventos + loga 'encerrada' (uma vez, CAS).")
  (buscar-fala [this ente-id id])
  (listar-falas-da-sessao [this ente-id sessao-id] "Falas da sessao em ordem cronologica (read-model + diarizacao).")
  (listar-apartes [this ente-id fala-pai-id] "Apartes de uma fala-mae.")
  (listar-eventos-cronometro [this ente-id fala-id] "Eventos do cronometro da fala (a projecao le daqui).")
  ;; §22.6 eixo F — tribuna: decisao da mesa (questao de ordem)
  (registrar-decisao-mesa! [this ente-id m] "Registra a decisao do presidente sobre questao de ordem (ato p/ ata, append-only).")
  (buscar-decisao-mesa [this ente-id id])
  (listar-decisoes-mesa [this ente-id sessao-id] "Decisoes da mesa da sessao em ordem cronologica (ata).")
  ;; §16.13 — incidentes processuais (mesa de conducao ao vivo)
  (registrar-incidente! [this ente-id m] "Registra incidente processual (append-only) + emite incidente.registrado (SSE) na MESMA tx.")
  (buscar-incidente [this ente-id id])
  (listar-incidentes [this ente-id sessao-id] "Incidentes da sessao em ordem cronologica (ata + painel da mesa)."))

(defrecord RepoSessoesPg [datasource bus]
  RepoSessoes
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  (agendar-sessao! [this ente-id m] (transacao this ente-id #(sessao/agendar! % (assoc m :ente-id ente-id))))
  ;; §22.6 eixo G — compoe o ato + a emissao do evento de tempo real na MESMA tx (atomicidade §22.9 E2).
  (transicionar-sessao! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (sessao/transicionar! tx (assoc m :ente-id ente-id))]
          ;; so emite numa MUDANCA real de estado (de != para) — guard explicito contra evento espurio
          ;; (a maquina hoje lanca em transicao invalida/redundante, mas o contrato fica explicito aqui).
          (when (not= (:de r) (:para r))
            (producers/emitir-sessao-transicionou! bus tx ente-id
              (cond-> {:sessao-id (:id m) :de (:de r) :para (:para r)}
                (:updated-by m) (assoc :ator-id (:updated-by m)))))
          r))))
  (buscar-sessao [this ente-id id] (transacao this ente-id #(sessao/buscar % ente-id id)))
  (sessoes-da-legislativa [this ente-id slid] (transacao this ente-id #(sessao/listar-por-sessao-legislativa % ente-id slid)))
  (criar-pauta! [this ente-id m] (transacao this ente-id #(pauta/criar-pauta! % (assoc m :ente-id ente-id))))
  (buscar-pauta-por-sessao [this ente-id sessao-id] (transacao this ente-id #(pauta/buscar-pauta-por-sessao % ente-id sessao-id)))
  ;; get-or-create do container 1:1 + insere o item na MESMA tx (a pauta e' transparente: a borda adiciona item
  ;; a sessao, nao a um container que o cliente cria a parte). `sessao-id` resolve/cria a pauta; o resto de `m`
  ;; (id, fase, tipo-item, proposicao-id/texto-descricao, created-by) vai p/ adicionar-item!.
  (adicionar-item-na-sessao! [this ente-id {:keys [sessao-id created-by] :as m}]
    (transacao this ente-id
      (fn [tx]
        (let [pauta (pauta/garantir-pauta! tx {:ente-id ente-id :sessao-id sessao-id :created-by created-by})]
          ;; sob READ COMMITTED garantir-pauta! sempre resolve (insere ou re-le o vencedor); o guard cobre um
          ;; futuro REPEATABLE READ/SERIALIZABLE, onde o re-read poderia nao ver o commit concorrente -> nil ->
          ;; pauta-sessao-id nil -> NOT NULL nao-controlado (500). Falha controlada em vez disso (review clj).
          (when-not pauta
            (throw (ex-info "garantir-pauta!: container nao resolvivel" {:tipo :servidor/erro :sessao-id sessao-id})))
          (pauta/adicionar-item! tx (-> m (dissoc :sessao-id)
                                        (assoc :ente-id ente-id :pauta-sessao-id (:id pauta))))))))
  (adicionar-item! [this ente-id m] (transacao this ente-id #(pauta/adicionar-item! % (assoc m :ente-id ente-id))))
  (reordenar-item! [this ente-id m] (transacao this ente-id #(pauta/reordenar-item! % (assoc m :ente-id ente-id))))
  (remover-item! [this ente-id m] (transacao this ente-id #(pauta/remover-item! % (assoc m :ente-id ente-id))))
  (buscar-item [this ente-id id] (transacao this ente-id #(pauta/buscar-item % ente-id id)))
  (listar-itens [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-itens % ente-id pauta-sessao-id)))
  (listar-alteracoes [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-alteracoes % ente-id pauta-sessao-id)))
  (publicar-versao! [this ente-id m] (transacao this ente-id #(pauta/publicar-versao! % (assoc m :ente-id ente-id))))
  (buscar-versao [this ente-id id] (transacao this ente-id #(pauta/buscar-versao % ente-id id)))
  (listar-versoes [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/listar-versoes % ente-id pauta-sessao-id)))
  (versao-publica-corrente [this ente-id pauta-sessao-id] (transacao this ente-id #(pauta/versao-publica-corrente % ente-id pauta-sessao-id)))
  (registrar-presenca! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (presenca/registrar-evento! tx (assoc m :ente-id ente-id))]
          (producers/emitir-presenca-registrada! bus tx ente-id
            {:sessao-id (:sessao-id m) :vereador-id (:vereador-id m) :tipo (:tipo m)
             :modalidade (:modalidade m) :fonte (:fonte m) :ocorrido-em (str (:ocorrido-em m))})
          r))))
  (listar-presenca [this ente-id sessao-id] (transacao this ente-id #(presenca/listar-eventos % ente-id sessao-id)))
  (esta-presente? [this ente-id sessao-id vereador-id instante] (transacao this ente-id #(rel-presenca/esta-presente-em? % sessao-id vereador-id instante)))
  (presentes-plenario [this ente-id sessao-id instante] (transacao this ente-id #(rel-presenca/presentes-plenario % sessao-id instante)))
  (presentes-remoto [this ente-id sessao-id instante] (transacao this ente-id #(rel-presenca/presentes-remoto % sessao-id instante)))
  (criar-justificativa! [this ente-id m] (transacao this ente-id #(presenca/criar-justificativa! % (assoc m :ente-id ente-id))))
  (buscar-justificativa [this ente-id id] (transacao this ente-id #(presenca/buscar-justificativa % ente-id id)))
  (decidir-justificativa! [this ente-id m] (transacao this ente-id #(presenca/decidir-justificativa! % (assoc m :ente-id ente-id))))
  (registrar-segmento! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (gravacao/registrar-segmento! tx (assoc m :ente-id ente-id))]
          ;; fronteira core->IA (§22.3.3): o segmento captado vai p/ o pipeline de transcricao.
          (producers/emitir-gravacao-segmento-captado! bus tx ente-id
            (cond-> {:segmento-id (:id m) :container-bruto-uri (:container-bruto-uri m)
                     :fonte-ingestao (:fonte-ingestao m) :acesso-restrito (boolean (:acesso-restrito m))}
              (:sessao-id m) (assoc :sessao-id (:sessao-id m))))
          r))))
  (vincular-segmento! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r   (gravacao/vincular-segmento! tx (assoc m :ente-id ente-id))
              ;; le o estado pos-vinculo p/ o evento carregar o acesso-restrito DEFINITIVO (re-derivado p/
              ;; sessao secreta) — a IA reconcilia o sigilo por este evento, nao pelo captado (que no fluxo
              ;; Opcao A pode ter saido com acesso-restrito=false). Mesma tx do ato (atomicidade §22.9 E2).
              seg (gravacao/buscar tx ente-id (:id m))]
          (producers/emitir-gravacao-segmento-vinculado! bus tx ente-id
            {:segmento-id (:id m) :sessao-id (:sessao-id m) :acesso-restrito (boolean (:acesso-restrito seg))})
          r))))
  (buscar-segmento [this ente-id id] (transacao this ente-id #(gravacao/buscar % ente-id id)))
  (listar-segmentos-da-sessao [this ente-id sessao-id] (transacao this ente-id #(gravacao/listar-segmentos-da-sessao % ente-id sessao-id)))
  (inscrever! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (tribuna/inscrever! tx (assoc m :ente-id ente-id))]
          (producers/emitir-inscricao-registrada! bus tx ente-id
            {:inscricao-id (:id m) :sessao-id (:sessao-id m) :vereador-id (:vereador-id m)
             :origem-inscricao (:origem-inscricao m) :fase (:fase m) :ordem (:ordem r)})
          r))))
  (buscar-inscricao [this ente-id id] (transacao this ente-id #(tribuna/buscar-inscricao % ente-id id)))
  (listar-inscricoes [this ente-id sessao-id] (transacao this ente-id #(tribuna/listar-inscricoes % ente-id sessao-id)))
  (desistir! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [sid (:sessao-id (tribuna/buscar-inscricao tx ente-id (:id m)))  ; sessao-id p/ rotear o canal
              r   (tribuna/desistir! tx (assoc m :ente-id ente-id))]
          (producers/emitir-inscricao-desistida! bus tx ente-id {:inscricao-id (:id m) :sessao-id sid})
          r))))
  (iniciar-fala! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (tribuna/iniciar-fala! tx (assoc m :ente-id ente-id))]
          (producers/emitir-fala-iniciada! bus tx ente-id
            (cond-> {:fala-id (:id m) :sessao-id (:sessao-id m) :orador-id (:orador-id m)
                     :tipo-fala (:tipo-fala m) :fase (:fase m) :iniciou-em (str (:iniciou-em m))}
              (:inscricao-id m) (assoc :inscricao-id (:inscricao-id m))))
          r))))
  (registrar-evento-cronometro! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r   (tribuna/registrar-evento-cronometro! tx (assoc m :ente-id ente-id))
              sid (:sessao-id (tribuna/buscar-fala tx ente-id (:fala-id m)))]  ; sessao-id p/ rotear o canal
          (producers/emitir-fala-cronometro! bus tx ente-id
            (cond-> {:fala-id (:fala-id m) :sessao-id sid :tipo (:tipo m) :ocorrido-em (str (:ocorrido-em m))}
              (:segundos-adicionais m) (assoc :segundos-adicionais (:segundos-adicionais m))))
          r))))
  (encerrar-fala! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (tribuna/encerrar-fala! tx (assoc m :ente-id ente-id))
              f (tribuna/buscar-fala tx ente-id (:id m))]
          (producers/emitir-fala-encerrada! bus tx ente-id
            {:fala-id (:id m) :sessao-id (:sessao-id f)
             :tempo-segundos (:tempo-efetivamente-usado-segundos r) :encerrou-em (str (:encerrou-em m))})
          r))))
  (buscar-fala [this ente-id id] (transacao this ente-id #(tribuna/buscar-fala % ente-id id)))
  (listar-falas-da-sessao [this ente-id sessao-id] (transacao this ente-id #(tribuna/listar-falas-da-sessao % ente-id sessao-id)))
  (listar-apartes [this ente-id fala-pai-id] (transacao this ente-id #(tribuna/listar-apartes % ente-id fala-pai-id)))
  (listar-eventos-cronometro [this ente-id fala-id] (transacao this ente-id #(tribuna/listar-eventos-cronometro % ente-id fala-id)))
  (registrar-decisao-mesa! [this ente-id m] (transacao this ente-id #(tribuna/registrar-decisao-mesa! % (assoc m :ente-id ente-id))))
  (buscar-decisao-mesa [this ente-id id] (transacao this ente-id #(tribuna/buscar-decisao-mesa % ente-id id)))
  (listar-decisoes-mesa [this ente-id sessao-id] (transacao this ente-id #(tribuna/listar-decisoes-mesa % ente-id sessao-id)))
  ;; §16.13 — compoe o ato append-only + a emissao do evento de tempo real na MESMA tx (atomicidade §22.9 E2):
  ;; o painel da mesa de conducao reage ao incidente ao vivo (SSE canal plenario).
  (registrar-incidente! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (incidente/registrar! tx (assoc m :ente-id ente-id))]
          (producers/emitir-incidente-registrado! bus tx ente-id
            (cond-> {:incidente-id (:id m) :sessao-id (:sessao-id m) :tipo (:tipo m)
                     :resultado (:resultado m) :ocorrido-em (str (:ocorrido-em m))}
              (:objeto-tipo m)   (assoc :objeto-tipo (:objeto-tipo m))
              (:objeto-id m)     (assoc :objeto-id (:objeto-id m))
              (:requerente-id m) (assoc :requerente-id (:requerente-id m))))
          r))))
  (buscar-incidente [this ente-id id] (transacao this ente-id #(incidente/buscar % ente-id id)))
  (listar-incidentes [this ente-id sessao-id] (transacao this ente-id #(incidente/listar-da-sessao % ente-id sessao-id))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoSessoesPg nil nil))
