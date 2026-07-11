(ns oplenario.legislativo.controllers
  "Orquestracao (impura) do legislativo (§22.10 controllers, ADR-0001): coordena Repo-Component + policy.check
  (camada FINA, §22.5 eixo E). Trabalha SO em models (kebab) — a traducao de borda fica no diplomat. A vertical
  da votacao ao vivo (F4 Slice 3) DIRIGE a votacao: a authz e' HERDADA do recurso SESSAO (lido via
  `consultar-sessao` INJETADA pelo host — legislativo NAO importa sessoes, §22.10), e as escritas usam o Repo do
  PROPRIO modulo (que casa ato + emissao do evento de tempo real na MESMA tx, Slice 1)."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.legislativo.components.repositorio :as repo]
            [oplenario.legislativo.logic :as logic]))

(set! *warn-on-reflection* true)

(defn- sessao-autorizada
  "Carrega a sessao `sessao-id` no tenant do `ator` via `consultar-sessao` (delega ao Repo de sessoes; a RLS
  escopa por tenant) e roda a camada FINA (policy.check/pode-dirigir-votacao? = mesma Casa). Devolve a sessao se
  autorizada; nil se inexistente (a borda traduz -> 404); LANCA negacao (-> 403) se de outra Casa. Fail-closed:
  ator nil NEGA aqui, nunca delega a consultar-sessao com ente-id nil."
  [consultar-sessao ator sessao-id]
  (when (nil? ator) (authz/negar! :ator-ausente {:acao :votacao/dirigir}))
  (when-let [s (consultar-sessao (:ente-id ator) sessao-id)]
    (authz/check! ator :votacao/dirigir s logic/pode-dirigir-votacao?)
    s))

(defn- votacao-na-sessao
  "Carrega a votacao `votacao-id` no tenant do ator e CONFIRMA que pertence a `sessao-id` (a amarra
  votacao<->sessao da URL). Devolve a votacao ou nil (inexistente OU de outra sessao) — a borda traduz -> 404,
  sem vazar a existencia de uma votacao de outra sessao."
  [repo-leg ente-id sessao-id votacao-id]
  (when-let [v (repo/buscar-votacao repo-leg ente-id votacao-id)]
    (when (= sessao-id (:sessao-id v)) v)))

(defn abrir-votacao
  "Abre uma votacao na sessao `sessao-id` (authz herdada da sessao). `m` ja vem decodificado/coagido pelo
  adapters/in (sem sessao-id). Devolve o recibo {:id} ou nil se a sessao nao existe no tenant (-> 404)."
  [repo-leg consultar-sessao ator sessao-id m]
  (when (sessao-autorizada consultar-sessao ator sessao-id)
    (repo/abrir-votacao! repo-leg (:ente-id ator) (assoc m :sessao-id sessao-id))))

(defn registrar-voto
  "Registra um voto na votacao `votacao-id` da sessao `sessao-id`. Authz na sessao + amarra votacao<->sessao.
  DISPATCH EXPLICITO (case 3-vias) pela modalidade da votacao CARREGADA: 'secreta' -> registrar-voto-secreto!
  (DESCARTA a identidade, sigilo §22.6); 'nominal' -> registrar-voto! (exige vereador-id); 'simbolica'
  (aclamacao) -> NAO registra votos individuais (o resultado e' cravado no encerramento via :resultado) ->
  :validacao/invalido (-> 400). Qualquer modalidade futura desconhecida cai no ramo fail-closed (nao no nominal).
  Devolve {:id} ou nil (sessao/votacao inexistente ou de outra sessao -> 404). Nominal sem vereador-id -> 400."
  [repo-leg consultar-sessao ator sessao-id votacao-id m]
  (when (sessao-autorizada consultar-sessao ator sessao-id)
    (let [ente-id (:ente-id ator)]
      (when-let [v (votacao-na-sessao repo-leg ente-id sessao-id votacao-id)]
        (case (:modalidade v)
          ;; sigilo §22.6 (defesa-em-profundidade): o caminho secreto nao carrega identidade nem autor.
          "secreta" (repo/registrar-voto-secreto! repo-leg ente-id (dissoc m :vereador-id :created-by))
          "nominal" (do
                      (when (nil? (:vereador-id m))
                        (throw (ex-info "voto nominal exige vereador-id"
                                        {:tipo :validacao/invalido :campos [:vereador-id]})))
                      (repo/registrar-voto! repo-leg ente-id m))
          ;; 'simbolica' (sem apuracao individual) E qualquer modalidade futura -> nao se registra voto aqui.
          (throw (ex-info "modalidade nao registra votos individuais"
                          {:tipo :validacao/invalido :campos [:modalidade] :modalidade (:modalidade v)})))))))

;; ========================= FE Onda A1: fila de relatores pendentes (§16.11) =========================

(defn relatores-pendentes
  "Fila de pareceres 'aguardando_designacao' do tenant `ente-id` (leitura tenant-wide, sem ator/policy fina —
  mesmo contrato de `resumo-presenca`/`esic-cumprimento`). Devolve as linhas cruas (kebab, do db); o
  adapters/out projeta+valida o contrato RelatoresPendentesOut."
  [repo-legislativo ente-id]
  (repo/relatores-pendentes repo-legislativo ente-id))

(defn listar-proposicoes
  "Onda B Slice 1 — leitura tenant-wide (mesmo contrato de authz de `relatores-pendentes`: sem policy fina
  adicional, so' o gate grosso da rota — papel 'secretario'). `filtro` ja vem coagido pelo adapters/in.
  `itens`/`total` vem de `listar-e-contar-proposicoes` (UMA tx do Repo, review ecc) — nunca duas leituras
  independentes que poderiam desalinhar sob escrita concorrente."
  [repo-legislativo ente-id filtro]
  (let [{:keys [itens total]} (repo/listar-e-contar-proposicoes repo-legislativo ente-id filtro)]
    {:itens itens
     :total total
     :pagina (:pagina filtro)
     :tamanho-pagina (:tamanho filtro)}))

(defn criar-proposicao
  "Onda B Slice 2 — protocola uma proposicao nova (authz: so' o gate grosso da rota, papel 'secretario',
  mesmo contrato de listar-proposicoes). `resolver-municipio` (injetado pelo host, cross-modulo p/
  cadastros) resolve {:uf :municipio-nome} do ente — precondicao de protocolar! (eixo H). Ente sem perfil
  cadastrado (resolver devolve nil) e' erro de PROVISIONAMENTO, nao de cliente: propaga sem catch (-> 500),
  nunca mascarado como 400."
  [repo-legislativo resolver-municipio ente-id m]
  (let [{:keys [uf municipio-nome]} (resolver-municipio ente-id)]
    (repo/protocolar! repo-legislativo ente-id (merge m {:uf uf :municipio-nome municipio-nome}))))

(defn buscar-proposicao-ficha
  "Onda B Slice 2 — detalhe (proposicao + texto vigente inline) p/ a tela de edicao pre-encher. nil se a
  proposicao nao existe no tenant (-> 404 na borda)."
  [repo-legislativo ente-id id]
  (let [{:keys [proposicao texto]} (repo/buscar-proposicao-detalhe repo-legislativo ente-id id)]
    (when proposicao {:proposicao proposicao :texto (:texto-inline texto)})))

(defn editar-proposicao
  "Onda B Slice 2 — edita metadados e/ou promove nova versao de texto ('edicao'). Mesmo gate grosso; `m`
  ja' vem coagido pelo adapters/in."
  [repo-legislativo ente-id m]
  (repo/editar-proposicao! repo-legislativo ente-id m))

(defn buscar-ficha-materia
  "Onda B Slice 3 — ficha completa da materia (proposicao + texto + tramitacao + apensadas + emendas +
  pareceres), mesmo gate grosso das rotas irmas (papel 'secretario', sem policy fina adicional). nil se a
  proposicao nao existe no tenant (-> 404 na borda), mesmo contrato de `buscar-proposicao-ficha`. `:texto`
  sai daqui JA extraido (:texto-inline da linha de dominio, ou nil) — mesma disciplina de
  `buscar-proposicao-ficha` (review MENOR fe-9-ficha-materia): o CONTROLLER e' quem decide o nome de campo
  do model, nunca o diplomat/http/in (que so' compoe adapters/out ja' prontos)."
  [repo-legislativo ente-id id]
  (let [{:keys [proposicao texto] :as ficha} (repo/ficha-completa-da-proposicao repo-legislativo ente-id id)]
    (when proposicao (assoc ficha :texto (:texto-inline texto)))))

;; ========================= Onda B Slice 5: editor/emissao do parecer =========================

(defn buscar-parecer-editor
  "Onda B Slice 5 — leitura agregada p/ o editor de parecer (parecer + objeto + texto rascunho/vigente),
  mesmo gate grosso das rotas irmas (papel 'secretario', sem policy fina adicional). nil se o parecer nao
  existe no tenant (-> 404 na borda), mesmo contrato de buscar-ficha-materia/buscar-proposicao-ficha."
  [repo-legislativo ente-id id]
  (repo/buscar-parecer-para-editor repo-legislativo ente-id id))

(defn salvar-rascunho-parecer
  "Onda B Slice 5 — cria uma nova versao 'rascunho' do texto do parecer. `m` ja' vem coagido pelo
  adapters/in."
  [repo-legislativo ente-id m]
  (repo/nova-versao-parecer! repo-legislativo ente-id m))

(defn emitir-parecer
  "Onda B Slice 5 — promove o rascunho a vigente (se houver) + registra o voto do relator + tenta
  transicionar (gatilho recebido, best-effort), 1 tx. `registro` (RegistroFatos do motor, injetado pelo
  host) e' o mesmo que `transicionar-parecer!` ja recebe. `m` ja' vem coagido pelo adapters/in — que ja'
  carrega o template-id do parecer (o DIPLOMAT o extrai antes de chamar o adapters/in; este controller nao
  decide template-id de outra forma)."
  [repo-legislativo registro ente-id m]
  (repo/emitir-parecer! repo-legislativo ente-id registro m))

(defn encerrar-votacao
  "Encerra a votacao `votacao-id` da sessao `sessao-id` (authz na sessao + amarra). `m` carrega o id
  (=votacao-id), lock-version, base-membros e resultado. Devolve o snapshot apurado ou nil se a votacao nao
  existe nesta sessao (-> 404). Pre-condicoes de borda viram :validacao/invalido (-> 400) usando a votacao JA
  carregada — em vez de propagarem como 500 do db: (a) votacao terminal nao reencerra; (b) modalidade
  'simbolica' (aclamacao) exige `resultado` explicito (nao apura individual).

  CARRY DE SEGURANCA (sec MEDIUM-1, F4 Slice 3): `base-membros` (denominador do quorum p/ maioria
  absoluta/qualificada) vem do CORPO do request — um secretario comprometido poderia falsear o resultado legal
  (ex.: base-membros=1 aprova tudo). Mitigacoes vivas: gate de papel 'secretario' + mesma Casa + o snapshot
  append-only grava o base_membros usado (auditavel). FIX PROPRIO (diferido, shape de F2): resolver a composicao
  da Casa SERVER-SIDE via a relacao `cadastros/membros_da_casa` (ja existe), injetada pelo host como o
  `consultar-sessao` faz — e remover `base-membros` do wire/in. Cross-modulo + data de vigencia do mandato =
  trabalho do resolvedor de fatos (§22.5.3 disc.5), nao desta fatia de borda."
  [repo-leg consultar-sessao ator sessao-id votacao-id m]
  (when (sessao-autorizada consultar-sessao ator sessao-id)
    (let [ente-id (:ente-id ator)]
      (when-let [v (votacao-na-sessao repo-leg ente-id sessao-id votacao-id)]
        (when (contains? logic/estados-votacao-terminais (:estado v))
          (throw (ex-info "votacao ja em estado terminal (encerrada/anulada)"
                          {:tipo :validacao/invalido :estado (:estado v)})))
        (when (and (= "simbolica" (:modalidade v)) (nil? (:resultado m)))
          (throw (ex-info "votacao simbolica exige resultado explicito"
                          {:tipo :validacao/invalido :campos [:resultado]})))
        (repo/encerrar-votacao! repo-leg ente-id m)))))

;; ========================= Onda B Slice 6: expediente (documentos + protocolo geral) =========================

(defn listar-modelos-documento
  "Onda B Slice 6 — modelos ATIVOS do tenant p/ o seletor da aba 'Gerar documento' (mesmo gate grosso das
  rotas irmas, papel 'secretario', sem policy fina adicional)."
  [repo-legislativo ente-id]
  (repo/listar-modelos-ativos repo-legislativo ente-id))

(defn gerar-documento
  "Onda B Slice 6 — gera um documento a partir de um modelo (feature 3.22, merge do dominio). Busca o modelo
  PRIMEIRO — `tipo-documento`/`corpo-template` vem DAI, nunca do cliente (o wire/adapters-in ja' fecha essa
  porta; este controller e' quem RESOLVE o modelo de fato). nil se o modelo nao existe no tenant (-> 404 na
  borda, mesmo contrato das leituras irmas). `m` ja' vem coagido pelo adapters/in (id/modelo-id/assunto/
  dados/created-by, com `:id` JA' gerado — o diplomat re-le o documento por esse mesmo id apos o sucesso).
  `logic/renderizar-documento` (via Repo/gerar-documento!) lanca `:validacao/invalido` (-> 400) se `dados` nao
  cobrir algum placeholder do template — nunca 500 por um formulario incompleto."
  [repo-legislativo ente-id m]
  (when-let [modelo (repo/buscar-modelo repo-legislativo ente-id (:modelo-id m))]
    (repo/gerar-documento! repo-legislativo ente-id
                           (merge m {:ente-id ente-id
                                     :tipo-documento (:tipo-documento modelo)
                                     :corpo-template (:corpo-template modelo)}))))

(defn buscar-documento-editor
  "Onda B Slice 6 — leitura agregada p/ a aba 'Gerar documento': {:documento :protocolo}. `:protocolo` vem
  ENRIQUECIDO (numero/ano) SE o documento ja' tiver `protocolo-geral-id` (pos 'Protocolar e numerar'); nil
  enquanto 'rascunho' — o editor nao faz um segundo GET so' pra mostrar o numero apos protocolar. nil
  (documento inexistente no tenant) -> 404 na borda, mesmo contrato de buscar-parecer-editor. Delega a
  `Repo/buscar-documento-para-editor` (review clojure+database MAJOR — NUMA UNICA tx, mesma disciplina de
  buscar-parecer-editor/buscar-parecer-para-editor; antes eram 2 chamadas publicas do Repo = 2 tx)."
  [repo-legislativo ente-id id]
  (repo/buscar-documento-para-editor repo-legislativo ente-id id))

(defn editar-documento
  "Onda B Slice 6 — reescreve corpo/assunto de um documento 'rascunho' (CAS). Mesmo gate grosso; `m` ja' vem
  coagido pelo adapters/in."
  [repo-legislativo ente-id m]
  (repo/editar-documento! repo-legislativo ente-id m))

(defn protocolar-documento
  "Onda B Slice 6 — o CTA 'Protocolar e numerar' do mockup: numera no Protocolo Geral (objeto-tipo
  'documento', sentido 'expedido') E emite o documento (rascunho -> emitido), 1 tx (Repo/protocolar-
  documento!). `ano` e' a data civil RESOLVIDA PELO DIPLOMAT (kernel/tempo — mesmo padrao de
  emitir-parecer/`agora`); este controller so' junta ao `m` ja' coagido pelo adapters/in (documento-id/
  lock-version/ator-id)."
  [repo-legislativo ente-id ano m]
  (repo/protocolar-documento! repo-legislativo ente-id (assoc m :ano ano)))

(defn listar-protocolo-do-ano
  "Onda B Slice 6 — o 'Livro do Protocolo Geral' (feature 3.23) do ano corrente, mesmo gate grosso. `ano`
  resolvido pelo diplomat (kernel/tempo), mesmo padrao de protocolar-documento."
  [repo-legislativo ente-id ano]
  (repo/protocolos-do-ano repo-legislativo ente-id ano))

;; ========================= Onda B Slice 7: pos-aprovacao (autografo + sancao/veto) =========================

(defn buscar-pos-aprovacao
  "Onda B Slice 7 — leitura composta {:autografo :tramitacao-executiva} da proposicao `proposicao-id`
  (mesmo gate grosso das rotas irmas, papel 'secretario', sem policy fina adicional). PRE-CHECK a
  existencia da proposicao no tenant (mesma disciplina de pre-check de editar-proposicao-handler/
  protocolar-documento-handler): sem isso, uma proposicao inexistente devolveria {:autografo nil
  :tramitacao-executiva nil} (200 vazio) em vez de 404 — Repo/buscar-pos-aprovacao NAO checa a proposicao
  por design (spec: sem short-circuit no nil do autografo; a decisao 404-vs-corpo-parcial e' deste
  controller). nil (proposicao inexistente no tenant) -> 404 na borda."
  [repo-legislativo ente-id proposicao-id]
  (when (:proposicao (repo/buscar-proposicao-detalhe repo-legislativo ente-id proposicao-id))
    (repo/buscar-pos-aprovacao repo-legislativo ente-id proposicao-id)))

(defn gerar-autografo
  "Onda B Slice 7 — gera o autografo (numera gapless) + abre a tramitacao executiva 'aguardando' NUMA
  UNICA tx (Repo/gerar-autografo-e-abrir-tramitacao!). Resolve `destinatario-texto` a partir do municipio
  do ente ('Prefeito Municipal de <municipio>', reusa `resolver-municipio` — mesmo padrao injetado de
  criar-proposicao) e `texto-versao-id` da versao VIGENTE da proposicao no momento da geracao
  (buscar-proposicao-detalhe, mesma leitura de buscar-proposicao-ficha). `ano` vem do diplomat (kernel/
  tempo, mesmo padrao de protocolar-documento — o ano do AUTOGRAFO e' o ano civil da geracao, escopo do
  numerador gapless 'autografo:ano', nao necessariamente o ano de protocolo da proposicao).

  GUARD DE DUPLICIDADE (pre-condicao de borda, mesmo racional de encerrar-votacao — vira
  :validacao/invalido usando um recurso JA carregado, em vez de propagar a excecao opaca do UNIQUE
  (ente_id, proposicao_id) do db/autografo.clj como 500): uma proposicao que ja' tem autografo lanca ANTES
  de qualquer escrita nova.

  GUARD DE TEXTO VIGENTE (achado em QA manual pos-merge): protocolar sem texto e' permitido (Onda B
  Slice 2) — uma proposicao pode chegar a 'aprovada' sem NUNCA ter tido uma versao promovida a vigente.
  O autografo e' o ARTEFATO LEGAL (nao pode nascer vazio; CHECK autografo_efetivado_tem_texto do banco
  bloquearia como 500 opaco). Guarda ANTES da escrita, mesmo racional do guard de duplicidade.

  nil se a proposicao nao existe no tenant (-> 404 na borda). `m` ja' vem coagido pelo adapters/in (id do
  autografo/prazo-resposta-em/created-by; SEM ano/destinatario-texto/texto-versao-id, injetados aqui)."
  [repo-legislativo resolver-municipio ente-id ano m]
  (let [proposicao-id (:proposicao-id m)
        {:keys [proposicao texto]} (repo/buscar-proposicao-detalhe repo-legislativo ente-id proposicao-id)]
    (when proposicao
      (when (repo/autografo-da-proposicao repo-legislativo ente-id proposicao-id)
        (throw (ex-info "gerar-autografo: a proposicao ja tem autografo (UNIQUE por proposicao)"
                        {:tipo :validacao/invalido :proposicao-id proposicao-id})))
      (when (nil? (:id texto))
        (throw (ex-info "gerar-autografo: a proposicao nao tem texto vigente (nao ha o que enviar ao Executivo)"
                        {:tipo :validacao/invalido :proposicao-id proposicao-id})))
      (let [{:keys [municipio-nome]} (resolver-municipio ente-id)]
        (repo/gerar-autografo-e-abrir-tramitacao! repo-legislativo ente-id
          (merge m {:ano ano :texto-versao-id (:id texto)
                    :destinatario-texto (str "Prefeito Municipal de " municipio-nome)}))))))

(defn buscar-tramitacao-executiva
  "Onda B Slice 7 — busca a tramitacao executiva pelo SEU PROPRIO id (mesmo gate grosso). nil se inexistente
  no tenant (-> 404 na borda)."
  [repo-legislativo ente-id id]
  (repo/buscar-tramitacao-executiva repo-legislativo ente-id id))

(defn buscar-tramitacao-por-autografo
  "Onda B Slice 7 — resolve a tramitacao executiva DO autografo `autografo-id` (mesmo gate grosso). nil se
  nao ha tramitacao executiva para este autografo no tenant (-> 404 na borda)."
  [repo-legislativo ente-id autografo-id]
  (repo/tramitacao-executiva-do-autografo repo-legislativo ente-id autografo-id))

(defn registrar-resposta-executivo
  "Onda B Slice 7 — 'Registrar retorno': POST /legislativo/autografos/:id/resposta (path :id =
  autografo-id). Resolve a tramitacao executiva DESTE autografo e registra a resposta nela (aguardando ->
  sancionado|sancao_tacita|vetado; CAS por lock-version). `m` ja' vem coagido pelo adapters/in (lock-
  version/resultado/veto-tipo/veto-razoes/updated-by); este controller injeta o `:id` (o da tramitacao
  executiva, NUNCA o path cru — o path e' o autografo). nil se nao ha tramitacao executiva para este
  autografo no tenant (-> 404 na borda, mesmo contrato de buscar-tramitacao-por-autografo)."
  [repo-legislativo ente-id autografo-id m]
  (when-let [{:keys [id]} (buscar-tramitacao-por-autografo repo-legislativo ente-id autografo-id)]
    (repo/registrar-resposta-executivo! repo-legislativo ente-id (assoc m :id id))))

(defn apreciar-veto
  "Onda B Slice 7 — carimba a apreciacao do veto pela camara: POST /legislativo/tramitacoes-executivas/:id/
  apreciacao (path :id = tramitacao-executiva-id, DIRETO — ao contrario de registrar-resposta-executivo,
  aqui nao ha' indireccao por autografo). vetado -> veto_mantido|veto_derrubado (CAS por lock-version). `m`
  ja' vem coagido pelo adapters/in (lock-version/resultado/veto-votacao-id/updated-by); este controller
  injeta o `:id` do path. nil se a tramitacao executiva nao existe no tenant (-> 404 na borda)."
  [repo-legislativo ente-id id m]
  (when (repo/buscar-tramitacao-executiva repo-legislativo ente-id id)
    (repo/apreciar-veto! repo-legislativo ente-id (assoc m :id id))))

;; ========================= Onda C1: borda /meu do vereador (home fora-de-sessao) =========================

(defn meu-painel
  "Onda C1 — leitura composta 'minhas proposicoes + meus pareceres + ciencias pendentes' do vereador ATOR
  (anti-forja: SEMPRE o vereador resolvido do proprio ator, nunca um vereador-id arbitrario do request).
  `resolver-vereador` e' a fn injetada pelo HOST (§22.5.3, exceção nomeada — o legislativo NUNCA importa
  cadastros; mesma inversao de dependencia de `consultar-sessao`/`resolver-municipio`) que resolve
  identidade-id->vereador-id NESTE ente. Um ator com papel 'vereador' mas SEM cadastro vinculado
  (`resolver-vereador` nil) devolve painel VAZIO — nao lanca: o gate grosso (exige-papel) ja' garantiu o
  papel, a ausencia de vinculo e' estado de dados, nao falha de autorizacao."
  [repo-legislativo resolver-vereador ator]
  (if-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (repo/meu-painel repo-legislativo (:ente-id ator) vereador-id)
    {:proposicoes [] :pareceres [] :ciencias []}))

(defn acusar-ciencia
  "Onda C1 — 'Dar ciencia' (Task 3): registra a ciencia do vereador ATOR sobre `evento-ref` (anti-forja:
  o `vereador-id` e' SEMPRE o resolvido do proprio ator via `resolver-vereador`, nunca um valor do corpo
  do request — mesmo contrato de `meu-painel`). `m` ja' vem coagido pelo adapters/in (`evento-ref`/`tipo`/
  `id` novo). Ator sem cadastro vinculado (`resolver-vereador` nil) -> nil (a borda traduz -> 404, mesmo
  contrato de um recurso ausente do proprio ator — nunca 500).

  GUARD DE ELEGIBILIDADE (review CRITICO clojure+database+security, 3 revisores convergentes): `evento-
  ref` PRECISA ser um parecer publicado real sobre proposicao de autoria do proprio vereador — sem este
  guard, `acusar-ciencia!` gravava qualquer UUID sintaticamente valido na prova append-only (Inv.10), sem
  jamais poder ser corrigido (a tabela e' append-only puro). `evento-ref` nao-elegivel -> nil (-> 404,
  mesmo contrato de recurso ausente — o guard NAO distingue 'nao existe' de 'nao e' seu', por design:
  vazar essa distincao revelaria a existencia de pareceres de OUTROS vereadores)."
  [repo-legislativo resolver-vereador ator m]
  (when-let [vereador-id (resolver-vereador (:ente-id ator) (:identidade-id ator))]
    (when (repo/parecer-elegivel-para-ciencia? repo-legislativo (:ente-id ator) vereador-id (:evento-ref m))
      (repo/acusar-ciencia! repo-legislativo (:ente-id ator) (assoc m :vereador-id vereador-id)))))
