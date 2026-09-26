# ADRs — Architecture Decision Records

Registro das **decisões de arquitetura** da plataforma, no formato curto (Contexto · Decisão ·
Enforcement · Consequências · Alternativas descartadas). Complementa a SSOT (`documento-mestre-camaras.md`
+ `arquitetura/`): a SSOT é a *espinha*; cada ADR é o *porquê* navegável de uma decisão estrutural.

## Convenção
- Arquivo: `docs/adr/NNNN-titulo-em-kebab.md` (NNNN sequencial, 4 dígitos).
- Status: `Proposto` → `Aceito` → (`Substituído por ADR-MMMM` | `Descartado`). ADR aceita é **imutável**;
  mudança vira ADR nova que **supersede** a anterior (não se reescreve a antiga).
- Em conflito com memória de chat, a ADR + SSOT prevalecem.

## Índice
| ADR | Título | Status |
|---|---|---|
| [0001](0001-estrutura-de-pastas-e-silhueta-de-modulo.md) | Estrutura de pastas e silhueta de módulo | Aceito |
| [0002](0002-identidade-canonica-numeracao-urn-imutabilidade.md) | Identidade canônica, numeração, URN e imutabilidade | Aceito |
| [0003](0003-camada-relacoes-pode-tocar-o-db-do-proprio-modulo.md) | `relacoes/` pode importar o `db/` do próprio módulo | 🟡 Rascunho |
| [0004](0004-a-guarda-de-transicao-so-le-verdade-apurada.md) | A guarda de transição só lê verdade apurada | Aceito |
| [0005](0005-conceder-acesso-e-area-do-admin-ente.md) | "Conceder acesso" pertence à área do `admin_ente`, não à tela de cadastro | Aceito |
| [0006](0006-satelite-de-ia-apps-ia.md) | Satélite de IA em `apps/ia/` (Python): silhueta, porta de inferência e trilho de CI | Aceito |
