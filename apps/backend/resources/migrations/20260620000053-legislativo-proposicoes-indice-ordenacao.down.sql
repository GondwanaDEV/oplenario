-- Reverte a mig 0053 (Onda B Slice 1, indice de ordenacao). Remove so' o indice adicionado; a tabela
-- e as demais estruturas (particoes, RLS, outros indices) sao das migrations 0013 em diante.
DROP INDEX IF EXISTS idx_proposicoes_atualizado_em;
