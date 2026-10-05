-- A regra de votacao da EMENDA A LEI ORGANICA (CF art. 29 caput): "votada em dois turnos, com o intersticio minimo de
-- dez dias, e aprovada por dois tercos dos membros da Camara Municipal". Ate' aqui so' as contas do Prefeito tinham
-- regra; a PELOM abria votacao com o quorum que a Mesa escolhesse, e a demo a aprovou por maioria simples (7 de 16).
--
-- Duas coisas, as duas DADO (Inv.4), na mesma tabela da regra das contas (mig 20261003000183):
--   1. a linha `emenda_lom`, com a guarda do quorum na mesma DSL do motor que a das contas avalia
--      (`motor/guarda-dsl`, na tx que abre a votacao). Nominal NAO entra na guarda: a CF nao a exige, e' cada LOM.
--   2. duas colunas que a guarda nao expressa bem, porque sao contagem e calendario, nao campo da votacao:
--      `turnos` (quantas aprovacoes em plenario a materia precisa para ser aprovada) e `intersticio_dias` (o minimo
--      entre a aprovacao de um turno e a abertura da votacao do seguinte). A conta usa esses numeros em
--      `legislativo/db/regra_votacao.clj`. O minimo e' o da CF; a LOM pode pedir mais ([GAP] por Casa).
--
-- As tres perguntas de migration:
--   1. Muda dado existente? So' acrescenta: colunas com DEFAULT (a linha `contas_prefeito` fica com 1 turno, sem
--      intersticio, que e' o que ela ja' significava) e uma linha nova. Votacao ja' registrada nao e' tocada — a PELOM
--      aprovada por maioria simples na demo continua no historico como foi votada.
--   2. Pode falhar conforme o dado ou o relogio? Nao: os CHECK novos valem para a unica linha existente (turnos 1,
--      intersticio NULL) e o INSERT e' ON CONFLICT DO NOTHING.
--   3. Enxerga todas as Casas? A tabela nao tem ente_id nem RLS: a regra e' da Constituicao, igual para toda Casa.
ALTER TABLE legislativo.regra_votacao_materia
  ADD COLUMN IF NOT EXISTS turnos smallint NOT NULL DEFAULT 1 CHECK (turnos BETWEEN 1 AND 2),
  ADD COLUMN IF NOT EXISTS intersticio_dias smallint CHECK (intersticio_dias IS NULL OR intersticio_dias BETWEEN 1 AND 365);
--;;
INSERT INTO legislativo.regra_votacao_materia (chave, guarda, referencia, turnos, intersticio_dias)
VALUES ('emenda_lom',
        'votacao.quorum_tipo == "maioria_qualificada_2_3"',
        'CF art. 29',
        2, 10)
ON CONFLICT (chave) DO NOTHING;
