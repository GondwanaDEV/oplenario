(ns participacao
  "Semente da PARTICIPACAO CIDADA da demo (plano `docs/superpowers/plans/2026-09-07-prontidao-de-
  apresentacao.md`, Task 0.6; acompanhamentos acrescentados na verificacao ao vivo de 12/09/2026) — sobre a
  Casa de `casa/semear!` e o ACERVO de `acervo/semear!`: 3 pedidos e-SIC (1 aberto no prazo, 1 respondido, 1
  respondido com recurso ABERTO) + 2 solicitacoes LGPD (1 aberta, 1 respondida) + 2 manifestacoes de
  ouvidoria (1 aberta, 1 respondida) + 5 comentarios em materias REAIS do acervo (3 aprovados, 2 na fila de
  moderacao — a fila NAO pode ficar vazia, e' o que se mostra ao servidor) + 3 acompanhamentos em materias
  REAIS do acervo (2 NAO-terminais + 1 terminal, ver `proposicoes-para-acompanhar`) + 1 Encarregado/DPO. Usa
  SO o Repo-Component REAL de cada modulo (`RepoParticipacao`, ja' booted em `sistema` —
  `(:repo-participacao sistema)`; `RepoTransparencia`, `(:repo-transparencia sistema)`, so' para os
  acompanhamentos — `transparencia.acompanhamento` e' TABELA DE DOMINIO do cidadao, nao projecao) +
  `(:repo-legislativo sistema)` so' para ler proposicoes reais do acervo (nenhum SELECT cru); nenhuma DSL
  nova.

  VOCABULARIO — lido da FONTE, nao de memoria (regra dura do briefing da Task 0.6):
  - `participacao.pedido_esic.estado` ∈ `protocolado · em_analise · respondido · indeferido` (migration
    `20260620000039-participacao-esic-pedido.up.sql:29-30`, espelhado em `participacao.logic/estados-pedido`
    linha 17); trigger `imut_trava_estado_terminal` trava o terminal em `respondido`/`indeferido` (mesma
    migration:69). Os rotulos 'aberto'/'em_recurso' do texto do plano SAO DE UI, nao estados de banco (ver
    correcao no teste, `oplenario.demo.participacao-test`) — 'aberto no prazo' e' `protocolado`; 'em recurso'
    e' um pedido `respondido` com um `recurso_esic` (ENTIDADE SEPARADA) aberto pendurado nele.
  - `participacao.recurso_esic.estado` ∈ `protocolado · decidido` (migration
    `20260620000040-participacao-esic-recurso-resposta.up.sql:149`, `logic/estados-recurso` linha 47) —
    DISTINTO de `prazo_ativo.estado` (ver abaixo), que e' o vocabulario que o briefing desta sessao citou por
    engano para 'recurso'.
  - `participacao.prazo_ativo.estado` ∈ `pendente · cumprida · vencida · dispensada · cancelada` (mesma
    migration 39:81-82, `logic/estados-prazo` linha 30) — o relogio, nao o pedido/recurso.
  - `participacao.solicitacao_titular.estado` ∈ `protocolada · em_analise · respondida · indeferida`
    (migration `20260620000041-participacao-lgpd.up.sql:291-292`, `logic/estados-solicitacao-titular`).
  - `participacao.manifestacao_ouvidoria.estado` ∈ `protocolada · em_analise · respondida · arquivada`
    (migration `20260620000042-participacao-ouvidoria.up.sql:540-541`, `logic/estados-manifestacao`).
  - `participacao.comentario.estado` ∈ `pendente · aprovado · rejeitado`; `moderacao_comentario.acao` ∈
    `aprovado · rejeitado` (migration `20260620000043-participacao-comentarios.up.sql:657,725`,
    `logic/estados-comentario`).

  IDENTIDADES: `casa/semear!` e' o UNICO produtor do mapa `:identidades` (cidadao/secretaria/presidente/
  vereador) — os CPFs fixos que o gera sao `^:private` ao ns `casa`. Este ns chama `(casa/semear! sistema)`
  de novo para obter esse mapa: e' SEGURO (idempotente por desenho, mesmo padrao que `casa.clj` documenta —
  'Toda semente posterior le esse mapa') e nao duplica nada (o bloco cadastral so' roda na 1a chamada, atras
  do gate `ja-semeada?`). O `ente` recebido como parametro (nao o `:ente` da rechamada) e' quem escopa TODA
  escrita deste ns — os dois sao sempre o mesmo UUID fixo (`casa/ente-id`), so' evitamos o acoplamento a essa
  constante privada.

  DEPENDE do ACERVO (`acervo/semear!`) ja' ter rodado: os 5 comentarios apontam para proposicoes REAIS do
  acervo, lidas via `(repo-leg/listar-e-contar-proposicoes ...)` (a MESMA leitura paginada do FE). Falha alto
  (ex-info) se o acervo nao foi semeado — este ns nao encadeia `acervo/semear!` (mesmo desenho de
  `sessoes.clj`, que tambem so' assume o acervo, nunca o recria)."
  (:require [casa]
            [oplenario.legislativo.components.repositorio :as repo-leg]
            [oplenario.participacao.components.repositorio :as repo]
            [oplenario.participacao.logic :as plogic]
            [oplenario.transparencia.components.repositorio :as transparencia-repo])
  (:import (java.time Instant LocalDate ZoneId)))

;; ---------- constantes (UUIDs FIXOS — re-executavel, mesmo racional de `casa/ente-id`/`sessoes/id-*`) ----------

(def ^:private id-pedido-aberto     #uuid "10000000-0000-0000-0000-000000000401")
(def ^:private id-pedido-respondido #uuid "10000000-0000-0000-0000-000000000402")
(def ^:private id-pedido-recorrido  #uuid "10000000-0000-0000-0000-000000000403")
(def ^:private id-recurso-recorrido #uuid "10000000-0000-0000-0000-000000000404")
(def ^:private id-solicitacao-1     #uuid "10000000-0000-0000-0000-000000000410")
(def ^:private id-solicitacao-2     #uuid "10000000-0000-0000-0000-000000000411")
(def ^:private id-manifestacao-1    #uuid "10000000-0000-0000-0000-000000000420")
(def ^:private id-manifestacao-2    #uuid "10000000-0000-0000-0000-000000000421")
(def ^:private ids-comentarios
  [#uuid "10000000-0000-0000-0000-000000000430" #uuid "10000000-0000-0000-0000-000000000431"
   #uuid "10000000-0000-0000-0000-000000000432" #uuid "10000000-0000-0000-0000-000000000433"
   #uuid "10000000-0000-0000-0000-000000000434"])
(def ^:private id-encarregado       #uuid "10000000-0000-0000-0000-000000000440")
(def ^:private ids-acompanhamentos
  [#uuid "10000000-0000-0000-0000-000000000450" #uuid "10000000-0000-0000-0000-000000000451"
   #uuid "10000000-0000-0000-0000-000000000452"])

(def ^:private zona
  "Mesmo fuso civil de `participacao.controllers/zona-civil` (America/Fortaleza — beachhead NE): os prazos
  legais (LAI/LGPD/13.460) correm por fuso, nao UTC."
  (ZoneId/of "America/Fortaleza"))

(def ^:private prazo-fonte-lai "LAI 12.527/2011 art. 11 §1º (20 dias)")
(def ^:private prazo-fonte-recurso "LAI 12.527/2011 (recurso; prazo da autoridade superior)")
(def ^:private prazo-fonte-titular "LGPD 13.709/2018 art. 18/19 (prazo do titular)")
(def ^:private prazo-fonte-ouvidoria "Lei 13.460/2017 art. 10 (30 dias, prorrogavel)")

;; ---------- e-SIC: 3 pedidos (aberto no prazo · respondido · respondido+recurso aberto) ----------

(defn- semear-esic!
  [repo ente cidadao-id secretaria-id]
  ;; 1 — aberto no prazo (protocolado, sem resposta ainda)
  (let [hoje (LocalDate/now zona)]
    (repo/protocolar-pedido! repo ente
      {:id id-pedido-aberto :ano (.getYear hoje)
       :assunto "Despesas com diárias no exercício de 2026"
       :descricao "Solicito cópia da planilha de despesas com diárias de vereadores e servidores no exercício de 2026, incluindo valores e destinos de cada viagem."
       :solicitante-identidade-id cidadao-id :recibo-em (Instant/now) :vence-em (plogic/vence-em hoje)
       :prazo-id (random-uuid) :base-dias plogic/dias-lai-esic :prazo-fonte-ref prazo-fonte-lai
       :created-by cidadao-id}))
  ;; 2 — respondido
  (let [hoje (LocalDate/now zona)]
    (repo/protocolar-pedido! repo ente
      {:id id-pedido-respondido :ano (.getYear hoje)
       :assunto "Cópia das atas das sessões de junho de 2026"
       :descricao "Solicito cópia digital das atas das sessões ordinárias realizadas no mês de junho de 2026."
       :solicitante-identidade-id cidadao-id :recibo-em (Instant/now) :vence-em (plogic/vence-em hoje)
       :prazo-id (random-uuid) :base-dias plogic/dias-lai-esic :prazo-fonte-ref prazo-fonte-lai
       :created-by cidadao-id})
    (repo/responder-pedido! repo ente
      {:pedido-id id-pedido-respondido :resposta-id (random-uuid)
       :corpo "Em atenção ao pedido protocolado, encaminhamos em anexo as atas das sessões ordinárias de junho de 2026, também disponíveis no Portal da Transparência desta Casa."
       :respondido-por secretaria-id :respondida-em (Instant/now)}))
  ;; 3 — respondido, com recurso interposto e AINDA nao decidido ('em recurso', rotulo de UI)
  (let [hoje (LocalDate/now zona)]
    (repo/protocolar-pedido! repo ente
      {:id id-pedido-recorrido :ano (.getYear hoje)
       :assunto "Relação de contratos de prestação de serviços vigentes em 2026"
       :descricao "Solicito relação de todos os contratos de prestação de serviços vigentes nesta Casa Legislativa em 2026, com valores e fornecedores."
       :solicitante-identidade-id cidadao-id :recibo-em (Instant/now) :vence-em (plogic/vence-em hoje)
       :prazo-id (random-uuid) :base-dias plogic/dias-lai-esic :prazo-fonte-ref prazo-fonte-lai
       :created-by cidadao-id})
    (repo/responder-pedido! repo ente
      {:pedido-id id-pedido-recorrido :resposta-id (random-uuid)
       :corpo "Em atenção ao pedido protocolado, informamos que os contratos vigentes estão disponíveis no Portal da Transparência, seção 'Contratos'."
       :respondido-por secretaria-id :respondida-em (Instant/now)})
    (let [hoje-rec (LocalDate/now zona)]
      (repo/interpor-recurso! repo ente
        {:recurso-id id-recurso-recorrido :pedido-id id-pedido-recorrido :ano (.getYear hoje-rec) :instancia 1
         :motivo "A resposta não indicou os valores individuais por contrato, apenas o link genérico do portal. Solicito a relação detalhada, conforme requerido inicialmente."
         :recibo-em (Instant/now) :vence-em (plogic/vence-em-recurso hoje-rec) :prazo-id (random-uuid)
         :base-dias plogic/dias-recurso-esic :prazo-fonte-ref prazo-fonte-recurso :created-by cidadao-id}))))

(defn- ler-esic
  [repo ente]
  [(select-keys (repo/buscar-pedido repo ente id-pedido-aberto) [:id :protocolo :estado])
   (select-keys (repo/buscar-pedido repo ente id-pedido-respondido) [:id :protocolo :estado])
   (assoc (select-keys (repo/buscar-pedido repo ente id-pedido-recorrido) [:id :protocolo :estado])
          :recurso (select-keys (repo/buscar-recurso repo ente id-recurso-recorrido) [:id :protocolo :estado]))])

;; ---------- LGPD: 2 solicitacoes do titular (aberta · respondida) ----------

(defn- semear-lgpd!
  [repo ente cidadao-id secretaria-id]
  (let [hoje (LocalDate/now zona)]
    (repo/solicitar-titular! repo ente
      {:id id-solicitacao-1 :ano (.getYear hoje) :tipo "acessar"
       :detalhe "Solicito acesso aos dados pessoais tratados por esta Casa no meu cadastro de cidadão participante."
       :titular-identidade-id cidadao-id :recibo-em (Instant/now) :vence-em (plogic/vence-em-titular hoje)
       :prazo-id (random-uuid) :base-dias plogic/dias-titular :prazo-fonte-ref prazo-fonte-titular
       :created-by cidadao-id}))
  (let [hoje (LocalDate/now zona)]
    (repo/solicitar-titular! repo ente
      {:id id-solicitacao-2 :ano (.getYear hoje) :tipo "corrigir"
       :detalhe "Solicito correção do meu endereço de e-mail cadastrado no Portal do Cidadão."
       :titular-identidade-id cidadao-id :recibo-em (Instant/now) :vence-em (plogic/vence-em-titular hoje)
       :prazo-id (random-uuid) :base-dias plogic/dias-titular :prazo-fonte-ref prazo-fonte-titular
       :created-by cidadao-id})
    (repo/responder-solicitacao! repo ente
      {:solicitacao-id id-solicitacao-2 :resposta-id (random-uuid)
       :corpo "Confirmamos a atualização do endereço de e-mail em seu cadastro, conforme solicitado."
       :respondido-por secretaria-id :respondida-em (Instant/now)})))

(defn- ler-lgpd
  [repo ente]
  [(select-keys (repo/buscar-solicitacao-titular repo ente id-solicitacao-1) [:id :protocolo :estado])
   (select-keys (repo/buscar-solicitacao-titular repo ente id-solicitacao-2) [:id :protocolo :estado])])

;; ---------- Ouvidoria: 2 manifestacoes (aberta · respondida) ----------

(defn- semear-ouvidoria!
  [repo ente cidadao-id secretaria-id]
  (let [hoje (LocalDate/now zona)]
    (repo/protocolar-manifestacao! repo ente
      {:id id-manifestacao-1 :ano (.getYear hoje) :tipo "reclamacao"
       :assunto "Demora no atendimento da Central de Atendimento ao Cidadão"
       :descricao "Relato dificuldade para obter retorno da Central de Atendimento sobre solicitação de agendamento com vereador."
       :anonima false :manifestante-identidade-id cidadao-id :recibo-em (Instant/now)
       :vence-em (plogic/vence-em-ouvidoria hoje) :prazo-id (random-uuid) :base-dias plogic/dias-ouvidoria
       :prazo-fonte-ref prazo-fonte-ouvidoria :created-by cidadao-id}))
  (let [hoje (LocalDate/now zona)]
    (repo/protocolar-manifestacao! repo ente
      {:id id-manifestacao-2 :ano (.getYear hoje) :tipo "elogio"
       :assunto "Elogio ao atendimento da Ouvidoria"
       :descricao "Registro elogio pela cordialidade e agilidade no atendimento prestado pela equipe da Ouvidoria desta Casa."
       :anonima false :manifestante-identidade-id cidadao-id :recibo-em (Instant/now)
       :vence-em (plogic/vence-em-ouvidoria hoje) :prazo-id (random-uuid) :base-dias plogic/dias-ouvidoria
       :prazo-fonte-ref prazo-fonte-ouvidoria :created-by cidadao-id})
    (repo/responder-manifestacao! repo ente
      {:manifestacao-id id-manifestacao-2 :resposta-id (random-uuid)
       :corpo "Agradecemos o retorno positivo e repassaremos o elogio à equipe responsável."
       :respondido-por secretaria-id :respondida-em (Instant/now)})))

(defn- ler-ouvidoria
  [repo ente]
  [(select-keys (repo/buscar-manifestacao repo ente id-manifestacao-1) [:id :protocolo :estado])
   (select-keys (repo/buscar-manifestacao repo ente id-manifestacao-2) [:id :protocolo :estado])])

;; ---------- Comentarios: 5 em materias REAIS do acervo (3 aprovados, 2 na fila de moderacao) ----------

(def ^:private textos-comentarios
  "Assuntos plausiveis de cidadao comentando materias de camara municipal — nada de 'teste 1'. Os 2 ultimos
  ficam PENDENTES DE PROPOSITO (nao chamam `moderar-comentario!`): e' a fila de moderacao que a Task 0.6
  exige nao-vazia — o que se mostra ao servidor."
  [{:corpo "Apoio integralmente essa proposta, é uma pauta importante para o bairro." :aprovar? true}
   {:corpo "Excelente iniciativa, espero que seja aprovada rapidamente." :aprovar? true}
   {:corpo "Concordo com o texto, mas sugiro incluir também as praças do Centro." :aprovar? true}
   {:corpo "Discordo totalmente, isso vai gerar mais gastos desnecessários! Um absurdo." :aprovar? false}
   {:corpo "Quero saber quando essa lei vai valer para o meu bairro também." :aprovar? false}])

(defn- proposicoes-para-comentar
  "5 `id` de proposicoes REAIS do acervo (`acervo/semear!`, Task 0.4) — via a MESMA leitura paginada do FE
  (`listar-e-contar-proposicoes`), nenhum SELECT cru. Falha alto se o acervo nao foi semeado: este ns depende
  dele, nao o recria (mesmo desenho de `sessoes.clj/materias-em-pauta`)."
  [repo-legislativo ente]
  (let [itens (:itens (repo-leg/listar-e-contar-proposicoes repo-legislativo ente {:pagina 1 :tamanho 200}))]
    (when (< (count itens) 5)
      (throw (ex-info (str "participacao/semear!: precisa de >=5 proposicoes do acervo (Task 0.4) — "
                           "rode acervo/semear! primeiro")
                      {:encontradas (count itens)})))
    (mapv :id (take 5 itens))))

(defn- semear-comentarios!
  [repo repo-legislativo ente cidadao-id secretaria-id]
  (let [proposicoes (proposicoes-para-comentar repo-legislativo ente)]
    (doseq [[id proposicao-id {:keys [corpo aprovar?]}] (map vector ids-comentarios proposicoes textos-comentarios)]
      (repo/comentar! repo ente
        {:id id :proposicao-id proposicao-id :autor-identidade-id cidadao-id :corpo corpo :created-by cidadao-id})
      (when aprovar?
        (repo/moderar-comentario! repo ente
          {:id id :acao "aprovado" :motivo-rejeicao nil :moderacao-id (random-uuid)
           :moderado-por secretaria-id :moderado-em (Instant/now)})))))

(defn- ler-comentarios
  [repo ente]
  (mapv #(select-keys (repo/buscar-comentario repo ente %) [:id :estado]) ids-comentarios))

;; ---------- Acompanhamentos: a cidada segue 3 materias REAIS do acervo (2 nao-terminais, 1 terminal) ----------
;; Achado da verificacao AO VIVO (Daouda, 12/09/2026): a cidada alcancava a superficie autenticada mas
;; `GET /portal/acompanhamentos` devolvia `{"acompanhamentos":[],"acompanhamentos-total":0}` — nenhuma das 4
;; sementes narrativas criava acompanhamento nenhum. `transparencia.acompanhamento` e' TABELA DE DOMINIO (nao
;; projecao — a coluna do dono e' `seguidor_identidade_id`, NAO `identidade_id`), escrita so' via o
;; Repo-Component real do modulo (`RepoTransparenciaPg/seguir!`), nunca INSERT cru.

(defn- proposicoes-para-acompanhar
  "3 proposicoes REAIS do acervo p/ a cidada seguir. Pelo menos 1 tem de ser NAO-terminal (protocolada/
  em_comissoes/aguardando_pauta/em_pauta) — o fan-out de notificacao
  (`oplenario.transparencia.diplomat.consumers/tipos-fan-out`) so' reage a `proposicao.transicionou`, e uma
  proposicao 'aprovada'/'arquivada' e' TERMINAL: nunca mais transiciona, entao um follow so' nela nunca
  teria FUTURO nenhum por este canal (a norma/autografo dela segue um ciclo PROPRIO, fora deste fan-out).

  Escolha: o PRIMEIRO item que `listar-e-contar-proposicoes` devolve p/ cada um dos 3 filtros de estado
  abaixo — 'em_pauta'/'em_comissoes' (NAO-terminais) + 'aprovada' (TERMINAL de proposito, p/ mostrar o
  contraste: um follow que NAO produzira' mais notificacao por este canal). 'Primeiro' e' a MESMA
  ordenacao que a rota real usa (`atualizado_em DESC, id ASC` — default de `legislativo.db.proposicao/
  listar` quando `ordenar-por` esta ausente, NAO a ordem de insercao — verificado contra a Casa semeada de
  verdade, nao suposto). A identidade exata (ementa/estado) de cada item e' o que
  `oplenario.demo.participacao-test` afirma — nao redigitada aqui como comentario que pode driftar.
  Falha alto se o acervo nao tiver as 3 categorias."
  [repo-legislativo ente]
  (letfn [(por-estado [estado]
            (:itens (repo-leg/listar-e-contar-proposicoes repo-legislativo ente
                      {:pagina 1 :tamanho 10 :estado estado})))]
    (let [em-pauta (por-estado "em_pauta")
          em-comissoes (por-estado "em_comissoes")
          aprovada (por-estado "aprovada")]
      (when (or (empty? em-pauta) (empty? em-comissoes) (empty? aprovada))
        (throw (ex-info (str "participacao/semear!: acervo incompleto p/ semear acompanhamentos — "
                             "rode acervo/semear! primeiro")
                        {:em-pauta (count em-pauta) :em-comissoes (count em-comissoes) :aprovada (count aprovada)})))
      [(first em-pauta) (first em-comissoes) (first aprovada)])))

(defn- semear-acompanhamentos!
  [repo-transparencia repo-legislativo ente cidadao-id]
  (doseq [[id proposicao] (map vector ids-acompanhamentos (proposicoes-para-acompanhar repo-legislativo ente))]
    (transparencia-repo/seguir! repo-transparencia ente
      {:id id :proposicao-id (:id proposicao) :seguidor-identidade-id cidadao-id :created-by cidadao-id})))

(defn- ler-acompanhamentos
  "Le de volta pela MESMA leitura que `GET /portal/acompanhamentos` usa (`meus-acompanhamentos` —
  RepoTransparencia), nunca um SELECT cru. Devolve `{:acompanhamentos :acompanhamentos-total}`, identico
  a' forma da API (item pode vir com `:indisponivel true` se a projecao do cabecalho da materia ainda nao
  chegou — a subscricao em si, VERDADE de dominio, nunca falta)."
  [repo-transparencia ente cidadao-id]
  (transparencia-repo/meus-acompanhamentos repo-transparencia ente cidadao-id))

;; ---------- Encarregado/DPO (config-like — upsert e' idempotente por ente_id, sem gate proprio) ----------

(defn- semear-encarregado!
  [repo ente secretaria-id]
  (repo/definir-encarregado! repo ente
    {:id id-encarregado :nome "Camila Andrade Ribeiro" :rotulo "Encarregada de Dados (DPO)"
     :email "protecaodedados@cmfortaleza.ce.gov.br" :atualizado-por secretaria-id}))

;; ---------- a funcao publica ----------

(defn semear!
  "Semeia (ou rele, se ja' semeada) a PARTICIPACAO CIDADA da Casa `ente`. `sistema` e' um sistema Component
  BOOTADO (mesmo contrato de `casa/semear!`/`acervo/semear!`/`sessoes/semear!`) — usa
  `(:repo-participacao sistema)` + `(:repo-legislativo sistema)` (so' leitura de proposicoes) + chama
  `(casa/semear! sistema)` de novo (idempotente por desenho) so' para obter o mapa `:identidades`.

  IDEMPOTENCIA: os UUIDs sao FIXOS — o gate e' a existencia do 1º pedido e-SIC (`id-pedido-aberto`). Se ja'
  existe, RELE (mesma leitura que o 1º semeio devolveria) em vez de re-tentar protocolar (que colidiria na
  UNIQUE de numeracao) ou re-responder um pedido ja' terminal (CAS silenciosamente ignora, deixando o
  Encarregado e a fila de moderacao incompletos se so' parte re-rodasse).

  `semear-acompanhamentos!` fica DE PROPOSITO FORA desse gate — ACHADO REAL, nao hipotetico: a Casa da
  demo ja' tinha `id-pedido-aberto` (semeada antes desta fatia existir) quando `seguir!` foi acrescentado
  aqui; se `semear-acompanhamentos!` morasse dentro do `when-not`, o gate (que so' olha o e-SIC) julgaria
  'ja' semeado' e a nova fatia NUNCA rodaria numa Casa existente — silenciosamente, sem erro nenhum.
  `transparencia-repo/seguir!` e' UPSERT (idempotente por `(ente,proposicao,seguidor)`), entao chamar
  sempre e' seguro E e' o unico jeito de uma fatia NOVA alcancar uma Casa ja' semeada por uma versao
  ANTERIOR desta funcao — mesma licao que motivou o design idempotente de `vinc/criar!`/`id/inserir!`.

  Devolve `{:esic :lgpd :ouvidoria :comentarios :moderacao-pendente :encarregado :acompanhamentos}`."
  [sistema ente]
  (let [repo (:repo-participacao sistema)
        repo-legislativo (:repo-legislativo sistema)
        repo-transparencia (:repo-transparencia sistema)
        {:keys [identidades]} (casa/semear! sistema)
        {:keys [cidadao secretaria]} identidades]
    (when-not (repo/buscar-pedido repo ente id-pedido-aberto)
      (semear-esic! repo ente cidadao secretaria)
      (semear-lgpd! repo ente cidadao secretaria)
      (semear-ouvidoria! repo ente cidadao secretaria)
      (semear-comentarios! repo repo-legislativo ente cidadao secretaria)
      (semear-encarregado! repo ente secretaria))
    (semear-acompanhamentos! repo-transparencia repo-legislativo ente cidadao)
    {:esic (ler-esic repo ente)
     :lgpd (ler-lgpd repo ente)
     :ouvidoria (ler-ouvidoria repo ente)
     :comentarios (ler-comentarios repo ente)
     :moderacao-pendente (repo/fila-moderacao repo ente)
     :encarregado (repo/buscar-encarregado repo ente)
     :acompanhamentos (ler-acompanhamentos repo-transparencia ente cidadao)}))
