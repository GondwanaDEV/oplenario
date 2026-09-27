# ADR-0014 — Orçamento de IA por Casa, a cota no satélite e o painel da Casa

- **Status:** aceita (27/09/2026)
- **Contexto de decisão:** docs/25 Eixo 8 (CONFIRMADO em 26/09/2026 — 8.1 B, 8.2, 8.3, 8.4, 8.5), plano docs/26 B.9
- **Relacionadas:** ADR-0006 (satélite e núcleo), ADR-0008 (fronteira por feed), ADR-0012/0013 (proposta, agente
  institucional)

## Contexto

A base comum já registrava tokens e custo de cada execução por Casa (0.5), sem limite. O Eixo 8.3 decidiu: orçamento
mensal por Casa com aviso a 80%; ao estourar, o **segundo plano** (o que a IA faz sozinha) pausa primeiro; o que a
pessoa pede segue até um **teto duro**, e então "IA indisponível — cota da Casa" com a tela fazendo tudo (R-IA-1);
nunca trocar para modelo pior em silêncio. O 8.4 pede o painel da Casa (consumo × orçamento, aceitação por capacidade,
erros reportados) para o `admin_ente`; o 8.1, avaliação de segurança do agente no CI. Os **valores** do orçamento
dependem do preço do plano — `[GAP]` comercial.

## Decisão

1. **O orçamento é do core**, definido pelo **operador** conforme o plano: `integracao_ia.orcamento_ia` (mig 0098,
   tenant, histórico append-only; a definição mais recente vale; teto ≥ mensal), pelo subcomando
   `oplenario.main ia-orcamento <ente> <mensal> <teto-duro> [moeda]`. Não há tela para isso (o console do operador é
   um stub); o `admin_ente` vê, não muda — os valores são do contrato. A moeda é a da tabela de preços do satélite
   (hoje USD). Sem definição, a Casa só mede.
2. **A definição vai à IA pelo feed** como `OrcamentoIADefinido` v1, gravada na mesma transação. Não há evento de
   domínio intermediário: o orçamento já é configuração da própria fronteira com a IA. Valores como texto decimal (o
   satélite lê `Decimal`); um evento atrasado nunca volta o orçamento para trás.
3. **A cota é aplicada no satélite, pelo núcleo**, antes de chamar o fornecedor: estado = `sem_orcamento` |
   `normal` | `aviso` (≥ 80%) | `segundo_plano_pausado` (≥ mensal) | `esgotada` (≥ teto). Segundo plano = `resumo.redigir`
   e `conferencia.redigir`. Cota fechada → nada vai ao fornecedor, a execução é registrada com decisão `cota` e o
   resultado é o R-IA-1 `cota` ("cota da Casa"). Gasto = soma do custo **conhecido** das execuções do mês civil da Casa
   (America/Fortaleza). Medição fora do ar **não** para a Casa (a execução segue, com log).
4. **O registro da Camada de Confiança passa a Postgres** (`ia.registro_evento`, schema do satélite, append-only, sem
   conteúdo) — o **mesmo** para a API e o trabalhador, senão a cota não veria o gasto do outro processo.
5. **Trabalho de segundo plano que esbarra na cota PAUSA**, não falha: volta à fila em 1 h sem contar tentativa, e
   ninguém é avisado de falha. Quando a cota reabre (mês novo ou plano maior), ele sai sozinho.
6. **O painel da Casa** (`GET /paineis/ia?mes=AAAA-MM`, `admin_ente`, módulo `paineis` por seams do host): o orçamento
   (core), o consumo e o estado (o que o satélite está aplicando — `GET /v1/entes/:e/consumo`), por capacidade:
   usos, os que não rodaram, custo, revisão humana e erros reportados; e o que a Casa fez com o que a IA entregou
   (notas técnicas aproveitadas/descartadas; propostas confirmadas/recusadas/expiradas). IA fora: o painel mostra o
   que o core sabe e diz que o consumo está indisponível — não inventa gasto. Só contagens e valores (8.5). Tela
   `/paineis/ia`; a entrada "IA da Casa" na navegação só aparece para o `admin_ente`.
7. **Avaliação do agente no CI** (8.1): conjunto `avaliacoes/agente-seguranca.json` (`"nivel": "agente"`) que roda o
   laço inteiro contra um MCP roteirizado — instrução escondida em conteúdo de terceiro não vira ação e contamina a
   execução, ato só vira proposta, voto não existe para o agente, resultado restrito nunca chega ao fornecedor, sem o
   ato no catálogo nada é chamado. Com o fake confere o pipeline; com fornecedor real é gate antes de trocar modelo.

## Consequências

- Os valores do orçamento de cada Casa seguem `[GAP]` comercial; o mecanismo está pronto e testado ponta a ponta
  (demo: orçamento 100/120, gasto 85,50 → aviso; orçamento 80 → resumo e conferência pausados).
- O fornecedor fake custa zero: sem fornecedor real, o gasto real da Casa é zero.
- **Fica para depois:** aviso ativo ao `admin_ente` (notificação) ao passar de 80% — hoje o painel mostra;
  a tela `observabilidade-ia` do operador (fast-follow, R-IA-3/4); taxa de aceitação medida de volta na IA para resumo
  e nota técnica (hoje só a ata); a camada robusta de avaliação (juiz automático sobre amostras) na Onda 2.
