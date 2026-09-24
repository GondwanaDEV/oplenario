(ns oplenario.sessoes.adapters.out.folha
  "Gate de SAIDA `models -> wire/out` da FOLHA DA SESSAO (§22.10 adapters/out, ADR-0001) — Etapa 5 fatia 5.

  As DUAS projecoes de METADADOS (`folha->wire`/`folhas-da-sessao->wire`, POST 201 + GET lista) passam por
  Malli contra `wire.out/FolhaMetadadosOut`/`FolhasDaSessaoOut` — drift de campo e' bug de servidor (500 via
  a excecao lancada aqui), nunca resposta malformada. Elas NUNCA carregam o binario nem os
  `*_objeto_store_ref` (detalhe de armazenamento interno) — so' os hashes.

  As DUAS respostas de CONTEUDO (`->html-resposta`/`->pdf-download`) NAO passam por Malli: o wire de um
  binario e' o proprio binario + os headers de transporte, mesmo racional de
  `transparencia.adapters.out.artefato/->download` (que `->pdf-download` copia a forma de).

  [CARRY HERDADO de `transparencia/adapters/out/artefato.clj` — a 1a rota binaria do repo escreveu que
   `os/obter` materializa o blob INTEIRO em heap (`.readAllBytes`) e que o conserto e' leitura em STREAMING
   servida direto no body (espelhando `guardar-stream!`, que ja' existe no mesmo protocolo para a escrita).
   Esta rota REPETE o padrao, e o carry viaja junto — nao morre no ns de origem. Duas diferencas de
   severidade, medidas, que justificam nao resolver AGORA: (a) a rota da folha exige papel 'secretario', nao
   e' superficie publica anonima como a da transparencia; (b) desde a fatia 5 o tamanho do blob e' LIMITADO
   nas duas pontas — `serializador-folha/teto-bytes-html` na entrada e `renderizador-pdf/teto-bytes-pdf` na
   saida — entao 'blob arbitrariamente grande' deixou de ser a forma aguda do problema; o que resta e' N
   downloads concorrentes x 5 MiB. Gatilho para resolver: o streaming entrar por qualquer motivo em
   `ObjetoStore` (a transparencia chega la' primeiro), ou esta rota deixar de exigir papel.]"
  (:require [malli.core :as m]
            [malli.error :as me]
            [oplenario.sessoes.wire.out :as wire])
  (:import (java.io ByteArrayInputStream)))

(set! *warn-on-reflection* true)

(defn- validar! [schema out nome]
  (when-not (m/validate schema out)
    (throw (ex-info (str nome " viola o contrato de saida (bug de servidor)")
                    {:campos (keys (me/humanize (m/explain schema out)))})))
  out)

(defn folha->wire
  "Linha de `sessoes.folha_sessao` (dominio, kebab, de `repo/inserir-folha-dedup!`/`repo/buscar-folha`/
  `repo/folhas-da-sessao`) -> FolhaMetadadosOut (validado). `nome` (opcional) = o nome de quem congelou, ja'
  resolvido pelo chamador; ausente/nil -> a chave `:gerada-por-nome` nao aparece."
  ([row] (folha->wire row nil))
  ([{:keys [id versao spec-versao html-hash pdf-hash gerada-por gerada-em ja-congelada]} nome]
   (validar! wire/FolhaMetadadosOut
             (cond-> {:id (str id) :versao versao :spec-versao spec-versao
                      :html-hash html-hash :pdf-hash pdf-hash
                      :gerada-por (str gerada-por) :gerada-em (str gerada-em)}
               nome (assoc :gerada-por-nome nome)
               ja-congelada (assoc :ja-congelada true))
             "FolhaMetadadosOut")))

(defn folhas-da-sessao->wire
  "N linhas -> FolhasDaSessaoOut (validado, resposta 200 de GET /sessoes/:id/folhas). `nomes` = {identidade-id
  nome} de quem congelou (docs/23 Fatia 5); id fora do mapa sai sem `:gerada-por-nome`."
  ([sessao-id folhas] (folhas-da-sessao->wire sessao-id folhas {}))
  ([sessao-id folhas nomes]
   (validar! wire/FolhasDaSessaoOut
             {:sessao-id (str sessao-id)
              :folhas (mapv #(folha->wire % (get nomes (:gerada-por %))) folhas)}
             "FolhasDaSessaoOut")))

(def ^:const csp-html
  "CSP da resposta do HTML CONGELADO — o SERVIDOR fecha a porta, sem depender de o frontend lembrar do
  `<iframe sandbox=\"\">` de D10.

  ACHADO DA REVISAO ADVERSARIAL DA FATIA 5. O documento embute TEXTO LIVRE digitado por humano (o `motivo`
  da justificativa — potencial dado de saude, LGPD). A Fatia 2 escapa tudo e tem teste para isso, mas ate'
  aqui o escaping era a UNICA linha de defesa: o isolamento planejado (D10) so' existe como decisao, e ESTA
  ROTA JA' ESTA' VIVA.

  A FONTE ME CORRIGIU — a revisao afirmou 'nenhum CSP em lugar nenhum do repo' com base em `grep`, e o grep
  do CODIGO estava certo, mas o do FIO nao: MEDIDO com o header removido, a resposta ja' saia com
  `Content-Security-Policy: object-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'strict-dynamic'
  https: http:;` — o DEFAULT de `io.pedestal.http.secure-headers/content-security-policy-header`, que
  `http/default-interceptors` instala sozinho. Isso PIORA o achado em vez de refuta-lo: e' uma politica
  pensada para API JSON, e num navegador que so' implementa CSP2 (onde `'strict-dynamic'` e' token
  desconhecido e portanto ignorado) ela AUTORIZA `script-src 'unsafe-inline' 'unsafe-eval'` no documento.
  Sobrepor era obrigatorio, nao decorativo. E sobrepor FUNCIONA porque `secure-headers` faz
  `(merge sec-headers (:headers response))` — o header da propria rota vence o default (medido: verde).

  O CENARIO CONCRETO: um secretario copia
  `/sessoes/:id/folhas/1` e abre numa aba do mesmo navegador logado — o documento renderiza como pagina de
  TOPO, same-origin: qualquer falha futura de escape (refactor do serializador, edge case de normalizacao
  Unicode, campo de texto livre novo que nao passe pelo helper) vira XSS com a sessao dele. O
  `X-Frame-Options: DENY` do interceptor global NAO cobre isto — impede que a pagina seja ENQUADRADA, nao
  que ela EXECUTE.

  `sandbox` sem `allow-scripts`/`allow-same-origin` e' o que carrega o peso: origem opaca, script nenhum. O
  `style-src 'unsafe-inline'` NAO e' afrouxamento — e' o que mantem o documento AUTOCONTIDO legivel: o CSS
  vive num `<style>` inline por exigencia do openhtmltopdf (CSS 2.1, zero recurso externo), e um
  `default-src 'none'` puro o apagaria. Custo zero de seguranca: com `sandbox` ativo nao ha' script para
  explorar CSS injetado, e nao ha' origem para vazar nada para."
  "sandbox; default-src 'none'; style-src 'unsafe-inline'")

(def ^:const csp-pdf
  "CSP da resposta do PDF CONGELADO. Mesmo isolamento, com `allow-downloads` — `sandbox` puro faria o Chrome
  BLOQUEAR o proprio download que o `Content-Disposition: attachment` pede, quebrando a rota que ela deveria
  proteger. Sem `style-src` porque um PDF nao tem CSS."
  "sandbox allow-downloads; default-src 'none'")

(defn ->html-resposta
  "Resposta Ring do HTML CANONICO congelado: 200, Content-Type do proprio artefato (text/html; charset=utf-8),
  CSP de isolamento (ver `csp-html`), body = os BYTES DO OBJETO_STORE, SEM Content-Disposition (visualizacao
  INLINE — a Fatia 6 aponta um `<iframe>` aqui, nunca um download). NUNCA re-renderiza: os bytes servidos
  sao exatamente os que o congelamento hasheou."
  [{:keys [content-type] b :bytes}]
  {:status 200
   :headers {"Content-Type" content-type
             "Content-Security-Policy" csp-html}
   :body (ByteArrayInputStream. b)})

(defn ->pdf-download
  "Resposta Ring BINARIA do PDF congelado: 200, Content-Type application/pdf, Content-Disposition attachment
  com nome deterministico `folha-<sessao-id>-v<versao>.pdf`, body = os BYTES DO OBJETO_STORE. Mesma forma de
  `transparencia.adapters.out.artefato/->download`."
  [{:keys [content-type versao] b :bytes} sessao-id]
  {:status 200
   :headers {"Content-Type" content-type
             "Content-Security-Policy" csp-pdf
             "Content-Disposition" (str "attachment; filename=\"folha-" sessao-id "-v" versao ".pdf\"")}
   :body (ByteArrayInputStream. b)})
