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
| [0007](0007-captacao-da-gravacao-local.md) | Captação da gravação local: `apps/captacao`, papel `captacao` e vínculo pós-sessão | Aceito |
| [0008](0008-fronteira-core-ia-eventos-de-integracao.md) | Fronteira core ↔ IA: eventos de integração por feed e caixa de entrada, conteúdo sob demanda | Aceito |
| [0009](0009-catalogo-de-acoes-e-adaptador-mcp-no-core.md) | Catálogo de ações no core: uma entrada por ação de domínio, `diplomat/catalogo.clj` e lint de rotas | Aceito |
| [0010](0010-identidade-delegada-do-agente.md) | Identidade delegada do agente: credencial opaca emitida pelo core, ator com `:via`, interseção a cada chamada | Aceito |
| [0011](0011-normas-de-referencia-por-dispositivo.md) | Normas de referência por dispositivo: módulo `normas`, parser determinístico e conferência humana | Aceito |
| [0012](0012-proposta-de-ato.md) | Proposta de ato: o agente prepara, a pessoa confirma na tela | Aceito |
| [0013](0013-agente-institucional-e-conferencia.md) | Agente institucional da Casa (concessão do admin) e a conferência das proposições | Aceito |
| [0014](0014-orcamento-de-ia-e-painel-da-casa.md) | Orçamento de IA por Casa, a cota no satélite e o painel da Casa | Aceito |
| [0015](0015-cidadao-entra-pelo-govbr.md) | O cidadão entra pelo gov.br: broker no realm da Casa, sessão só de cidadão | Aceito |
| [0016](0016-operador-da-plataforma-e-registro-de-casas.md) | O operador da plataforma: realm próprio com chave física, e o registro de Casas por handoff | Aceito |
| [0017](0017-trilha-de-auditoria-da-casa.md) | A trilha de auditoria da Casa: o que registra, quem vê, quanto tempo, LGPD e o selo | ✅ Aceito |
| [0018](0018-suspender-e-encerrar-casa.md) | Suspender e encerrar uma Casa: o que cada estado faz, quem decide e o que acontece com os dados | ✅ Aceito (fatias 1 e 2 implementadas: suspender, reativar e encerrar com exportação e apagamento) |
| [0019](0019-parecer-juridico-e-o-caminho-da-materia-ate-a-pauta.md) | Parecer jurídico e o caminho da matéria até a pauta (comissão, jurídico, IA como rascunho, publicar a pauta) | ✅ Aceito (fatias 1–4 implementadas; a etapa obrigatória fica desligada por padrão) |
| [0020](0020-comunicados-internos-e-setores.md) | Comunicados internos da Casa, com setores e prova de leitura (recebido, lido, ciente) | ✅ Aceito (fatias 1–3 implementadas: setores, comunicados, caixa para todos, anexos, avisos automáticos, prazo de ciência) |
| [0021](0021-audiencia-publica-e-julgamento-das-contas.md) | Audiência pública (tipo de sessão, inscrição do cidadão pelo gov.br, metas fiscais da LRF no motor) e julgamento das contas do Prefeito (parecer prévio, PDL, defesa, 2/3 para rejeitar) | ✅ Aceito (partes A e B + regras no motor implementadas) |
| [0022](0022-indeferir-ciencia-da-prorrogacao-e-anexos-no-balcao.md) | Balcão de atendimento: indeferir (e-SIC e LGPD), ciência da prorrogação ao requerente e anexos da Casa e do requerente | ✅ Aceito (implementado) |
| [0023](0023-openrouter-como-fornecedor-de-modelo-de-linguagem.md) | OpenRouter como fornecedor de modelo de linguagem da plataforma: adaptador por HTTP, ZDR e sem coleta travados, provedor e custo declarados na proveniência | ✅ Aceito (implementado; ligar em produção espera o `[GAP]` jurídico) |
| [0024](0024-entrada-pelo-cpf-e-o-keycloak-escondido.md) | Entrada pelo CPF (CPF no O Plenário, senha na Câmara, `login_hint`), o Keycloak com o tema do O Plenário, o realm com nome, pt-BR e trava de força bruta, e o primeiro acesso com senha + código | ✅ Aceito (implementado; produção espera a imagem do Keycloak com o tema e o reprovisionamento dos realms) |
