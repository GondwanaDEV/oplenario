(ns oplenario.transparencia.controllers
  "Orquestracao IMPURA do portal (§22.10 controllers, ADR-0001) — coordena o Repo-Component. As rotas do
  Slice 1 (listar/ficha materia+legislacao) sao PUBLICAS (sem ator). As do Slice 2 (acompanhamento) sao
  AUTENTICADAS (cidadao): ente-id + seguidor vem do ATOR (nunca do corpo/path — anti-forge). Slice 4b: o
  download do artefato le' o binario do objeto_store (I/O de blob fica no controller, nao no Repo — o Repo so'
  resolve o PONTEIRO)."
  (:require [clojure.tools.logging :as log]
            [oplenario.kernel.components.objeto-store :as os]
            [oplenario.kernel.ids :as ids]
            [oplenario.transparencia.components.repositorio :as repo]))

(defn listar-materias
  "Portal: materias em tramitacao (sem exclusao de estado nesta fatia — lista tudo, mais recente primeiro)."
  [repo-transparencia ente-id]
  (repo/listar-materias repo-transparencia ente-id #{}))

(defn ficha-materia
  "A ficha PUBLICA de uma materia — a materia + a norma publicada, se houver (liga 'proposicao -> lei').
  Devolve nil se a materia nao existe no portal (proposicao nunca protocolada, ou tenant errado)."
  [repo-transparencia ente-id proposicao-id]
  (when-let [m (repo/buscar-materia repo-transparencia ente-id proposicao-id)]
    (assoc m :norma (repo/norma-da-materia repo-transparencia ente-id proposicao-id))))

(defn listar-normas
  "Portal: acervo de legislacao as-enacted (F6c Slice 3). `filtro` = {:tipo :ano :numero} (todos opcionais,
  ja' coagidos na borda). Sem filtro: mais recente primeiro (compat Slice 1)."
  [repo-transparencia ente-id filtro]
  (repo/listar-normas repo-transparencia ente-id filtro))

(defn buscar-norma
  "Uma norma publicada especifica, ou nil."
  [repo-transparencia ente-id norma-id]
  (repo/buscar-norma repo-transparencia ente-id norma-id))

(defn baixar-artefato-da-norma
  "Resolve o artefato de publicacao MAIS RECENTE de uma norma e le' o binario do objeto_store. Discrimina 3
  desfechos p/ a borda (nunca 404 silencioso sobre um documento OFICIAL):
   - `:nao-encontrado` (sem ponteiro): a norma nao tem artefato gerado -> 404.
   - `:blob-ausente` (ponteiro EXISTE mas obter->nil): a ANCORA-antes-do-blob (mig 0046/0047) — a linha foi
     inserida mas o objeto_store falhou APOS o commit no legislativo. E' condicao de ALERTA (log/error +
     500), NUNCA 404 (o cidadao nao pode receber 'nao existe' sobre um ato que foi publicado) nem servir
     lixo. O reconciliador F7 (rascunho-sem-blob) atua na fonte (legislativo).
   - `:ok`: bytes + content-type + versao p/ a resposta binaria.
  O I/O de blob mora AQUI (controller impuro), nao no Repo (que so' resolve o ponteiro)."
  [repo-transparencia objeto-store ente-id norma-id]
  (if-let [ptr (repo/artefato-mais-recente-da-norma repo-transparencia ente-id norma-id)]
    (if-let [b (os/obter objeto-store (:objeto-store-ref ptr))]
      {:resultado :ok :bytes b :content-type (:content-type ptr) :versao (:versao ptr)}
      (do (log/error "transparencia: artefato de publicacao com ponteiro mas SEM blob no objeto_store"
                     {:evento :artefato-sem-blob :ente-id ente-id :norma-id norma-id
                      :artefato-id (:artefato-id ptr) :objeto-store-ref (:objeto-store-ref ptr)})
          {:resultado :blob-ausente}))
    {:resultado :nao-encontrado}))

(defn perfil-parlamentar
  "Perfil PUBLICO do vereador no read-model (Onda E fatia 2): {:materias :normas-de-autoria :votos
  :presenca}, numa UNICA tx (ver o metodo homonimo do Repo). NAO inclui a identidade (nome/mandato/
  comissoes) — essa chega na BORDA, injetada pelo host sobre o Repo de `cadastros` (§22.10: `transparencia`
  nunca importa outro modulo de dominio). Rota PUBLICA: sem ator, `ente-id` resolvido do path publico."
  [repo-transparencia ente-id vereador-id]
  (repo/perfil-parlamentar repo-transparencia ente-id vereador-id))

;; ---------- Slice 2: acompanhamento do cidadao (autenticado; consent-gated) ----------

(defn seguir!
  "CIDADAO segue a `proposicao-id`. GUARD: so' se a materia existe no read-model (nao se segue UUID solto nem
  materia nao publicada) — ausente -> nil (borda -> 404). ente-id + seguidor INJETADOS do ator. UPSERT
  (re-seguir reativa). O ato de seguir E' o consentimento de ser notificado (§22.5). Devolve {:estado ...} ou nil."
  [repo-transparencia ator proposicao-id]
  (let [ente-id (:ente-id ator)]
    (when (repo/buscar-materia repo-transparencia ente-id proposicao-id)
      (repo/seguir! repo-transparencia ente-id
        {:id (ids/novo-id) :proposicao-id proposicao-id
         :seguidor-identidade-id (:identidade-id ator) :created-by (:identidade-id ator)}))))

(defn deixar-de-seguir!
  "CIDADAO deixa de seguir a `proposicao-id` (soft-cancel idempotente — retira o consentimento). ente-id +
  seguidor do ator. Sem guard de existencia da materia (deixar de seguir e' sempre seguro; no-op se nao seguia)."
  [repo-transparencia ator proposicao-id]
  (repo/deixar-de-seguir! repo-transparencia (:ente-id ator)
    {:proposicao-id proposicao-id :seguidor-identidade-id (:identidade-id ator)}))

(defn meus-acompanhamentos
  "'minhas materias acompanhadas' do cidadao autenticado — escopo pelo seguidor do ATOR (nunca ve as de
  outro; sem :id, sem policy fina necessaria — a query ja filtra por seguidor)."
  [repo-transparencia ator]
  (repo/meus-acompanhamentos repo-transparencia (:ente-id ator) (:identidade-id ator)))
