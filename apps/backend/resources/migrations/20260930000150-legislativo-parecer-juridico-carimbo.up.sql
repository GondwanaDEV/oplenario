-- ADR-0019 fatia 4 (parte C) — o CARIMBO do parecer juridico: no ato da assinatura, o texto canonico (nº/ano, signatario,
-- conclusao, relatorio e fundamentacao) e' assinado pelo AssinadorICP injetado (hoje o STUB-ICP-v0, como o resto do
-- legislativo) e o SHA-256 dele fica gravado. Assinado continua imutavel (o trigger da 111 vale para as colunas novas).
--
-- Colunas NULLABLE de proposito: pareceres assinados ANTES desta migration nao tem carimbo (a ficha mostra "sem carimbo").
-- O CHECK e' NOT VALID: nao reescreve nem valida as linhas antigas, mas vale para TODA assinatura nova — `estado =
-- 'assinado'` sem carimbo e' rejeitado pelo banco.
ALTER TABLE legislativo.parecer_juridico
  ADD COLUMN IF NOT EXISTS assinatura_algoritmo text,
  ADD COLUMN IF NOT EXISTS assinatura_b64       text,
  ADD COLUMN IF NOT EXISTS conteudo_sha256      text CHECK (conteudo_sha256 IS NULL OR conteudo_sha256 ~ '^sha256:[0-9a-f]{64}$');
--;;
ALTER TABLE legislativo.parecer_juridico
  ADD CONSTRAINT parecer_juridico_carimbo_completo CHECK (
    estado <> 'assinado'
    OR (assinatura_algoritmo IS NOT NULL AND assinatura_b64 IS NOT NULL AND conteudo_sha256 IS NOT NULL)) NOT VALID;
