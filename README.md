# Handoff — O Plenário · Discovery de Arquitetura

Pasta de transferência para continuar o **discovery de arquitetura** d'**O Plenário** —
plataforma SaaS para câmaras municipais brasileiras — no **Claude Code**. É um handoff
completo: uma instância nova do Claude, com zero contexto além desta pasta, retoma o trabalho
exatamente onde parou, no mesmo método, sem você reexplicar nada.
(Nome **O Plenário** provisório até confirmar domínio + INPI — ver `docs/04-nome-e-marca.md`.)

## Como usar no Claude Code

1. Descompacte esta pasta na raiz do diretório que você vai abrir no Claude Code.
2. Abra o Claude Code nessa pasta — o **`CLAUDE.md`** é lido automaticamente e orienta o resto.
3. Primeira ação atual: a **trilha de produto/comercial** — o descompasso do Eixo A já foi
   fechado em v1.9. Estado e próximos passos em `docs/00-estado-e-roadmap.md` e no `CLAUDE.md`.

## O que tem aqui

- **`CLAUDE.md`** — brief operacional auto-lido pelo Claude Code. Ponto de entrada.
- **`documento-mestre-camaras.md`** — fonte canônica de verdade (todas as decisões
  consolidadas, estratégicas e arquiteturais). Em conflito com qualquer coisa, prevalece.
- **`docs/`**:
  - `00-estado-e-roadmap.md` — estado do cursor (Eixo A consolidado em v1.9) e o roadmap de §22.7.
  - `01-metodologia.md` — como as sessões funcionam (eixo a eixo, confirmação, versionamento).
  - `02-eixo-A-fechado-rascunho.md` — decisões do Eixo A (consolidadas em §22.7 v1.9; rascunho de origem).
  - `03-proxima-sessao-eixo-C.md` — brief + prompt da próxima sessão.
  - `04-nome-e-marca.md` — decisão de nome (O Plenário) + checks pendentes (domínio, INPI).

## Estado atual e próxima ação

O **descompasso do Eixo A foi fechado em v1.9**: §22.7 (Motor de regras de compliance — Eixo A)
está consolidada no documento-mestre, o nome **O Plenário** entrou em §1, e o histórico (§24)
registra o bump. As listas granulares da DSL ficaram parqueadas em §22.7.4 ("a transcrever da
sessão de origem") — não inventadas.

Próxima ação por decisão do Emilio: **trilha de produto/comercial** (mercado, concorrência, PRD,
posicionamento). A trilha de arquitetura fica em pausa; quando retomar, o próximo eixo é o **C**
(brief em `docs/03-proxima-sessao-eixo-C.md`), com o pré-requisito já cumprido.
