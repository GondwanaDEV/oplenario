(ns oplenario.compliance.models.descritor-remessa
  "DESCRITOR declarativo de layout de remessa (§22.7.8 dec.2b) — Malli (§22.10 models/). O nó central do
  eixo: a spec de qual-campo-de-onde-em-que-formato e' DADO, nao codigo nem extensao da DSL. Versionado
  por copia integral (`spec-layout-versao`) e REUSA o registry de funcoes de relacao como FONTE dos
  valores (`[:relacao <nome>]`), mas tem seu proprio renderizador (oplenario.compliance.gerador-remessa).
  E' DOMINIO (lei uniforme do TCE, SEM ente_id) — o arquivo concreto gerado e' que e' tenant.

  Uma `:fonte` e' a proveniencia de um valor:
    [:contexto <chave>]  — do contexto da geracao (competencia, sistema, ente...);
    [:relacao  <nome>]   — funcao de relacao escalar do registry (mesma fonte da DSL de avaliacao);
  e a secao `:registros` (opcional) projeta um read-port EM LOTE (D7) — N registros de uma competencia,
  leitura em lote, NAO predicado booleano — mapeando colunas (chave-no-registro -> campo de saida).

  Layout FISICO do SIM = [GAP] de conteudo regulatorio; o descritor-fixture (no gerador) e' ilustrativo.")

(def Fonte
  "Proveniencia de um valor ESCALAR do cabecalho: contexto da geracao ou relacao escalar do registry."
  [:tuple {:title "Fonte"} [:enum :contexto :relacao] [:string {:min 1}]])

(def CampoCabecalho
  "Um campo escalar do cabecalho: nome de saida + de onde sai o valor."
  [:map {:closed true}
   [:campo [:string {:min 1}]]
   [:fonte Fonte]])

(def Coluna
  "Coluna de um registro do lote: campo de saida (`:campo`) <- chave no registro de origem (`:de`)."
  [:map {:closed true}
   [:campo [:string {:min 1}]]
   [:de [:string {:min 1}]]])

(def SecaoRegistros
  "Secao de N registros projetada de um read-port em LOTE (D7). `:fonte` = a chave do lote (ex.: 'despesas')."
  [:map {:closed true}
   [:fonte [:tuple [:enum :lote] [:string {:min 1}]]]
   [:colunas [:vector {:min 1} Coluna]]])

(def DescritorRemessa
  "O descritor declarativo de layout completo (dado versionado por copia). `:registros` e' opcional
  (uma remessa pode ser so cabecalho). `:content-type` viaja com o artefato (o serializador renderiza o
  formato fisico)."
  [:map {:closed true}
   [:spec-layout-versao [:string {:min 1}]]
   [:sistema [:string {:min 1}]]
   [:content-type [:string {:min 1}]]
   [:cabecalho [:vector {:min 1} CampoCabecalho]]
   [:registros {:optional true} SecaoRegistros]])
