-- e2e/t3/fixtures.sql — as TRES precondicoes da Trilha 3 que NAO tem rota HTTP de escrita.
-- Roda via container: docker exec -i oplenario-postgres-1 psql -U oplenario -d oplenario -f -
--
-- NAO E DESTRUTIVO: so INSERT (ON CONFLICT DO NOTHING / guardado por NOT EXISTS). Nenhum
-- DROP/TRUNCATE/DELETE/UPDATE. Rodar duas vezes e no-op.
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
--   3) legislativo.pareceres -> nao ha rota HTTP que CRIE parecer (so GET/PATCH/POST-emissao sobre
--      um :id existente). Ver o bloco E4 la embaixo para o porque de precisar de um alvo novo.

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

-- ---------- E4: UM parecer em 'aguardando_assinatura' com relator = a identidade :vereador ----------
-- POR QUE: o spec E4 tem duas escritas de UM TIRO SO'. "Emitir parecer (secretaria)" sobre um parecer
-- em 'aguardando_assinatura' dispara a UNICA transicao que o template de demo conhece pro gatilho
-- 'emitir' (aguardando_assinatura -> aprovado, apps/backend/demo/acervo.clj:449-452), e 'aprovado' e'
-- ESTADO TERMINAL: o trigger trg_pareceres_imut_estado (shared.imut_trava_estado_terminal) impede
-- qualquer UPDATE depois. Consumido o parecer, a corrida seguinte de E4 nao tem alvo — e NAO EXISTE
-- rota HTTP que crie parecer (grep em legislativo/diplomat/http/in.clj: so GET/PATCH/POST-emissao
-- sobre um :id que ja' existe; `Repositorio/criar-parecer!` nao esta wired). Sem esta fixture, E4 so'
-- roda inteiro UMA vez por semente.
--
-- NAO FABRICA ESTADO QUE O DOMINIO NAO PRODUZ: e' um CLONE da linha que a propria semente
-- (acervo.clj) escreve — mesmos objeto_id/comissao_id/relator_id/template_id, mesma versao de texto
-- 'rascunho' numero 1. So' o id e' novo. Guardado por NOT EXISTS: se ainda ha um parecer alvo de pe
-- (o da semente OU um fixture anterior nao consumido), este bloco e' no-op — nunca acumula.
INSERT INTO legislativo.pareceres
  (ente_id, id, objeto_tipo, objeto_id, comissao_id, relator_id, voto_relator, estado,
   template_id, origem, origem_ref, efetivado_em, lock_version)
SELECT m.ente_id, gen_random_uuid(), m.objeto_tipo, m.objeto_id, m.comissao_id, m.relator_id,
       NULL, 'aguardando_assinatura', m.template_id, 'nativa', 't3-fixture:parecer', now(), 0
  FROM legislativo.pareceres m
 WHERE m.ente_id = :'ente'::uuid
   AND m.id = 'ce76c191-8387-4820-b126-bcb30d02a358'::uuid   -- a linha-modelo da semente
   AND NOT EXISTS (
     SELECT 1 FROM legislativo.pareceres p
      WHERE p.ente_id = m.ente_id
        AND p.estado = 'aguardando_assinatura'
        AND p.relator_id = m.relator_id);

-- a versao de texto 'rascunho' que faz `textoEstado` sair de 'vazio' (sem ela a tela de assinatura
-- cai em 'sem-texto' e nunca oferece o CTA). Mesma forma da linha da semente.
INSERT INTO legislativo.parecer_texto_versao
  (ente_id, parecer_id, numero_versao, origem_versao, estado_versao, formato, texto_inline,
   origem, efetivado_em, lock_version)
SELECT p.ente_id, p.id, 1, 'redacao', 'rascunho', 'markdown',
       E'Relatório\n\nParecer semeado por e2e/t3/fixtures.sql para a Trilha 3 (clone da linha da semente).\n\nAnálise\n\nTexto de rascunho para exercitar emissão e assinatura pela interface.',
       'nativa', now(), 0
  FROM legislativo.pareceres p
 WHERE p.ente_id = :'ente'::uuid
   AND p.origem_ref = 't3-fixture:parecer'
   AND NOT EXISTS (
     SELECT 1 FROM legislativo.parecer_texto_versao v
      WHERE v.ente_id = p.ente_id AND v.parecer_id = p.id);

-- prova do bloco E4 (o artefato, nao o exit code)
SELECT 'parecer_alvo_E4' AS tabela, estado AS chave_ou_key, id::text
  FROM legislativo.pareceres
 WHERE ente_id = :'ente'::uuid
   AND estado = 'aguardando_assinatura'
   AND relator_id = '69d7aa68-498a-4aa5-a6e9-10e0ad2d664b'::uuid;

-- E8 — RESET das notificacoes de fixture para NAO-LIDAS.
-- O INSERT acima e' idempotente por idempotency_key, entao ele NAO recria o que ja existe — e o proprio
-- spec E8 marca uma delas como lida a cada corrida. Sem este reset, a 2a corrida encontra a inbox toda
-- lida e "marcar como lida" fica sem alvo: o teste reprovava por fixture consumida, nao por defeito.
-- E reset de linha que a PROPRIA fixture criou (idempotency_key 't3-fixture:%'), nunca de dado da Casa.
UPDATE paineis.notificacao_caixa
   SET lida_em = NULL
 WHERE ente_id = '10000000-0000-0000-0000-000000000001'
   AND idempotency_key LIKE 't3-fixture:notificacao:%'
   AND lida_em IS NOT NULL;
