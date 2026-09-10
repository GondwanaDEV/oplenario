-- e2e/t3/fixtures.sql — as DUAS precondicoes da Trilha 3 que NAO tem rota HTTP de escrita.
-- Roda via container: docker exec -i oplenario-postgres-1 psql -U oplenario -d oplenario -f -
--
-- NAO E DESTRUTIVO: so INSERT ... ON CONFLICT DO NOTHING. Nenhum DROP/TRUNCATE/DELETE/UPDATE.
-- Rodar duas vezes e no-op (as duas tabelas tem UNIQUE que absorve o replay).
--
-- Por que SQL e nao HTTP:
--   1) legislativo.documento_modelo  -> so existe GET /legislativo/documento-modelos.
--      `Repositorio/criar-modelo!` existe no protocolo mas NAO esta wired a nenhuma rota
--      (legislativo/diplomat/http/in.clj:531 registra so o :get). Sem modelo ativo o
--      SeletorModelo de /expediente renderiza 0 botoes e "Gerar documento" fica disabled
--      para sempre -> as 3 escritas de E2 ficam inalcancaveis pela interface.
--   2) paineis.notificacao_caixa -> e READ-MODEL, alimentada pelo projetor `projetar-inbox!`
--      a partir de `notificacao.requisitada`(canal in_app), que por sua vez so nasce de
--      `norma.publicada`. `publicar-norma!` NAO tem chamador em nenhum diplomat (verificado
--      por grep) => nao existe caminho HTTP para gerar uma notificacao. A alternativa do repo
--      (`seed-demo/notificacoes`) cria identidade NOVA a cada run e SOBRESCREVE demo-ids.edn.
--      Estas linhas tem exatamente a forma que o projetor escreve.

\set ente '10000000-0000-0000-0000-000000000001'
\set vereador_identidade '49c23663-c30e-4829-b3be-c7481f107275'

-- RLS FORCE nas duas tabelas: a policy le app.ente_id do settings da sessao.
SELECT set_config('app.ente_id', :'ente', false);

-- ---------- E2: dois modelos de documento, ambos com campo de substituicao {{ }} ----------
-- O corpo TEM {{ }} de proposito: o plano exige exercitar "campo nao preenchido".
-- 'oficio_padrao' tem DOIS placeholders; 'certidao_padrao' tem TRES.
INSERT INTO legislativo.documento_modelo
  (ente_id, chave, nome, tipo_documento, corpo_template, ativo, origem, efetivado_em)
VALUES
  (:'ente'::uuid, 'oficio_padrao', 'Ofício padrão da Mesa', 'oficio',
   E'Ao Excelentíssimo Senhor {{destinatario}},\n\nA Mesa Diretora comunica a Vossa Excelência o que segue sobre {{assunto_detalhado}}.\n\nAtenciosamente,\nA Mesa Diretora.',
   true, 'nativa', now()),
  (:'ente'::uuid, 'certidao_padrao', 'Certidão padrão', 'certidao',
   E'CERTIFICO, para os devidos fins, que {{interessado}} consta nos registros desta Casa Legislativa sob a referência {{referencia}}, na data de {{data_referencia}}.\n\nO Secretário-Geral da Mesa.',
   true, 'nativa', now())
ON CONFLICT (ente_id, chave) DO NOTHING;

-- ---------- E8: tres notificacoes NAO-LIDAS para a identidade :vereador do demo-ids.edn ----------
-- Tres (nao uma) porque a escrita de E8 CONSOME uma por run: a suite tem de poder repetir.
-- objeto_id aponta para proposicoes REAIS de autoria dessa vereadora (nada inventado).
INSERT INTO paineis.notificacao_caixa
  (ente_id, destinatario_identidade_id, categoria, assunto, corpo, objeto_tipo, objeto_id, idempotency_key)
VALUES
  (:'ente'::uuid, :'vereador_identidade'::uuid, 'norma_publicada',
   'Sua proposição virou norma [T3-FIXTURE 1]',
   'O projeto de lei nº 4/2026, de sua autoria, foi publicado. [fixture da Trilha 3]',
   'proposicao', '8344f6b3-cfac-4c50-85f2-4a4039eec802'::uuid, 't3-fixture:notificacao:1'),
  (:'ente'::uuid, :'vereador_identidade'::uuid, 'norma_publicada',
   'Sua proposição virou norma [T3-FIXTURE 2]',
   'O requerimento nº 2/2026, de sua autoria, foi publicado. [fixture da Trilha 3]',
   'proposicao', '3e6007de-8dc6-4f15-a12b-67541f1fb3ab'::uuid, 't3-fixture:notificacao:2'),
  (:'ente'::uuid, :'vereador_identidade'::uuid, 'sistema',
   'Aviso da Secretaria [T3-FIXTURE 3]',
   'Mensagem de teste da Trilha 3 para exercitar "marcar como lida" pela interface.',
   'proposicao', '8344f6b3-cfac-4c50-85f2-4a4039eec802'::uuid, 't3-fixture:notificacao:3')
ON CONFLICT (ente_id, idempotency_key) DO NOTHING;

-- prova (o artefato, nao o exit code): o que ficou de pe depois do INSERT.
SELECT 'documento_modelo' AS tabela, chave AS chave_ou_key, id::text
  FROM legislativo.documento_modelo WHERE ente_id = :'ente'::uuid
UNION ALL
SELECT 'notificacao_caixa', idempotency_key, id::text
  FROM paineis.notificacao_caixa
 WHERE ente_id = :'ente'::uuid AND destinatario_identidade_id = :'vereador_identidade'::uuid
   AND lida_em IS NULL
 ORDER BY 1, 2;
