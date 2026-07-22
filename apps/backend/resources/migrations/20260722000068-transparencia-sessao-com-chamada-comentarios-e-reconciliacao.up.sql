-- Revisao da fatia 5 do carry I-5. Duas coisas que a 0067 deixou abertas e que NAO se consertam nela
-- (migration aplicada e' IMUTAVEL: migratus grava o id e nao re-roda).
--
-- (1) COMMENT NO CATALOGO. A 0067 declarou a semantica de `sessao_com_chamada.data` so' em prosa no `.sql` e
--     na docstring de `registrar-sessao-com-chamada!`. Quem abre `\d+ transparencia.sessao_com_chamada` le
--     `data date NOT NULL` e mais nada, e conclui "e' a data da sessao" — que e' falso em DOIS sentidos (ver
--     o texto do COMMENT abaixo). E' a coluna que a fatia 6 usa como PREDICADO da janela de exercicio do
--     mandato, num numero PUBLICO e NOMINAL. Mesma licao, e mesmo remedio, da 0066 sobre `presenca.tipo`.
--
-- (2) RECONCILIACAO DA JANELA DE DEPLOY. O backfill da 0067 e' um SNAPSHOT unico, tirado dentro da tx da
--     migration. O comando canonico de subida do projeto (`docker compose up -d --build`) roda o servico
--     `migrate` ate' a conclusao ANTES de recriar o `app` (`depends_on: migrate service_completed_successfully`),
--     e o container `app` ANTIGO continua de pe' drenando o outbox com o consumer de UM statement. Toda
--     `presenca.registrada` drenada nessa janela grava linha em `presenca_parlamentar` e NENHUMA em
--     `sessao_com_chamada`; o evento fica marcado em `shared.evento_consumido`, entao nao ha redrive, e nao
--     existe ferramenta de re-projecao no repo. A sessao perdida sumiria do denominador da fatia 6 PARA
--     SEMPRE, e o faltoso daquela sessao publicaria 100% — a mesma lavagem do faltoso pela qual a Forma C1
--     foi descartada. O `INSERT..SELECT..GROUP BY ... ON CONFLICT DO NOTHING` e' idempotente por construcao,
--     entao repeti-lo AQUI (com o codigo novo ja' vivo) varre o buraco sem tocar nas linhas boas.
--     LIMITE HONESTO: ele reconstroi a data com a MESMA aproximacao do backfill da 0067 (ver o COMMENT).
--
-- CARRY OPERACIONAL (nao estava escrito na 0067 e vale para as DUAS): `ALTER TABLE ... [NO] FORCE ROW LEVEL
-- SECURITY` toma ACCESS EXCLUSIVE LOCK (verificado em pg_locks neste PG16) e migratus roda o arquivo inteiro
-- numa transacao — o lock e' tomado no primeiro ALTER e so' cai no COMMIT, cobrindo o INSERT..SELECT sobre
-- `presenca_parlamentar` inteira. Essa tabela e' lida pela rota PUBLICA do perfil do vereador e escrita pelo
-- relay: enquanto o lock estiver de pe', as duas coisas BLOQUEIAM. A janela e' curta hoje (a tabela guarda
-- ESTADO por sessao+vereador, nao o log de eventos), mas cresce monotonicamente. Mesmo tom do carry ja'
-- registrado para o DROP INDEX da fatia 6: JANELA DE MANUTENCAO, nao deploy no meio de sessao.
COMMENT ON TABLE transparencia.sessao_com_chamada IS
$c$UMA linha por SESSAO que teve ao menos um registro de presenca de ALGUEM (carry I-5, mig 0067). NAO e' "sessao realizada": uma sessao em que ninguem fez check-in nao existe aqui e some dos DOIS lados da fracao do numero-card de presenca — deliberado, porque a alternativa (denominador vindo de sessoes.sessao) transformaria uma falha do painel em FALTA para os 21 vereadores. Mantida pelo consumer de presenca.registrada, na mesma tx do relay, e reconciliavel pelo INSERT..SELECT da mig 0068.$c$;
--;;
COMMENT ON COLUMN transparencia.sessao_com_chamada.data IS
$c$Data CIVIL (America/Fortaleza, kernel/tempo/zona-civil-padrao) — NAO e' "a data da sessao", e as duas origens da linha NAO tem a mesma semantica. (a) Linha projetada AO VIVO pelo consumer: data do PRIMEIRO evento de presenca da sessao (ON CONFLICT ... LEAST sobre todos os eventos, um a um). (b) Linha vinda do BACKFILL da 0067 ou do reconciliador da 0068: data do PRIMEIRO dos ULTIMOS eventos POR VEREADOR, que pode ser POSTERIOR a (a) — transparencia.presenca_parlamentar guarda ESTADO por (sessao, vereador) e nao log, entao min(ocorrido_em) GROUP BY sessao e' min(max por vereador); o log so' existe em sessoes.presenca_evento e le-lo daqui seria JOIN cross-schema, proibido pela 22.10. Sessao que atravessa a meia-noite cai um dia adiante. Nao ha ferramenta de re-projecao no repo: linha errada e' permanente. A coluna projetado_em distingue as linhas de migration (todas com o instante dela) das linhas vivas.$c$;
--;;
ALTER TABLE transparencia.presenca_parlamentar NO FORCE ROW LEVEL SECURITY;
--;;
-- RECONCILIADOR. Identico em forma ao backfill da 0067, de proposito: e' o MESMO statement, e o ns de teste
-- `transparencia.sessao-com-chamada-test` o extrai DESTE arquivo e o roda contra dado semeado (o backfill da
-- 0067 nao era testavel — o fixture roda migrar! antes de existir linha; este e', porque roda depois).
INSERT INTO transparencia.sessao_com_chamada (ente_id, sessao_id, data)
SELECT ente_id, sessao_id, min(ocorrido_em AT TIME ZONE 'America/Fortaleza')::date
  FROM transparencia.presenca_parlamentar
 GROUP BY ente_id, sessao_id
ON CONFLICT (ente_id, sessao_id) DO NOTHING;
--;;
ALTER TABLE transparencia.presenca_parlamentar FORCE ROW LEVEL SECURITY;
