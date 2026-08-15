(ns oplenario.sessoes.gerador-folha
  "O renderizador PROPRIO da FOLHA DA SESSAO (§22.6 eixo C, Etapa 5 fatia 1, D1/D2/D6) — irmao de
  `compliance/gerador-remessa` (mesmo padrao: PURO, le' valores JA RESOLVIDOS pelo controller e projeta um
  DOCUMENTO INTERMEDIARIO formato-agnostico; a serializacao fisica — HTML canonico na Fatia 2, PDF na
  Fatia 3 — e' de outro port). Aqui NAO ha banco nem I/O, so' a projecao + as validacoes fail-closed
  PROPRIAS do documento (D6: 'nao_realizada' sem motivo e' documento incompleto).

  D1 por CONSTRUCAO: este ns nao importa `sessoes.logic` e nao deriva estado nem conta quorum — `:linhas`
  e `:quorum` chegam PRONTOS do controller (`chamada-da-sessao*`, que ja' roda
  `logic/derivar-linhas-da-chamada` + `logic/contar-quorum`). Se este ns importasse `logic`, a tentacao de
  recalcular aqui dentro reabriria a porta que a Etapa 1 fechou (a TERCEIRA aritmetica de quorum)."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def spec-versao
  "A versao do SPEC do documento — carimbada em todo documento produzido por `renderizar`. Etapa 5 fatia 1
  e' a v1; um formato incompativel (campo removido/renomeado, nao so' adicionado) exige v2, nunca reescrever
  esta constante."
  "folha-sessao-v1")

(defn- exigir! [v campo]
  (when (nil? v)
    (throw (ex-info (str "folha: " (name campo) " ausente — documento incompleto")
                    {:tipo :servidor/erro :campo campo}))))

(defn renderizar
  "PURO. Projeta `dados` (tudo JA RESOLVIDO pelo controller: {:sessao :instante :cabecalho-da-casa :linhas
  :quorum :serie :justificativas :atos-de-chamada-conduzida}) no DOCUMENTO da folha
  ({:spec-versao :sessao :instante :cabecalho-da-casa :linhas :quorum :serie :justificativas
  :atos-de-chamada-conduzida}).

  Fail-closed (D6), duas formas:
    1. campo estrutural ausente (`:sessao`/`:instante`/`:linhas`/`:quorum`/`:cabecalho-da-casa` nil) — o
       controller ja' garante 'so' sessao fechada chega aqui' ANTES de chamar `renderizar`; este guard e' o
       cinto de seguranca do proprio documento, para nunca sair um artefato com metade dos campos por um
       bug de composicao no chamador.
    2. `:sessao :estado` = 'nao_realizada' SEM `:motivo-nao-realizada` (nao-branco) — uma folha que nao
       explica por que a sessao nao aconteceu e' documento incompleto. O CHECK da migration 0026 so'
       garante o motivo no INSTANTE da transicao; nada impede o dado de chegar aqui faltando (fixture
       pobre, migracao de acervo legado) — e' esse o caso que este guard fecha.

  `:serie`/`:justificativas`/`:atos-de-chamada-conduzida` default para vazio ({}/[]/[]) quando `nil`: uma
  sessao fechada sem NENHUM evento de presenca (`sem-registro-de-presenca` — o mesmo caso que a chamada ja'
  distingue) e' um documento LEGITIMO, nao um erro de composicao."
  [{:keys [sessao instante cabecalho-da-casa linhas quorum serie justificativas atos-de-chamada-conduzida]}]
  (exigir! sessao :sessao)
  (exigir! instante :instante)
  (exigir! cabecalho-da-casa :cabecalho-da-casa)
  (exigir! linhas :linhas)
  (exigir! quorum :quorum)
  (when (and (= "nao_realizada" (:estado sessao))
             (str/blank? (:motivo-nao-realizada sessao)))
    (throw (ex-info "folha: sessao nao_realizada sem motivo — documento incompleto"
                    {:tipo :servidor/erro :sessao-id (:id sessao)})))
  {:spec-versao spec-versao
   :sessao sessao
   :instante instante
   :cabecalho-da-casa cabecalho-da-casa
   :linhas linhas
   :quorum quorum
   :serie (or serie {})
   :justificativas (vec justificativas)
   :atos-de-chamada-conduzida (vec atos-de-chamada-conduzida)})
