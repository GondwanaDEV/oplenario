-- F7 E2 (notificacao duravel — fan-out do acompanhamento, §16.11): ENRIQUECE o ledger de entrega
-- `paineis.notificacao_entrega` (mig 0004) com o CONTEUDO renderizado + rastreabilidade + motivo de falha.
--
-- POR QUE: o ledger e' a UNICA peca DURAVEL do modulo de projecao (mig 0004). O envio de e-mail/push e' um
-- efeito EXTERNO nao-transacional; a disciplina outbox exige gravar o INTENT DURAVEL primeiro (estado
-- 'pendente' na tx do relay, atomico com o dedup §22.9 E2) e ENTREGAR depois, num passo SEPARADO (worker).
-- Esse passo separado NAO tem acesso ao evento que originou a notificacao (ele ja' foi drenado) — logo o
-- CONTEUDO a enviar (assunto/corpo, ja' RENDERIZADO por transparencia, que tem a materia same-schema) tem de
-- morar no ledger, senao o worker nao teria o que mandar. `paineis` nao pode re-derivar o conteudo (a materia
-- vive em transparencia.materia — JOIN cross-schema proibido §22.10; e o modulo e' entrega BURRA de proposito).
--
-- SEM PII: `assunto`/`corpo` carregam so' info PUBLICA (ementa da proposicao + estado da tramitacao — tudo ja'
-- no portal publico). `destinatario` (mig 0004) e' o UUID de identidade (handle pseudonimo), NAO um e-mail/nome.
-- A resolucao identidade-UUID -> contato real (e-mail) + o envio SMTP/push ficam no notificador (carry infra).
--
-- objeto_tipo/objeto_id: ref polimorfica OPACA (mesma convencao de paineis.pendencia) do que a notificacao
-- trata ("proposicao"/proposicao_id) — rastreabilidade/auditoria + base p/ um futuro "reenviar"/"notificacoes
-- desta materia" sem nova migration. Opaco: NAO acopla paineis a legislativo (strings/uuid crus).
--
-- falha_motivo: preenchido quando estado='falha' (o worker registra a causa da tentativa fracassada).
--
-- RLS/grants: herdados da mig 0009 (FORCE RLS + policy tenant_isolation + GRANT SELECT/INSERT/UPDATE). ADD
-- COLUMN nao precisa re-grant (grant e' table-level). Todas as colunas sao NULLABLE — a tabela ja' existe
-- (idempotencia da migration) e uma linha antiga sem conteudo e' degenerada mas nunca ocorre (o consumer
-- SEMPRE grava o conteudo); default NULL evita reescrever a tabela.
ALTER TABLE paineis.notificacao_entrega
  ADD COLUMN IF NOT EXISTS assunto      text,
  ADD COLUMN IF NOT EXISTS corpo        text,
  ADD COLUMN IF NOT EXISTS objeto_tipo  text,
  ADD COLUMN IF NOT EXISTS objeto_id    uuid,
  ADD COLUMN IF NOT EXISTS falha_motivo text;
--;;
-- "entregas pendentes" (a query do worker de entrega: SELECT ... WHERE estado='pendente'). Parcial em
-- 'pendente' (o worker so' varre o que falta enviar; entregas ja' resolvidas — enviada/falha — saem do indice).
-- Escopo tenant no prefixo (ente_id) — o worker varre por ente (com-tenant*). `[:inline "pendente"]` no db.
CREATE INDEX IF NOT EXISTS idx_notificacao_pendente
  ON paineis.notificacao_entrega (ente_id, criado_em)
  WHERE estado = 'pendente';
