(ns oplenario.tempo-real.controllers
  "Orquestracao IMPURA do endpoint SSE (§22.6 eixo G): trabalha SO em models (a sessao carregada, as mensagens
  da CanalStore) — nunca toca wire/adapters (isso e' do diplomat). `autorizar-plenario` roda o checklist de
  authz da ABERTURA (canais.clj) com a sessao consultada via fn INJETADA pelo host (inversao de dependencia:
  tempo_real NAO importa sessoes — §22.10; o host cruza os modulos). `enviar-desde` itera o replay/incremento
  da CanalStore. Depende do kernel (autorizacao) + da logic do proprio modulo, jamais de outro modulo."
  (:require [oplenario.kernel.autorizacao :as authz]
            [oplenario.tempo-real.components :as comp]
            [oplenario.tempo-real.logic :as logic]))

(set! *warn-on-reflection* true)

(defn autorizar-plenario
  "Checklist de authz da CONEXAO ao canal plenario (canais.clj). `consultar-sessao` = (fn [ente-id sessao-id] ->
  sessao | nil), injetada pelo host (delega ao Repo de sessoes; a RLS escopa por tenant). Carrega a sessao no
  tenant do ator e roda a camada FINA (policy.check/pode-assistir-plenario?, que cobre posse de tenant + sessao
  nao-secreta). Devolve a sessao se autorizada; nil se inexistente no tenant (a borda traduz p/ 404); LANCA
  negacao (:autorizacao/negado -> 403 na borda) se proibida (sessao secreta / de outra Casa)."
  [consultar-sessao ator sessao-id]
  ;; fail-closed auto-suficiente (review seg MINOR-2): nao confia na pre-condicao do interceptor de auth — ator
  ;; nil NEGA aqui, nunca delega a consultar-sessao com ente-id nil.
  (when (nil? ator) (authz/negar! :ator-ausente {:acao :plenario/assistir}))
  (when-let [s (consultar-sessao (:ente-id ator) sessao-id)]
    (authz/check! ator :plenario/assistir s logic/pode-assistir-plenario?)
    s))

(defn enviar-desde
  "Replay/incremento do canal: le as mensagens com seq > `cursor` (Last-Event-ID) e chama `(enviar! msg)` por
  mensagem, em ordem de seq. `enviar!` devolve false se o canal de saida caiu (cliente desconectou) -> para no
  1o envio falho. Devolve [novo-cursor vivo?]: `novo-cursor` = a maior seq enviada (ou `cursor` se nada novo);
  `vivo?`=false sinaliza desconexao p/ o loop do diplomat encerrar. So toca a CanalStore (componente injetado);
  a projecao wire (mensagem->frame) e o I/O SSE ficam no `enviar!` que o diplomat passa (gate so na borda)."
  [canal-store canal cursor enviar!]
  (reduce (fn [[c _] {msg-seq :seq :as msg}]
            (if (enviar! msg)
              [msg-seq true]
              (reduced [c false])))
          [cursor true]
          (comp/ler-desde canal-store canal cursor)))
