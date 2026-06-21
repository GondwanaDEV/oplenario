# governanca-ia-clj — filtro de governança da porta de IA (protótipo de validação)

Port **Clojure** do filtro de governança que protege a saída ao **LLM de fronteira externo** (§22.9
Eixo 10 + decisões B1–B4). É a **primeira implementação de fato** (valida a *forma*), **não** o
satélite de produção (§22.3.4). Clojure por coerência com o idioma de governança do core (§22.5 authz
/ §22.7 compliance: *rules-as-data, fail-closed*) — é a **semente core-owned da política**; o enforcement
de produção mora no egress do satélite, que **consome** esta política.

## Rodar
Pré: Java 17+ e Clojure CLI.
```bash
clojure -M:test    # suíte de aceitação — 5 testes / 22 asserções
```

## O que valida (forma fechada)
- **B1 — gate determinístico fail-closed** (`provenancia`): conteúdo só cruza se a proveniência for
  **comprovadamente pública**; ausência de tag / `:secreto` / `:restrito` / voto secreto → **bloqueado**.
  Verificado: sem caminho de escape para conteúdo sigiloso (propriedade load-bearing, validada por ecc).
- **B2 — chokepoint único + redação de minimização** (`filtro` + `redator`): único ponto de saída; sobre
  conteúdo público, redige **identificadores privados** de alta precisão (CPF/CNPJ/email) e **preserva
  nome público**.
- **B3 — degradação** (`filtro`): qualquer peça sigilosa **degrada a chamada inteira** (não envia parcial
  em silêncio); o vendor (fake) **nunca recebe nada** — provado pelo atom de recebidos vazio.
- **B4 — auditoria sem conteúdo** (`auditoria`): registro append-only com proveniência + decisão +
  **contagem** de redações + vendor + hash; **nunca o conteúdo** (accountability LGPD).

## Limitações (refinamento de produção — `[GAP]`, apontadas por ecc:clojure-reviewer)
1. **Over-redação de números no formato `NNN.NNN.NNN-NN`** (nº de ofício/protocolo/processo legado casa
   o padrão de CPF) → mutila conteúdo público. Produção: **validar dígito verificador** + heurística de
   prefixo contextual, junto do eixo de **NER** (deferido).
2. **Sem validação de DV** — `123.456.789-00` é redigido mesmo se não for CPF válido (falso-positivo;
   conservador p/ privacidade, mas mutila texto).
3. **Payload vazio chama o vendor com `[]`** (inofensivo aqui — nada sigiloso cruza; em produção: guard
   `:noop`).
4. **`hash-payload` usa `clojure.core/hash`** (32 bits, não-cripto) — produção: SHA-256.
5. **`PortaInferencia` sem contrato de erro/timeout** — produção: try/catch com `:status :erro-vendor`
   auditado.
6. **NER de PII amplo** (nomes de pessoa privada, endereços) — deferido (content-aware; risco de
   over-redação de nome público exige tuning).

## Relação com o resto
- A **proveniência de sigilo** nasce no core (Clojure) e viaja por evento de integração (§22.3.3) — o
  gate **lê a tag**, não re-deriva.
- O **vendor** atrás da `PortaInferencia` é trocável: default **Claude/Bedrock `sa-east-1`** (pesquisa A:
  dado-no-Brasil + não-treino), **Sabiá** como modo soberano on-demand, **self-host** quando open-weights
  fecharem o gap (§22.9 Eixo 10). Trocar = trocar a impl do protocolo, nada mais.
