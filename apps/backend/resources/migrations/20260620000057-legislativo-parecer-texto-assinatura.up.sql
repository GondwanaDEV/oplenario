-- Onda C Slice C4 (feature 7.3, assinatura em 2 toques) — assinatura sobre a versao de texto do parecer
-- que vira vigente. Mesmo padrao de legislativo.artefato_publicacao (mig 0046): assinatura DESTACADA
-- (algoritmo+b64) + quem+quando. NULLABLE (versoes anteriores a esta fatia nunca sao assinadas
-- retroativamente) e so' preenchida quando ha' um rascunho sendo promovido na MESMA chamada de
-- emitir-parecer! (Repo/emitir-parecer!, legislativo/components/repositorio.clj) — nao cobre a coluna
-- na trigger de imutabilidade (trg_parecer_texto_conteudo_imutavel, mig 0020): [GAP] nao ha' CHECK/trigger
-- que impeca sobrescrever um valor ja' setado — nenhum caminho de codigo hoje o faz (so' `promover!` grava
-- estes campos, uma unica vez por versao, no momento em que ela se torna vigente).
ALTER TABLE legislativo.parecer_texto_versao
  ADD COLUMN IF NOT EXISTS assinatura_algoritmo text,
  ADD COLUMN IF NOT EXISTS assinatura_b64 text,
  ADD COLUMN IF NOT EXISTS assinado_por uuid,
  ADD COLUMN IF NOT EXISTS assinado_em timestamptz;
