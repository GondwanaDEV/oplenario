(ns oplenario.compliance.components.fontes
  "Port de SAIDA FontesRemessa (§22.7.8 D7): read-ports EM LOTE p/ a proveniencia dos campos da remessa. A
  remessa carrega dados de OUTROS modulos (despesas->transparencia, atos->legislativo, cadastro->cadastros);
  coletar N registros de uma competencia e' **leitura em lote**, NAO predicado booleano de avaliacao — exige
  um tipo de read-port DISTINTO das funcoes-de-relacao-para-avaliacao (que reduzem a booleano). Reusa o
  padrao, NOMEIA o limite. A impl de producao = HTTP client por modulo (port->http_client, §22.10); NUNCA
  JOIN cross-schema. O adapter `fontes-fixture` (in-memory) serve a forma/testes ate as fontes reais chegarem
  ([GAP]: quais campos vem de onde e' conteudo regulatorio do SIM)."
  )

(set! *warn-on-reflection* true)

(defprotocol FontesRemessa
  (buscar-lote [this ente-id chave-lote contexto]
    "Le EM LOTE os N registros do `chave-lote` (ex.: 'despesas') p/ o `contexto` (ex.: {'competencia' ...}),
     no tenant `ente-id`. Devolve um vetor de mapas (registros) — possivelmente vazio (competencia sem
     registros e' legitimo). Leitura em lote, nao avaliacao. **SEM `tx` de proposito** (review clj m2): a
     impl de producao e' HTTP-por-modulo (port->http_client, §22.10) — NAO deve segurar uma conexao PG
     aberta durante I/O de rede (starvation do pool). O Repo resolve os lotes FORA da tx das relacoes."))

(defrecord FontesFixture [lotes]
  FontesRemessa
  (buscar-lote [_ _ente-id chave-lote _contexto]
    (get lotes chave-lote [])))

(defn fontes-fixture
  "Cria um adapter de fontes IN-MEMORY a partir de {chave-lote -> [registros]} — p/ a forma/testes enquanto
  as fontes reais (HTTP por modulo) sao [GAP]. Lote nao configurado -> vetor vazio."
  [lotes]
  (->FontesFixture lotes))
