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
