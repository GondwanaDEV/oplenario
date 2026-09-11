-- 3-A (decisao do Daouda, 11/09/2026) — QUEM pode disparar cada gatilho.
--
-- O buraco que esta coluna fecha: ate' aqui a unica autorizacao da borda de tramitacao era o gate GROSSO
-- da rota, `exige-papel "secretario"` — o MESMO papel que LISTA proposicoes. Um portador desse papel
-- levava a materia de ponta a ponta do rito sozinho. A 3-B deu a Casa como exigir o ATO (guarda); esta
-- coluna da' como exigir a PESSOA, e as duas sao perguntas diferentes: o guard pergunta "isto aconteceu?",
-- a autorizacao pergunta "voce pode declarar que aconteceu?".
--
-- E' EXPRESSAO DA MESMA DSL, nao um enum de papel, por dois motivos:
--   (1) Invariante 4 — quem preside, quem relata e quem despacha e' rito da Casa, nao vocabulario do
--       sistema. Um enum `('presidente','secretario')` cravaria o organograma de uma camara no motor.
--   (2) Disciplina 5 — o motor declarativo ja e' o mesmo para tramitacao, autorizacao (§22.5 eixo B),
--       plenario e compliance. `motor/politica-dsl` ja devolve `(fn [ator recurso] -> bool)` e o catalogo
--       de fatos ja publica `é_presidente_da_mesa`, `é_secretario_da_mesa`, `quem_exerce_presidencia`,
--       `é_membro_de_comissao`, `é_presidente_de_comissao`. Nada novo precisou ser inventado.
--
-- NULL = sem restricao ALEM do gate da rota. Espelha `guarda NULL` (= sempre passa), e nao e' frouxidao
-- por acaso: exigir a coluna preenchida quebraria todo rito ja cadastrado, inclusive o da semente. O
-- preco dessa escolha e' que um rito que ESQUECEU de declarar autorizacao parece autorizado — por isso
-- `GET /legislativo/proposicoes/:id/tramitacao` passa a dizer, por gatilho, se o ator pode dispara-lo, e
-- a ausencia de restricao aparece como tal em vez de se esconder.
ALTER TABLE legislativo.template_transicao ADD COLUMN IF NOT EXISTS autorizacao text;
--;;
COMMENT ON COLUMN legislativo.template_transicao.autorizacao IS
  'Expressao booleana da DSL do motor: quem pode disparar este gatilho. NULL = so o gate da rota. Avaliada por motor/politica-dsl DENTRO da tx da escrita, contra o snapshot travado (achado C3: authz-tx != write-tx). Lance = negacao.';
