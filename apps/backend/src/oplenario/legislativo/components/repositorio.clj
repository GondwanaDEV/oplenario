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
            [oplenario.legislativo.db.meu-painel :as meu-painel-db]
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
            [oplenario.legislativo.gerador-publicacao :as ger-pub]
            [oplenario.legislativo.logic :as logic])
  (:import (java.security MessageDigest)
           (org.postgresql.util PSQLException)))

(defprotocol RepoLegislativo
  (transacao [this ente-id f] "Roda (f tx) numa UNICA tx do tenant — compoe acoes atomicamente.")
  (protocolar! [this ente-id proposicao] "Gate eixo H: numera (gapless) + URN + insere, atomico.")
  (buscar-proposicao [this ente-id id])
  (listar-por-estado [this ente-id estado])
  (listar-e-contar-proposicoes [this ente-id filtro]
    "Onda B Slice 1: leitura filtrada/paginada/ordenada + total do MESMO filtro, NUMA UNICA tx (review
     ecc clojure+database — compoe como `protocolar!`/`transicionar!`; evita `itens`/`total` inconsistentes
     sob escrita concorrente + o round-trip extra de duas tx separadas). Devolve {:itens [...] :total N}.")
  (mudar-estado-proposicao! [this ente-id m])
  (editar-proposicao! [this ente-id m]
    "PATCH parcial (CAS) + promove nova versao 'edicao' se :texto presente, 1 tx.")
  (buscar-proposicao-detalhe [this ente-id id]
    "{:proposicao ... :texto (a linha de texto/vigente, ou nil)}, uma leitura.")
  (ficha-completa-da-proposicao [this ente-id id]
    "Onda B Slice 3 (ficha-materia, leitura interna): {:proposicao :texto :tramitacao :apensadas :emendas
     :pareceres} NUMA UNICA tx (mesma disciplina de buscar-proposicao-detalhe/listar-e-contar-proposicoes).
     Sem short-circuit no nil da proposicao (mesmo estilo de buscar-proposicao-detalhe): as demais leituras
     rodam do mesmo jeito e vem naturalmente vazias. Tetos (review MAJOR fe-9-ficha-materia — o `take`
     em memoria anterior truncava preservando os MAIS ANTIGOS): 100 p/ tramitacao, 50 p/
     apensadas/emendas/pareceres, empurrados ao SQL (mesmo padrao teto-fixo-50 de relatores-pendentes) — os
     4 db/ trazem os N MAIS RECENTES (DESC+LIMIT, revertido a ASC), nunca uma janela [0..N) do resultado
     cronologico inteiro. Apensadas = so nivel 1 (apensadas-ativas), NAO a cadeia recursiva — decisao de
     escopo desta fatia.")
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
  (relatores-pendentes [this ente-id] "Fila de pareceres aguardando designacao de relator (FE Onda A1).")
  ;; Onda B Slice 5 — borda de edicao/emissao do parecer (agrega leitura p/ o editor + o ato de emitir)
  (buscar-parecer-para-editor [this ente-id id]
    "UMA tx: {:parecer :objeto (proposicao, se objeto-tipo='proposicao'; senao nil) :texto-rascunho
     :texto-vigente}. nil (parecer inexistente) mesmo contrato de buscar-proposicao-detalhe.")
  (emitir-parecer! [this ente-id registro args]
    "Promove o rascunho mais recente a vigente (se houver) + registra o voto do relator + tenta
     transicionar (gatilho recebido, best-effort; MESMO padrao de transicionar-parecer!, `registro` p/
     o motor), 1 tx. Lanca :validacao/invalido se nao houver NENHUM conteudo de texto (nem rascunho nem
     vigente ja gravado) — nunca emite parecer vazio. Devolve o estado FINAL do parecer.")
  ;; eixo G — votacao (emite eventos votacao.aberta/voto.registrado/votacao.encerrada = fonte do placar ao vivo, F4)
  (abrir-votacao! [this ente-id m] "Abre votacao 'aberta' sobre objeto polimorfico.")
  (registrar-voto! [this ente-id m] "Voto nominal atribuido (append-only; UNIQUE por vereador).")
  (registrar-voto-secreto! [this ente-id m] "Voto secreto anonimo (sem vereador_id).")
  (registrar-meu-voto! [this ente-id m autorizar!]
    "Onda C3 — vota do proprio celular: numa SO tx, re-busca a votacao COM LOCK (`FOR UPDATE`, fecha a
     janela de corrida com `encerrar-votacao!`), roda `(autorizar! tx v-fresco)` (o predicado authz/policy —
     DEVE lancar em negacao, nunca so' devolver false), reconfere estado 'aberta' + modalidade 'nominal'
     contra o snapshot LOCKED (nao o `v` pre-tx do controller) e so' entao insere.")
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
  ;; Onda B Slice 7 — borda do pos-aprovacao: leitura composta + acao composta.
  (buscar-pos-aprovacao [this ente-id proposicao-id]
    "{:autografo (nil-ou-map) :tramitacao-executiva (nil-ou-map)} NUMA UNICA tx (mesma disciplina de
     buscar-ficha-materia/buscar-proposicao-detalhe). Sem short-circuit no nil do autografo — o controller
     decide 404 vs. corpo parcial (proposicao inexistente vs. proposicao sem autografo ainda).")
  (gerar-autografo-e-abrir-tramitacao! [this ente-id m]
    "Compoe autografo/gerar! (numera gapless + insere, append-only) + tramitacao-executiva/iniciar! (abre
     'aguardando') NUMA UNICA tx — atomico (se iniciar! falhar, gerar! desfaz e o numero reservado pelo
     kernel/sequencial some com o rollback, gapless preservado; mesmo racional de protocolar-documento!).
     Devolve {:autografo-id :numero :tramitacao-executiva-id}.")
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
  (buscar-documento-para-editor [this ente-id id]
    "Onda B Slice 6 (review clojure+database — consistencia com buscar-parecer-para-editor/
     ficha-completa-da-proposicao): leitura agregada p/ a aba 'Gerar documento' NUMA UNICA tx (mesmo
     snapshot MVCC, sem o round-trip extra de duas tx separadas). {:documento :protocolo}. `:protocolo`
     vem ENRIQUECIDO (numero/ano) SE o documento ja tiver `protocolo-geral-id`; nil enquanto 'rascunho'.
     nil (documento inexistente no tenant) mesmo contrato de buscar-parecer-para-editor.")
  ;; Onda B Slice 6 — o CTA 'Protocolar e numerar' do Expediente: acao COMPOSTA (protocolo geral + emissao)
  ;; NUMA UNICA tx (mesma disciplina de emitir-parecer!/protocolar!).
  (protocolar-documento! [this ente-id m]
    "Numera o documento no Protocolo Geral (objeto-tipo 'documento', sentido 'expedido') E emite o documento
     (rascunho -> emitido, vinculado ao protocolo recem-criado), 1 tx (mesmo racional de composicao de
     emitir-parecer!). `m` = {:documento-id :ano :ator-id :lock-version}. Fail-closed: documento inexistente
     no tenant lanca ANTES de protocolar (nada e' numerado por engano); documento fora de 'rascunho' ou
     lock-version desatualizado lanca no guard/CAS de emitir-documento! (a tx inteira rola atras — o numero
     recem-reservado NAO fica orfao, o kernel/sequencial e' transacional). Devolve {:documento-id
     :protocolo-numero :protocolo-ano :protocolo-id}.")
  ;; F6c Slice 4a — artefato de publicacao oficial ('DO-lite', doc-mestre L287). Verdade de dominio do
  ;; legislativo (§22.10: artefato legal e' do dominio, nao da projecao transparencia — que so' EXIBE, Slice 4b).
  (gerar-artefato-publicacao! [this ente-id m]
    "Gera o ARTEFATO oficial de uma norma PUBLICADA (doc-mestre L287): resolve a norma + o texto legal integral
     -> renderiza (puro) -> serializa (port) -> assina (port ICP STUB, §22.5 eixo F) -> hash -> grava o binario
     no objeto_store -> insere `artefato_publicacao` VERSIONADO (versao MAX+1 atomica). Fail-closed: norma nao
     'publicada'/sem texto ou campo nao resolvido ABORTA antes de persistir. `m` = {:norma-id :serializador
     :assinador :objeto-store :assinado-por?}. Devolve a linha do artefato.")
  (buscar-artefato-publicacao [this ente-id id])
  (artefatos-da-norma [this ente-id norma-id] "Artefatos de uma norma, por versao (historico de (re)geracoes).")
  ;; Onda C1 — borda /meu do vereador (§11.2/§11.3): leitura composta escopada por autor/relator.
  (meu-painel [this ente-id vereador-id]
    "Onda C1: {:proposicoes [...] :pareceres [...] :ciencias [...]} NUMA UNICA tx (mesma disciplina de
     buscar-proposicao-detalhe/ficha-completa-da-proposicao). `:ciencias` = pareceres publicados sobre
     proposicao de autoria do vereador, ainda nao acusados (Task 3).")
  (acusar-ciencia! [this ente-id m]
    "Task 3 — INSERT append-only idempotente (Inv.10) da ciencia do vereador sobre `evento-ref`. Devolve
     {:id :ciente-em}. CALLER (controller) confirma `parecer-elegivel-para-ciencia?` antes.")
  (parecer-elegivel-para-ciencia? [this ente-id vereador-id evento-ref]
    "Review CRITICO (clojure+database+security) — guard de `acusar-ciencia!`: `evento-ref` e' de fato um
     parecer publicado sobre proposicao do vereador `vereador-id` neste ente? Sem isso, `acusar-ciencia!`
     aceitava qualquer UUID sintaticamente valido na prova append-only (Inv.10)."))

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
  "Insere o artefato versionado (versao MAX+1 ATOMICA) + EMITE `artefato.publicacao.gerado` na MESMA tx do
  INSERT (F6c Slice 4b; atomicidade outbox-com-o-ato §22.9 E2 — a linha do evento so' existe se o INSERT
  commitou; espelha publicar-norma! -> norma.publicada). Re-tenta UMA vez no 23505 (corrida de versao
  concorrente; mesma disciplina de compliance/gerar-remessa!, carry F5.3a-1). Cada tentativa = tx propria:
  na 1a que colide (23505) TUDO rola atras (o evento tambem), e a 2a re-emite fresco -> 1 evento por artefato."
  [repo ente-id row-base]
  (letfn [(inserir []
            (transacao repo ente-id
              (fn [tx]
                (let [row (artefato/inserir-versionada! tx row-base)]
                  (producers/emitir-artefato-publicacao-gerado! (:bus repo) tx ente-id
                    {:norma-id (:norma-id row) :artefato-id (:id row) :versao (:versao row)
                     :hash (:hash row) :objeto-store-ref (:objeto-store-ref row)
                     :content-type (:content-type row) :assinatura-algoritmo (:assinatura-algoritmo row)
                     :assinado? (some? (:assinado-por row)) :criado-em (str (:criado-em row))})
                  row))))]
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
        (when-let [corpo (:texto p)]
          (when (= :objeto-store (logic/decidir-armazenamento corpo))
            (throw (ex-info "texto excede o limite inline (32KB); objeto_store fora do escopo desta fatia"
                            {:tipo :validacao/invalido :campos [:texto]}))))
        (let [r (proposicao/protocolar! tx (assoc p :ente-id ente-id))]
          (when-let [corpo (:texto p)]
            (let [versao-id (random-uuid)]
              (texto/nova-versao! tx {:id versao-id :ente-id ente-id :proposicao-id (:id r)
                                       :origem-versao "protocolo" :formato "markdown"
                                       :texto-inline corpo :created-by (:created-by p)})
              (texto/promover! tx {:ente-id ente-id :proposicao-id (:id r) :versao-id versao-id
                                    :updated-by (:created-by p) :lock-version 0})))
          (producers/emitir-protocolada! bus tx ente-id
            {:proposicao-id (:id r) :tipo (:tipo p) :ano (:ano p) :sequencial (:sequencial r)
             :urn-lex (:urn-lex r) :ementa (:ementa p) :estado "protocolada"
             :autor-tipo (:autor-tipo p) :autor-texto (:autor-texto p)})
          r))))
  (buscar-proposicao [this ente-id id] (transacao this ente-id #(proposicao/buscar % ente-id id)))
  (listar-por-estado [this ente-id estado] (transacao this ente-id #(proposicao/listar-por-estado % ente-id estado)))
  ;; Onda B Slice 1 (review ecc): listar+contar compostos NUMA UNICA tx — mesmo filtro le' `itens`/`total`
  ;; do mesmo snapshot MVCC, sem o round-trip extra de duas tx independentes.
  (listar-e-contar-proposicoes [this ente-id filtro]
    (transacao this ente-id
      (fn [tx]
        {:itens (proposicao/listar tx ente-id filtro)
         :total (proposicao/contar tx ente-id filtro)})))
  (mudar-estado-proposicao! [this ente-id m] (transacao this ente-id #(proposicao/mudar-estado! % (assoc m :ente-id ente-id))))
  ;; Onda B Slice 2: editar-proposicao! compoe (guard nao-terminal + PATCH parcial CAS) + versao 'edicao'
  ;; opcional numa UNICA tx (mesma disciplina de protocolar! — o texto novo so' e' vigente se o PATCH commitou).
  (editar-proposicao! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (when-let [corpo (:texto m)]
          (when (= :objeto-store (logic/decidir-armazenamento corpo))
            (throw (ex-info "texto excede o limite inline (32KB); objeto_store fora do escopo desta fatia"
                            {:tipo :validacao/invalido :campos [:texto]}))))
        (let [r (proposicao/editar! tx (assoc m :ente-id ente-id))]
          (when-let [corpo (:texto m)]
            (let [versao-id (random-uuid)]
              (texto/nova-versao! tx {:id versao-id :ente-id ente-id :proposicao-id (:id m)
                                       :origem-versao "edicao" :formato "markdown"
                                       :texto-inline corpo :created-by (:updated-by m)})
              (texto/promover! tx {:ente-id ente-id :proposicao-id (:id m) :versao-id versao-id
                                    :updated-by (:updated-by m) :lock-version 0})))
          r))))
  ;; Onda B Slice 2: leitura composta (proposicao + texto vigente) NUMA UNICA tx — mesmo snapshot MVCC
  ;; (mesma disciplina de listar-e-contar-proposicoes). Nao lanca quando a proposicao nao existe: devolve
  ;; {:proposicao nil :texto nil} (o caller/HTTP traduz p/ 404).
  (buscar-proposicao-detalhe [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        {:proposicao (proposicao/buscar tx ente-id id)
         :texto (texto/vigente tx ente-id id)})))
  ;; Onda B Slice 3 (ficha-materia): composicao NUMA UNICA tx (mesma disciplina de buscar-proposicao-detalhe).
  ;; Tetos EMPURRADOS AO SQL (review MAJOR fe-9-ficha-materia — `take` em memoria truncava preservando os
  ;; MAIS ANTIGOS e descartava os MAIS RECENTES, e ainda pagava o custo de fetch da tabela inteira): cada
  ;; db/ aceita `limite` e devolve os N MAIS RECENTES (DESC+LIMIT no SQL, revertido a ASC internamente —
  ;; o contrato de ordem cronologica pro caller e' o MESMO com ou sem limite). 100 tramitacao, 50
  ;; apensadas/emendas/pareceres (mesmo teto-fixo-50 de relatores-pendentes).
  (ficha-completa-da-proposicao [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        {:proposicao (proposicao/buscar tx ente-id id)
         :texto (texto/vigente tx ente-id id)
         :tramitacao (tram/historico-da-proposicao tx ente-id id 100)
         :apensadas (apensacao/apensadas-ativas tx ente-id id 50)
         :emendas (emenda/listar-por-mae tx ente-id id 50)
         :pareceres (parecer/listar-por-objeto tx ente-id "proposicao" id 50)})))
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
              ;; :ator-id so entra quando ha ator (acao anonima omite a chave — contrato {:optional true}).
              ;; :ocorrido-em (F7 carry): string ISO do Instant real da transicao (RETURNING de
              ;; registrar-transicao!) — NAO o momento de projecao a jusante.
              (cond-> {:proposicao-id (:proposicao-id args) :template-id (:template-id args)
                       :de (:de r) :para (:para r) :gatilho (:gatilho args)
                       :transicao-id (:transicao-id r) :ocorrido-em (str (:ocorrido-em r))}
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
  ;; FE Onda A1 (§16.11) — read-model barato, tenant-wide, teto fixo 50 (sem paginacao nesta fatia).
  (relatores-pendentes [this ente-id] (transacao this ente-id #(parecer/relatores-pendentes % ente-id 50)))
  ;; Onda B Slice 5 — editor/emissao do parecer. buscar-parecer-para-editor agrega LEITURA (parecer + objeto
  ;; opcional + texto rascunho/vigente) NUMA UNICA tx (mesma disciplina de ficha-completa-da-proposicao). O
  ;; objeto e' SEMPRE 'proposicao' nesta fatia (mesma decisao YAGNI de relatores-pendentes — 'emenda' fica
  ;; fora ate' um requisito de cliente puxar).
  (buscar-parecer-para-editor [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        (when-let [p (parecer/buscar tx ente-id id)]
          {:parecer p
           :objeto (when (= "proposicao" (:objeto-tipo p)) (proposicao/buscar tx ente-id (:objeto-id p)))
           :texto-rascunho (parecer-texto/rascunho-mais-recente tx ente-id id)
           :texto-vigente (parecer-texto/vigente tx ente-id id)}))))
  ;; emitir-parecer! compoe (promove rascunho->vigente se houver + registra voto SEMPRE + tenta transicionar
  ;; + emite parecer.transicionou na MESMA tx) — espelha transicionar-parecer! (eixo F/F3.6a) mas NAO pode
  ;; chamar o protocolo `transicionar-parecer!` diretamente (abriria SUA PROPRIA tx, quebrando a atomicidade
  ;; com promover!/registrar-voto-relator!) — por isso reusa parecer-tram/transicionar-parecer! (db/) +
  ;; producers/emitir-transicionou-parecer! INLINE, o MESMO bloco do impl acima.
  (emitir-parecer! [this ente-id registro {:keys [parecer-id template-id gatilho voto-relator updated-by agora
                                                   contexto lock-version]}]
    (transacao this ente-id
      (fn [tx]
        ;; CAS otimista contra o SNAPSHOT QUE O CLIENTE VIU (review HIGH fe-11-parecer): confere ANTES de
        ;; qualquer escrita, contra o `lock-version` que veio do corpo — os CAS internos abaixo (promover!/
        ;; registrar-voto-relator!) releem o valor FRESCO desta MESMA tx pra encadear os proprios passos
        ;; (isso e' correto: sao escritas NOSSAS, nao concorrentes) e por isso NUNCA veem conflito do ponto
        ;; de vista do cliente. Zero escritas ainda acontecem se este guard lanca (mesma disciplina do guard
        ;; de "sem rascunho e sem vigente" logo abaixo).
        (let [atual (parecer/buscar tx ente-id parecer-id)]
          (when (nil? atual)
            (throw (ex-info "emitir-parecer!: parecer inexistente no tenant"
                            {:parecer-id parecer-id :ente-id ente-id})))
          (when (not= lock-version (:lock-version atual))
            (throw (ex-info "conflito de escrita (lock_version desatualizado) ou parecer inexistente"
                            {:id parecer-id :lock-version lock-version}))))
        (let [rascunho (parecer-texto/rascunho-mais-recente tx ente-id parecer-id)]
          ;; fail-closed (spec Onda B Slice 5): SEM rascunho E SEM vigente ja gravado -> lanca. `and` e'
          ;; short-circuit — a leitura de `vigente` so' roda quando ja' nao ha rascunho.
          (when (and (nil? rascunho) (nil? (parecer-texto/vigente tx ente-id parecer-id)))
            (throw (ex-info "emitir-parecer!: nenhum conteudo de texto para emitir"
                            {:tipo :validacao/invalido :parecer-id parecer-id})))
          (when rascunho
            (parecer-texto/promover! tx {:ente-id ente-id :parecer-id parecer-id :versao-id (:id rascunho)
                                         :updated-by updated-by :lock-version (:lock-version rascunho)})))
        ;; voto SEMPRE seta (mesmo sem rascunho novo) — re-le' o lock-version POS-promover! (o reaponte do
        ;; pointer incrementa o lock_version do parecer; usar o valor pre-promover! CASaria contra versao
        ;; desatualizada e lancaria conflito espurio).
        (let [{:keys [lock-version]} (parecer/buscar tx ente-id parecer-id)]
          (parecer/registrar-voto-relator! tx {:id parecer-id :ente-id ente-id :voto-relator voto-relator
                                               :updated-by updated-by :lock-version lock-version}))
        (let [r (parecer-tram/transicionar-parecer! tx {:registro registro :ente-id ente-id :parecer-id parecer-id
                                                         :template-id template-id :gatilho gatilho :agora agora
                                                         :contexto contexto :updated-by updated-by})]
          (when (:transicionou? r)
            (producers/emitir-transicionou-parecer! bus tx ente-id
              {:parecer-id parecer-id :template-id template-id
               :objeto-tipo (:objeto-tipo r) :objeto-id (:objeto-id r)
               :de (:de r) :para (:para r) :gatilho gatilho :transicao-id (:transicao-id r)})))
        (parecer/buscar tx ente-id parecer-id))))
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
  ;; Onda C3 (review CRÍTICO fe-17-cockpit-votacao): a autorizacao (mandato+presenca, motor/politica-dsl)
  ;; PRECISA da MESMA tx da escrita — senao a janela entre autorizar e escrever deixa a Mesa encerrar a
  ;; votacao no meio do caminho (achado independente de 2 revisores). `autorizar!` roda AQUI, DENTRO da tx,
  ;; contra o snapshot LOCKED (`FOR UPDATE`) — nao o `v` pre-tx que o controller carregou so' p/ a amarra
  ;; votacao<->sessao e o guard trivial de 'secreta'.
  (registrar-meu-voto! [this ente-id m autorizar!]
    (transacao this ente-id
      (fn [tx]
        (let [v (votacao/buscar-com-lock tx ente-id (:votacao-id m))]
          (when (nil? v)
            (throw (ex-info "registrar-meu-voto!: votacao inexistente" {:votacao-id (:votacao-id m)})))
          (autorizar! tx v)
          (when (not= "aberta" (:estado v))
            (throw (ex-info "registrar-meu-voto!: votacao nao esta aberta"
                            {:votacao-id (:votacao-id m) :estado (:estado v)})))
          (when (not= "nominal" (:modalidade v))
            (throw (ex-info "registrar-meu-voto!: votacao nao e' nominal — use o caminho da Mesa"
                            {:votacao-id (:votacao-id m) :modalidade (:modalidade v)})))
          (let [r (votacao/registrar-voto! tx (assoc m :ente-id ente-id))]
            (when (:sessao-id v)
              (producers/emitir-voto-registrado! bus tx ente-id
                {:votacao-id (:votacao-id m) :sessao-id (:sessao-id v) :modalidade "nominal"
                 :vereador-id (:vereador-id m) :voto (:voto m)}))
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
  ;; Onda B Slice 7 — leitura composta (mesma disciplina de ficha-completa-da-proposicao/
  ;; buscar-parecer-para-editor): UMA tx, sem short-circuit no nil do autografo (a decisao 404-vs-corpo-
  ;; parcial e' do controller, que ja' confere a proposicao separadamente).
  (buscar-pos-aprovacao [this ente-id proposicao-id]
    (transacao this ente-id
      (fn [tx]
        (let [aut (autografo/buscar-por-proposicao tx ente-id proposicao-id)]
          {:autografo aut
           :tramitacao-executiva (when aut (exec/buscar-por-autografo tx ente-id (:id aut)))}))))
  ;; composicao ATOMICA (mesmo racional de protocolar-documento!): gerar! (numera+insere o autografo,
  ;; append-only) + iniciar! (abre 'aguardando') NA MESMA tx — se iniciar! falhar, gerar! desfaz e o numero
  ;; reservado pelo kernel/sequencial some com o rollback (gapless preservado).
  (gerar-autografo-e-abrir-tramitacao! [this ente-id m]
    (transacao this ente-id
      (fn [tx]
        (let [{aut-id :id numero :numero} (autografo/gerar! tx (assoc m :ente-id ente-id))
              {tram-id :id} (exec/iniciar! tx {:id (random-uuid) :ente-id ente-id :autografo-id aut-id
                                                :created-by (:created-by m)})]
          {:autografo-id aut-id :numero numero :tramitacao-executiva-id tram-id}))))
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
  ;; Onda B Slice 6 (review clojure+database, MAJOR de consistencia): agrega documento + protocolo (se ja
  ;; vinculado) NUMA UNICA tx — mesma disciplina de buscar-parecer-para-editor/ficha-completa-da-proposicao.
  ;; Antes o controller compunha 2 chamadas publicas do Repo (2 tx); agora e' 1 leitura, 1 tx, mesmo snapshot.
  (buscar-documento-para-editor [this ente-id id]
    (transacao this ente-id
      (fn [tx]
        (when-let [doc (documento/buscar tx ente-id id)]
          {:documento doc
           :protocolo (when-let [pid (:protocolo-geral-id doc)]
                        (protocolo/buscar tx ente-id pid))}))))
  ;; Onda B Slice 6 — 'Protocolar e numerar': COMPOE protocolo-geral/protocolar! (numera gapless, objeto-tipo
  ;; 'documento') + documento/emitir! (rascunho -> emitido, vinculado ao protocolo) NUMA UNICA tx (mesma
  ;; disciplina de protocolar!/emitir-parecer! — nao pode chamar os dois protocolos publicos separadamente,
  ;; abriria DUAS tx e quebraria a atomicidade). Le' o documento ANTES (existencia + :assunto, que o
  ;; protocolo-geral exige NOT NULL) — o guard de ESTADO ('so' rascunho protocola') e' o mesmo de emitir!
  ;; (reusado, nao duplicado): se o documento nao estiver 'rascunho' ou o lock-version nao bater, emitir!
  ;; lanca e a tx INTEIRA rola atras (o numero reservado pelo kernel/sequencial some com o rollback, gapless
  ;; preservado — nao ha' numero "gasto" por uma tentativa que falhou).
  ;; ACOPLAMENTO IMPLICITO (review database MENOR, registrado explicitamente): o `:assunto` gravado no
  ;; protocolo e' o snapshot lido AQUI, antes do CAS — so' e' seguro contra uma edicao concorrente porque
  ;; `editar-rascunho!` SEMPRE incrementa `lock_version` em qualquer mutacao bem-sucedida; se uma edicao
  ;; concorrente commitar entre esta leitura e o CAS de `emitir!` abaixo, o `lock-version` recebido pelo
  ;; cliente ja' esta' obsoleto e o CAS LANCA (a tx inteira rola atras, protocolo com assunto obsoleto incluido).
  ;; Se `editar-rascunho!` algum dia deixar de bumpar `lock_version` em algum caminho, esta protecao some
  ;; silenciosamente — vale um teste de regressao cross-modulo se isso mudar.
  ;; GUARD DE INEXISTENCIA (review clojure MENOR): esta excecao NAO carrega `:tipo :validacao/invalido`
  ;; (mesma convencao, INTENCIONAL, dos guards irmaos em db/documento.clj) — depende do diplomat pre-checar
  ;; existencia (via `buscar-documento-para-editor`) ANTES de chamar esta acao composta; o unico caller HTTP
  ;; hoje (`protocolar-documento-handler`) faz esse pre-check. Um caller futuro sem pre-check (job em lote,
  ;; outro controller) veria 500 opaco em vez de 400 fail-closed.
  (protocolar-documento! [this ente-id {:keys [documento-id ano ator-id lock-version]}]
    (transacao this ente-id
      (fn [tx]
        (let [doc (documento/buscar tx ente-id documento-id)]
          (when (nil? doc)
            (throw (ex-info "protocolar-documento!: documento inexistente no tenant"
                            {:documento-id documento-id :ente-id ente-id})))
          (let [protocolo-id (ids/novo-id)
                {:keys [numero]} (protocolo/protocolar! tx
                                    {:id protocolo-id :ente-id ente-id :ano ano :objeto-tipo "documento"
                                     :objeto-id documento-id :sentido "expedido" :assunto (:assunto doc)
                                     :protocolado-por ator-id :created-by ator-id})]
            (documento/emitir! tx {:id documento-id :ente-id ente-id :emitido-por ator-id :updated-by ator-id
                                    :lock-version lock-version :protocolo-geral-id protocolo-id})
            {:documento-id documento-id :protocolo-numero numero :protocolo-ano ano :protocolo-id protocolo-id})))))
  ;; F6c Slice 4a/4b — artefato de publicacao oficial. Le a norma publicada + texto (tx), resolve o corpo (inline
  ;; ou objeto_store, FORA da tx), renderiza (puro, fail-closed), serializa+assina (ports), insere VERSIONADO
  ;; (MAX+1 atomico; INSERT antes do S3 = ancora) + EMITE `artefato.publicacao.gerado` na tx do INSERT (Slice
  ;; 4b, §22.9 E2). A EXIBICAO (transparencia projeta o evento + rota publica de download) consome dai'.
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
  (artefatos-da-norma [this ente-id norma-id] (transacao this ente-id #(artefato/listar-por-norma % ente-id norma-id)))
  ;; Onda C1 — leitura composta NUMA UNICA tx (mesmo snapshot MVCC, mesma disciplina de
  ;; buscar-proposicao-detalhe/buscar-pos-aprovacao).
  (meu-painel [this ente-id vereador-id]
    (transacao this ente-id
      (fn [tx]
        {:proposicoes (meu-painel-db/proposicoes-do-autor tx ente-id vereador-id)
         :pareceres (meu-painel-db/pareceres-do-relator tx ente-id vereador-id)
         :ciencias (meu-painel-db/ciencias-pendentes tx ente-id vereador-id)})))
  (acusar-ciencia! [this ente-id m] (transacao this ente-id #(meu-painel-db/acusar-ciencia! % (assoc m :ente-id ente-id))))
  (parecer-elegivel-para-ciencia? [this ente-id vereador-id evento-ref]
    (transacao this ente-id #(meu-painel-db/parecer-elegivel-para-ciencia? % ente-id vereador-id evento-ref))))

(defn repositorio
  "Cria o Component (sem estado proprio; recebe :datasource via `using`)."
  []
  (->RepoLegislativoPg nil nil))
