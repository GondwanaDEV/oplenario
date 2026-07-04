(ns oplenario.legislativo.components.repositorio
  "Component de PERSISTENCIA do legislativo — banco DISPONIBILIZADO como Stuart Sierra Component
  (ADR-0001 §3). O protocolo RepoLegislativo expoe as ACOES (tenant-aware: trata `com-tenant*` por
  dentro); o record segura o :datasource (via `using`); o db/ e' a IMPL. O controller depende DESTE
  Component, nunca do db/ direto. `transacao` compoe varias acoes numa UNICA tx do tenant."
  (:require [clojure.string :as str]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.kernel.ids :as ids]
            [oplenario.kernel.tenancy :as tenancy]
            [oplenario.legislativo.components.assinador-icp :as assinador-icp]
            [oplenario.legislativo.components.serializador-publicacao :as ser-pub]
            [oplenario.legislativo.db.apensacao :as apensacao]
            [oplenario.legislativo.db.artefato-publicacao :as artefato]
            [oplenario.legislativo.db.autografo :as autografo]
            [oplenario.legislativo.db.documento :as documento]
            [oplenario.legislativo.db.documento-modelo :as doc-modelo]
            [oplenario.legislativo.db.emenda :as emenda]
            [oplenario.legislativo.db.norma :as norma]
            [oplenario.legislativo.db.parecer :as parecer]
            [oplenario.legislativo.db.parecer-texto-versao :as parecer-texto]
            [oplenario.legislativo.db.parecer-tramitacao :as parecer-tram]
            [oplenario.legislativo.db.parecer-voto-divergente :as parecer-voto]
            [oplenario.legislativo.db.proposicao :as proposicao]
            [oplenario.legislativo.db.protocolo-geral :as protocolo]
            [oplenario.legislativo.db.texto-versao :as texto]
            [oplenario.legislativo.db.tramitacao :as tram]
            [oplenario.legislativo.db.tramitacao-executiva :as exec]
            [oplenario.legislativo.db.votacao :as votacao]
            [oplenario.legislativo.diplomat.producers :as producers]
            [oplenario.legislativo.gerador-publicacao :as ger-pub])
  (:import (java.security MessageDigest)
           (org.postgresql.util PSQLException)))

(defprotocol RepoLegislativo
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  (protocolar! [this ente-id proposicao] "Gate eixo H: numera (gapless) + URN + insere, atomico.")
  (buscar-proposicao [this ente-id id])
  (listar-por-estado [this ente-id estado])
  (mudar-estado-proposicao! [this ente-id m])
  ;; eixo B — versionamento de texto
  (nova-versao! [this ente-id versao] "Cria versao 'rascunho' (conteudo append-only).")
  (promover-versao! [this ente-id m] "Promove rascunho->vigente (ato auditado; reaponta o pointer).")
  (buscar-versao [this ente-id id])
  (versoes-da-proposicao [this ente-id proposicao-id])
  (texto-vigente [this ente-id proposicao-id])
  ;; eixo C — tramitacao por motor declarativo
  (criar-template! [this ente-id template])
  (criar-estado! [this ente-id estado])
  (criar-transicao! [this ente-id transicao])
  (transicionar! [this ente-id registro args] "Engine: guard via motor + historico + muda estado, 1 tx.")
  (historico-da-proposicao [this ente-id proposicao-id])
  ;; eixo D — emendas
  (criar-emenda! [this ente-id emenda] "Numera local por mae + insere ('apresentada').")
  (buscar-emenda [this ente-id id])
  (emendas-da-proposicao [this ente-id proposicao-mae-id])
  (mudar-estado-emenda! [this ente-id m] "Ciclo enum simples; CAS + trava terminal.")
  (aprovar-emenda! [this ente-id m] "Aplica ao texto-mae: cria rascunho + fecha ciclo, 1 tx.")
  ;; eixo E — apensacao (associacao com historico)
  (apensar! [this ente-id m] "Apensa apensada->principal (ativa). UNIQUE-ativa + CHECK reflexivo barram.")
  (desapensar! [this ente-id m] "UPDATE em desapensada_em (NAO DELETE); CAS + so a ativa desapensa.")
  (buscar-apensacao [this ente-id id])
  (apensadas-ativas [this ente-id principal-id] "Apensadas ativas diretas (nivel 1).")
  (cadeia-apensacao [this ente-id principal-id] "Cadeia genuina (traversal recursivo, cycle-safe).")
  ;; eixo F — parecer_comissao (state machine propria governada pelo motor do eixo C)
  (iniciar-parecer! [this ente-id m] "Cria parecer: estado inicial do template + valida sujeito/objeto.")
  (buscar-parecer [this ente-id id])
  (pareceres-do-objeto [this ente-id objeto-tipo objeto-id] "Pareceres sobre proposicao|emenda (disc.2).")
  (designar-relator! [this ente-id m] "Designa o relator (CAS).")
  (transicionar-parecer! [this ente-id registro args] "Engine do parecer + emite parecer.transicionou, 1 tx.")
  (historico-do-parecer [this ente-id parecer-id])
  ;; eixo F / F3.6b — texto do parecer (eixo B aplicado) + votos divergentes (aux append-only)
  (nova-versao-parecer! [this ente-id versao] "Cria versao 'rascunho' do texto do parecer (append-only).")
  (promover-versao-parecer! [this ente-id m] "Promove rascunho->vigente + reaponta o pointer, 1 tx.")
  (buscar-versao-parecer [this ente-id id])
  (versoes-do-parecer [this ente-id parecer-id])
  (texto-vigente-parecer [this ente-id parecer-id])
  (registrar-voto-divergente! [this ente-id m] "Registra voto vencido (append-only puro).")
  (votos-divergentes-do-parecer [this ente-id parecer-id])
  ;; eixo G — votacao (emite eventos votacao.aberta/voto.registrado/votacao.encerrada = fonte do placar ao vivo, F4)
  (abrir-votacao! [this ente-id m] "Abre votacao 'aberta' sobre objeto polimorfico.")
  (registrar-voto! [this ente-id m] "Voto nominal atribuido (append-only; UNIQUE por vereador).")
  (registrar-voto-secreto! [this ente-id m] "Voto secreto anonimo (sem vereador_id).")
  (encerrar-votacao! [this ente-id m] "Apura + computa resultado (quorum exato) + grava snapshot, CAS.")
  (anular-votacao! [this ente-id m] "Leva a 'anulada' (correcao = nova votacao).")
  (buscar-votacao [this ente-id id])
  (votos-da-votacao [this ente-id votacao-id])
  ;; F3.8a — pos-aprovacao: autografo (artefato legal append-only) + tramitacao no Executivo (sancao/veto)
  (gerar-autografo! [this ente-id m] "Numera gapless + insere o autografo (append-only); UNIQUE por proposicao.")
  (buscar-autografo [this ente-id id])
  (autografo-da-proposicao [this ente-id proposicao-id])
  (iniciar-tramitacao-executiva! [this ente-id m] "Abre 'aguardando' p/ um autografo (UNIQUE por autografo).")
  (registrar-resposta-executivo! [this ente-id m] "aguardando -> sancionado|sancao_tacita|vetado; CAS.")
  (apreciar-veto! [this ente-id m] "vetado -> veto_mantido|veto_derrubado (carimba a votacao do eixo G); CAS.")
  (buscar-tramitacao-executiva [this ente-id id])
  (tramitacao-executiva-do-autografo [this ente-id autografo-id])
  ;; F3.8b — norma promulgada (numeracao canonica + URN-de-norma LexML + publicacao)
  (promulgar-norma! [this ente-id m] "Numera gapless + URN-de-norma + insere 'promulgada', atomico.")
  (publicar-norma! [this ente-id m] "promulgada -> publicada (mutacao parcial unica); CAS.")
  (buscar-norma [this ente-id id])
  (norma-da-proposicao [this ente-id proposicao-id])
  ;; F3.9a — Expediente: Protocolo Geral (numerador institucional unico, append-only)
  (protocolar-geral! [this ente-id m] "Numera gapless (reinicio anual) + insere no livro do protocolo, atomico.")
  (buscar-protocolo [this ente-id id])
  (protocolos-do-objeto [this ente-id objeto-tipo objeto-id])
  (protocolos-do-ano [this ente-id ano])
  ;; F3.9b — Expediente: geracao de documentos por modelo (merge do dominio)
  (criar-modelo! [this ente-id m] "Cria um template de documento (config do tenant).")
  (buscar-modelo [this ente-id id])
  (modelo-por-chave [this ente-id chave])
  (listar-modelos-ativos [this ente-id])
  (atualizar-modelo! [this ente-id m] "Edita nome/corpo/ativo do modelo (CAS).")
  (gerar-documento! [this ente-id m] "Renderiza o merge + insere 'rascunho', atomico.")
  (buscar-documento [this ente-id id])
  (documentos-do-modelo [this ente-id modelo-id])
  (editar-documento! [this ente-id m] "Reescreve corpo/assunto enquanto rascunho (CAS).")
  (emitir-documento! [this ente-id m] "rascunho -> emitido (congela o conteudo); CAS.")
  ;; F6c Slice 4a — artefato de publicacao oficial ('DO-lite', doc-mestre L287). Verdade de dominio do
  ;; legislativo (§22.10: artefato legal e' do dominio, nao da projecao transparencia — que so' EXIBE, Slice 4b).
  (gerar-artefato-publicacao! [this ente-id m]
    "Gera o ARTEFATO oficial de uma norma PUBLICADA (doc-mestre L287): resolve a norma + o texto legal integral
     -> renderiza (puro) -> serializa (port) -> assina (port ICP STUB, §22.5 eixo F) -> hash -> grava o binario
     no objeto_store -> insere `artefato_publicacao` VERSIONADO (versao MAX+1 atomica). Fail-closed: norma nao
     'publicada'/sem texto ou campo nao resolvido ABORTA antes de persistir. `m` = {:norma-id :serializador
     :assinador :objeto-store :assinado-por?}. Devolve a linha do artefato.")
  (buscar-artefato-publicacao [this ente-id id])
  (artefatos-da-norma [this ente-id norma-id] "Artefatos de uma norma, por versao (historico de (re)geracoes)."))

;; ---------- geracao do artefato de publicacao oficial ('DO-lite', doc-mestre L287, F6c Slice 4a):
;;            resolve a norma publicada + o texto legal -> renderiza (puro) -> serializa+assina (ports STUB) ->
;;            objeto_store -> insere `artefato_publicacao` versionado (MAX+1 atomico). ----------

(defn- sha256-hex
  "Hash hex SHA-256 do binario, prefixado 'sha256:' — integridade do artefato. bit-and 0xff: byte assinado da
  JVM nao vira 'ffffff..' no hex. (Duplica conscientemente o helper de compliance/repositorio — 4 linhas,
  modulos independentes; consolidar num kernel/hash e' cleanup futuro, nao vale acoplar os modulos agora.)"
  [^bytes b]
  (let [h (.digest (MessageDigest/getInstance "SHA-256") b)]
    (str "sha256:" (apply str (map #(format "%02x" (bit-and (int %) 0xff)) h)))))

(defn- resolver-corpo
  "Resolve o TEXTO LEGAL integral da versao promulgada da `norma`: inline (texto_inline) direto, ou externalizado
  (conteudo_uri) via objeto_store. Fail-closed: texto ausente/vazio -> LANCA (documento legal nao sai sem corpo).
  A ex-data carrega norma-id/texto-versao-id (erro ACIONAVEL — mesma disciplina dos demais throws desta funcao
  e de gerador-remessa; um texto-versao-id pendente ou blob inacessivel e' triado sem stack-trace)."
  [norma texto objeto-store]
  (let [{:keys [texto-inline conteudo-uri]} texto
        corpo (cond
                (and (string? texto-inline) (not (str/blank? texto-inline))) texto-inline
                (and (string? conteudo-uri) (not (str/blank? conteudo-uri)))
                (when-let [b (os/obter objeto-store conteudo-uri)] (String. ^bytes b "UTF-8"))
                :else nil)]
    (when (or (nil? corpo) (str/blank? corpo))
      (throw (ex-info "gerar-artefato-publicacao!: texto legal da norma nao resolvido"
                      {:norma-id (:id norma) :texto-versao-id (:texto-versao-id norma)})))
    corpo))

(defn- inserir-artefato-com-retry!
  "Insere o artefato versionado (versao MAX+1 ATOMICA) re-tentando UMA vez no 23505 — corrida de versao
  concorrente (mesma disciplina de compliance/gerar-remessa!, carry F5.3a-1). Cada tentativa = tx propria."
  [repo ente-id row-base]
  (letfn [(inserir [] (transacao repo ente-id #(artefato/inserir-versionada! % row-base)))]
    (try (inserir)
         (catch PSQLException e
           (if (= "23505" (.getSQLState e)) (inserir) (throw e))))))

(defrecord RepoLegislativoPg [datasource bus]
  RepoLegislativo
  (transacao [_ ente-id f] (tenancy/com-tenant* (:ds datasource) ente-id f))
  ;; gate eixo H: protocola + EMITE `proposicao.protocolada` (snapshot publico) na MESMA tx (atomicidade
  ;; outbox-com-o-ato §22.9 E2 — a materia so aparece no portal se o protocolo commitou). O read-model de
  ;; transparencia projeta deste evento (§22.10: sem import/JOIN cross-modulo).
  (protocolar! [this ente-id p]
    (transacao this ente-id
      (fn [tx]
        (let [r (proposicao/protocolar! tx p)]
          (producers/emitir-protocolada! bus tx ente-id
            {:proposicao-id (:id r) :tipo (:tipo p) :ano (:ano p) :sequencial (:sequencial r)
             :urn-lex (:urn-lex r) :ementa (:ementa p) :estado "protocolada"
             :autor-tipo (:autor-tipo p) :autor-texto (:autor-texto p)})
          r))))
  (buscar-proposicao [this ente-id id] (transacao this ente-id #(proposicao/buscar % ente-id id)))
  (listar-por-estado [this ente-id estado] (transacao this ente-id #(proposicao/listar-por-estado % ente-id estado)))
  (mudar-estado-proposicao! [this ente-id m] (transacao this ente-id #(proposicao/mudar-estado! % (assoc m :ente-id ente-id))))
  (nova-versao! [this ente-id v] (transacao this ente-id #(texto/nova-versao! % (assoc v :ente-id ente-id))))
  (promover-versao! [this ente-id m] (transacao this ente-id #(texto/promover! % (assoc m :ente-id ente-id))))
  (buscar-versao [this ente-id id] (transacao this ente-id #(texto/buscar % ente-id id)))
  (versoes-da-proposicao [this ente-id pid] (transacao this ente-id #(texto/versoes-da-proposicao % ente-id pid)))
  (texto-vigente [this ente-id pid] (transacao this ente-id #(texto/vigente % ente-id pid)))
  (criar-template! [this ente-id t] (transacao this ente-id #(tram/criar-template! % (assoc t :ente-id ente-id))))
  (criar-estado! [this ente-id e] (transacao this ente-id #(tram/criar-estado! % (assoc e :ente-id ente-id))))
  (criar-transicao! [this ente-id tr] (transacao this ente-id #(tram/criar-transicao! % (assoc tr :ente-id ente-id))))
  ;; eixo C / F3.3b: ENGINE + emissao do evento de dominio na MESMA tx do tenant (atomicidade
  ;; outbox-com-o-ato §22.9 E2 — o `proposicao.transicionou` so existe se a transicao commitou; guard
  ;; que bloqueia = sem transicao = sem evento). E' o Repo (composer de tx) quem casa ato+emissao.
  (transicionar! [this ente-id registro args]
    (transacao this ente-id
      (fn [tx]
        (let [r (tram/transicionar! tx (assoc args :registro registro :ente-id ente-id))]
          (when (:transicionou? r)
            (producers/emitir-transicionou! bus tx ente-id
              ;; :ator-id so entra quando ha ator (acao anonima omite a chave — contrato {:optional true})
              (cond-> {:proposicao-id (:proposicao-id args) :template-id (:template-id args)
                       :de (:de r) :para (:para r) :gatilho (:gatilho args)
                       :transicao-id (:transicao-id r)}
                (:ator-id args) (assoc :ator-id (:ator-id args)))))
          r))))
  (historico-da-proposicao [this ente-id pid] (transacao this ente-id #(tram/historico-da-proposicao % ente-id pid)))
  ;; eixo D / F3.4 — emendas. aprovar! compoe (nova-versao rascunho + muda estado) numa UNICA tx do tenant.
  (criar-emenda! [this ente-id e] (transacao this ente-id #(emenda/criar! % (assoc e :ente-id ente-id))))
  (buscar-emenda [this ente-id id] (transacao this ente-id #(emenda/buscar % ente-id id)))
  (emendas-da-proposicao [this ente-id pid] (transacao this ente-id #(emenda/listar-por-mae % ente-id pid)))
  (mudar-estado-emenda! [this ente-id m] (transacao this ente-id #(emenda/mudar-estado! % (assoc m :ente-id ente-id))))
  (aprovar-emenda! [this ente-id m] (transacao this ente-id #(emenda/aprovar! % (assoc m :ente-id ente-id))))
  ;; eixo E / F3.5 — apensacao. Desapensacao = UPDATE (fato persiste); mudanca de principal = 2 atos.
  (apensar! [this ente-id m] (transacao this ente-id #(apensacao/apensar! % (assoc m :ente-id ente-id))))
  (desapensar! [this ente-id m] (transacao this ente-id #(apensacao/desapensar! % (assoc m :ente-id ente-id))))
  (buscar-apensacao [this ente-id id] (transacao this ente-id #(apensacao/buscar % ente-id id)))
  (apensadas-ativas [this ente-id pid] (transacao this ente-id #(apensacao/apensadas-ativas % ente-id pid)))
  (cadeia-apensacao [this ente-id pid] (transacao this ente-id #(apensacao/cadeia % ente-id pid)))
  ;; eixo F / F3.6a — parecer. transicionar-parecer! compoe ENGINE + emissao do evento na MESMA tx do
  ;; tenant (atomicidade outbox-com-o-ato §22.9 E2; espelha transicionar! da proposicao). O payload carrega
  ;; objeto_tipo/objeto_id (do retorno do engine) p/ o consumer da mae em F3.6c.
  (iniciar-parecer! [this ente-id m] (transacao this ente-id #(parecer/criar! % (assoc m :ente-id ente-id))))
  (buscar-parecer [this ente-id id] (transacao this ente-id #(parecer/buscar % ente-id id)))
  (pareceres-do-objeto [this ente-id ot oid] (transacao this ente-id #(parecer/listar-por-objeto % ente-id ot oid)))
  (designar-relator! [this ente-id m] (transacao this ente-id #(parecer/designar-relator! % (assoc m :ente-id ente-id))))
  (transicionar-parecer! [this ente-id registro args]
    (transacao this ente-id
      (fn [tx]
        (let [r (parecer-tram/transicionar-parecer! tx (assoc args :registro registro :ente-id ente-id))]
          (when (:transicionou? r)
            (producers/emitir-transicionou-parecer! bus tx ente-id
              (cond-> {:parecer-id (:parecer-id args) :template-id (:template-id args)
                       :objeto-tipo (:objeto-tipo r) :objeto-id (:objeto-id r)
                       :de (:de r) :para (:para r) :gatilho (:gatilho args)
                       :transicao-id (:transicao-id r)}
                (:ator-id args) (assoc :ator-id (:ator-id args)))))
          r))))
  (historico-do-parecer [this ente-id pid] (transacao this ente-id #(parecer-tram/historico-do-parecer % ente-id pid)))
  ;; eixo F / F3.6b — texto + votos divergentes. promover! compoe (supersede + vigente + reaponta pointer) 1 tx.
  (nova-versao-parecer! [this ente-id v] (transacao this ente-id #(parecer-texto/nova-versao! % (assoc v :ente-id ente-id))))
  (promover-versao-parecer! [this ente-id m] (transacao this ente-id #(parecer-texto/promover! % (assoc m :ente-id ente-id))))
  (buscar-versao-parecer [this ente-id id] (transacao this ente-id #(parecer-texto/buscar % ente-id id)))
  (versoes-do-parecer [this ente-id pid] (transacao this ente-id #(parecer-texto/versoes-do-parecer % ente-id pid)))
  (texto-vigente-parecer [this ente-id pid] (transacao this ente-id #(parecer-texto/vigente % ente-id pid)))
  (registrar-voto-divergente! [this ente-id m] (transacao this ente-id #(parecer-voto/registrar! % (assoc m :ente-id ente-id))))
  (votos-divergentes-do-parecer [this ente-id pid] (transacao this ente-id #(parecer-voto/listar-por-parecer % ente-id pid)))
  ;; eixo G / carry F4 — votacao. Compoe o ato + a emissao do evento de TEMPO REAL na MESMA tx do tenant
  ;; (atomicidade outbox-com-o-ato §22.9 E2; fonte do placar ao vivo, §22.6 eixo G). So emite quando ha
  ;; sessao-id: a votacao em plenario tem canal (`sessao/{id}/plenario`); o ato administrativo (ex.: apreciacao
  ;; de veto fora de sessao) nao tem canal p/ rotear -> sem evento. §22.6 SIGILO: o voto secreto e' TICK.
  (abrir-votacao! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (votacao/abrir! tx (assoc m :ente-id ente-id))]
          (when (:sessao-id m)
            (producers/emitir-votacao-aberta! bus tx ente-id
              (cond-> {:votacao-id (:id m) :sessao-id (:sessao-id m) :objeto-tipo (:objeto-tipo m)
                       :objeto-id (:objeto-id m) :modalidade (:modalidade m) :quorum-tipo (:quorum-tipo m)}
                (:pauta-item-id m) (assoc :pauta-item-id (:pauta-item-id m)))))
          r))))
  ;; GUARD DE MODALIDADE (defesa-em-profundidade do SIGILO §22.6): o DB nao amarra `votos` a
  ;; `votacoes.modalidade` — chamar registrar-voto! (nominal) sobre uma votacao SECRETA vazaria a identidade
  ;; no outbox. Busca a votacao ANTES de escrever, recusa fail-loud o cruzamento de modalidade (a tx rola
  ;; atras) e REUSA o `v` p/ o sessao-id do roteamento (uma unica leitura).
  (registrar-voto! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [v (votacao/buscar tx ente-id (:votacao-id m))]
          (when (not= "nominal" (:modalidade v))
            (throw (ex-info "registrar-voto!: votacao nao e' nominal — use registrar-voto-secreto!"
                            {:erro :modalidade-mismatch :votacao-id (:votacao-id m) :modalidade (:modalidade v)})))
          (let [r (votacao/registrar-voto! tx (assoc m :ente-id ente-id))]
            (when (:sessao-id v)
              (producers/emitir-voto-registrado! bus tx ente-id
                {:votacao-id (:votacao-id m) :sessao-id (:sessao-id v) :modalidade "nominal"
                 :vereador-id (:vereador-id m) :voto (:voto m)}))
            r)))))
  (registrar-voto-secreto! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [v (votacao/buscar tx ente-id (:votacao-id m))]
          (when (not= "secreta" (:modalidade v))
            (throw (ex-info "registrar-voto-secreto!: votacao nao e' secreta — use registrar-voto!"
                            {:erro :modalidade-mismatch :votacao-id (:votacao-id m) :modalidade (:modalidade v)})))
          (let [r (votacao/registrar-voto-secreto! tx (assoc m :ente-id ente-id))]
            (when (:sessao-id v)
              ;; §22.6 SIGILO: tick sem identidade nem valor do voto (so o contador ao vivo).
              (producers/emitir-voto-registrado! bus tx ente-id
                {:votacao-id (:votacao-id m) :sessao-id (:sessao-id v) :modalidade "secreta"}))
            r)))))
  (encerrar-votacao! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (votacao/encerrar! tx (assoc m :ente-id ente-id))
              v (votacao/buscar tx ente-id (:id m))]  ; snapshot persistido: sessao-id + totais + resultado
          (when (:sessao-id v)
            (producers/emitir-votacao-encerrada! bus tx ente-id
              (cond-> {:votacao-id (:id m) :sessao-id (:sessao-id v) :resultado (:resultado v)
                       :modalidade (:modalidade v)}
                (some? (:total-sim v))       (assoc :total-sim (:total-sim v))
                (some? (:total-nao v))       (assoc :total-nao (:total-nao v))
                (some? (:total-abstencao v)) (assoc :total-abstencao (:total-abstencao v))
                (some? (:base-membros v))    (assoc :base-membros (:base-membros v)))))
          r))))
  (anular-votacao! [this ente-id m] (transacao this ente-id #(votacao/anular! % (assoc m :ente-id ente-id))))
  (buscar-votacao [this ente-id id] (transacao this ente-id #(votacao/buscar % ente-id id)))
  (votos-da-votacao [this ente-id vid] (transacao this ente-id #(votacao/votos-da-votacao % ente-id vid)))
  ;; F3.8a — pos-aprovacao. autografo = append-only (artefato legal); tramitacao_executiva = state machine.
  ;; Apreciacao do veto carrega o id da VOTACAO (eixo G, maioria absoluta) — composicao no controller/sessao.
  (gerar-autografo! [this ente-id m] (transacao this ente-id #(autografo/gerar! % (assoc m :ente-id ente-id))))
  (buscar-autografo [this ente-id id] (transacao this ente-id #(autografo/buscar % ente-id id)))
  (autografo-da-proposicao [this ente-id pid] (transacao this ente-id #(autografo/buscar-por-proposicao % ente-id pid)))
  (iniciar-tramitacao-executiva! [this ente-id m] (transacao this ente-id #(exec/iniciar! % (assoc m :ente-id ente-id))))
  (registrar-resposta-executivo! [this ente-id m] (transacao this ente-id #(exec/registrar-resposta! % (assoc m :ente-id ente-id))))
  (apreciar-veto! [this ente-id m] (transacao this ente-id #(exec/apreciar-veto! % (assoc m :ente-id ente-id))))
  (buscar-tramitacao-executiva [this ente-id id] (transacao this ente-id #(exec/buscar % ente-id id)))
  (tramitacao-executiva-do-autografo [this ente-id aid] (transacao this ente-id #(exec/buscar-por-autografo % ente-id aid)))
  ;; F3.8b — norma. promulgar! compoe (sequencial + URN + insert) na tx; o caller garante o desfecho promulgavel.
  (promulgar-norma! [this ente-id m] (transacao this ente-id #(norma/promulgar! % (assoc m :ente-id ente-id))))
  ;; F3.8b: publica (promulgada -> publicada) + EMITE `norma.publicada` (marco de eficacia) na MESMA tx. Le a
  ;; norma pos-UPDATE p/ o snapshot publico (publicado_em/veiculo agora preenchidos). Transparencia projeta.
  (publicar-norma! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [r (norma/publicar! tx (assoc m :ente-id ente-id))
              n (norma/buscar tx ente-id (:id r))]
          (producers/emitir-norma-publicada! bus tx ente-id
            {:norma-id (:id n) :proposicao-id (:proposicao-id n) :tipo-norma (:tipo-norma n)
             :numero (:numero n) :ano (:ano n) :urn (:urn n) :ementa (:ementa n)
             :publicado-em (str (:publicado-em n)) :veiculo-publicacao (:veiculo-publicacao n)})
          r))))
  (buscar-norma [this ente-id id] (transacao this ente-id #(norma/buscar % ente-id id)))
  (norma-da-proposicao [this ente-id pid] (transacao this ente-id #(norma/buscar-por-proposicao % ente-id pid)))
  ;; F3.9a — Protocolo Geral. Append-only; numera gapless por ano. Objeto polimorfico (disc.2, sem FK).
  (protocolar-geral! [this ente-id m] (transacao this ente-id #(protocolo/protocolar! % (assoc m :ente-id ente-id))))
  (buscar-protocolo [this ente-id id] (transacao this ente-id #(protocolo/buscar % ente-id id)))
  (protocolos-do-objeto [this ente-id ot oid] (transacao this ente-id #(protocolo/buscar-por-objeto % ente-id ot oid)))
  (protocolos-do-ano [this ente-id ano] (transacao this ente-id #(protocolo/listar-por-ano % ente-id ano)))
  ;; F3.9b — documentos. gerar! renderiza o merge (logic) + insere rascunho na tx; emitir! congela.
  (criar-modelo! [this ente-id m] (transacao this ente-id #(doc-modelo/criar! % (assoc m :ente-id ente-id))))
  (buscar-modelo [this ente-id id] (transacao this ente-id #(doc-modelo/buscar % ente-id id)))
  (modelo-por-chave [this ente-id chave] (transacao this ente-id #(doc-modelo/buscar-por-chave % ente-id chave)))
  (listar-modelos-ativos [this ente-id] (transacao this ente-id #(doc-modelo/listar-ativos % ente-id)))
  (atualizar-modelo! [this ente-id m] (transacao this ente-id #(doc-modelo/atualizar! % (assoc m :ente-id ente-id))))
  (gerar-documento! [this ente-id m] (transacao this ente-id #(documento/gerar! % (assoc m :ente-id ente-id))))
  (buscar-documento [this ente-id id] (transacao this ente-id #(documento/buscar % ente-id id)))
  (documentos-do-modelo [this ente-id mid] (transacao this ente-id #(documento/listar-por-modelo % ente-id mid)))
  (editar-documento! [this ente-id m] (transacao this ente-id #(documento/editar-rascunho! % (assoc m :ente-id ente-id))))
  (emitir-documento! [this ente-id m] (transacao this ente-id #(documento/emitir! % (assoc m :ente-id ente-id))))
  ;; F6c Slice 4a — artefato de publicacao oficial. Le a norma publicada + texto (tx), resolve o corpo (inline
  ;; ou objeto_store, FORA da tx), renderiza (puro, fail-closed), serializa+assina (ports), insere VERSIONADO
  ;; (MAX+1 atomico; INSERT antes do S3 = ancora). SEM evento nesta fatia: a EXIBICAO (transparencia consome +
  ;; rota publica) e' a Slice 4b (emit+consume juntos, como o Slice 1 fez p/ norma.publicada).
  (gerar-artefato-publicacao! [this ente-id {:keys [norma-id serializador assinador objeto-store assinado-por]}]
    (when-not objeto-store (throw (ex-info "gerar-artefato-publicacao!: objeto-store ausente" {:ente-id ente-id})))
    (when-not serializador (throw (ex-info "gerar-artefato-publicacao!: serializador ausente" {:ente-id ente-id})))
    (when-not assinador (throw (ex-info "gerar-artefato-publicacao!: assinador ausente" {:ente-id ente-id})))
    (let [{:keys [norma texto]}
          (transacao this ente-id
            (fn [tx]
              (let [n (norma/buscar tx ente-id norma-id)]
                (when (nil? n)
                  (throw (ex-info "gerar-artefato-publicacao!: norma inexistente" {:norma-id norma-id :ente-id ente-id})))
                (when (not= "publicada" (:estado n))
                  (throw (ex-info "gerar-artefato-publicacao!: so' se gera artefato de norma 'publicada'"
                                  {:norma-id norma-id :estado (:estado n)})))
                (when (nil? (:texto-versao-id n))
                  (throw (ex-info "gerar-artefato-publicacao!: norma sem texto-versao-id (artefato legal vazio)"
                                  {:norma-id norma-id})))
                {:norma n :texto (texto/buscar tx ente-id (:texto-versao-id n))})))
          corpo     (resolver-corpo norma texto objeto-store)
          documento (ger-pub/renderizar
                     {:especie (:tipo-norma norma) :numero (:numero norma) :ano (:ano norma)
                      :urn (:urn norma) :ementa (:ementa norma)
                      :publicado-em (str (:publicado-em norma)) :veiculo (:veiculo-publicacao norma)
                      :corpo corpo})
          {b :bytes content-type :content-type} (ser-pub/serializar serializador documento)
          hash-conteudo (sha256-hex b)
          {:keys [algoritmo assinatura-b64]} (assinador-icp/assinar assinador b)   ; assinatura DESTACADA sobre os bytes
          ;; store-ref a partir do (:id norma) ECHOADO pelo banco (nao do param cru) — defesa-em-profundidade
          ;; (review sec LOW): a chave do objeto_store so' usa valores round-tripados+tipados pelo PG (uuid),
          ;; nunca texto livre do caller. ente-id = UUID da sessao; ambos os segmentos sao uuid canonico.
          store-ref (str "publicacoes/" ente-id "/" (:id norma) "/" hash-conteudo ".bin")
          row (inserir-artefato-com-retry! this ente-id
                {:id (ids/novo-id) :ente-id ente-id :norma-id norma-id :spec-versao (:spec-versao documento)
                 :content-type content-type :hash hash-conteudo :objeto-store-ref store-ref
                 :assinatura-algoritmo algoritmo :assinatura-b64 assinatura-b64 :assinado-por assinado-por})]
      (os/guardar! objeto-store store-ref b content-type)   ; binario no objeto_store APOS a ancora (INSERT-primeiro)
      row))
  (buscar-artefato-publicacao [this ente-id id] (transacao this ente-id #(artefato/buscar % ente-id id)))
  (artefatos-da-norma [this ente-id norma-id] (transacao this ente-id #(artefato/listar-por-norma % ente-id norma-id))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoLegislativoPg nil nil))
