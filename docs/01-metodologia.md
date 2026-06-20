# Metodologia de trabalho — sessões de arquitetura

Como as sessões de North Star Architecture funcionam neste projeto. Seguir este método é o
que mantém a continuidade e a qualidade do discovery ao longo das sessões.

---

## 1. Princípio central: o documento-mestre é o artefato de handoff

Cada sessão fecha **um tópico arquitetural macro** e produz um documento-mestre atualizado.
O documento é a memória de longo prazo do projeto — não a conversa. Por isso:

- No início de cada sessão, o documento-mestre é o contexto canônico.
- No fim de cada sessão, as decisões fechadas são **escritas no documento-mestre** com bump
  de versão e registro no histórico de revisões (§24).
- "Em caso de conflito entre um chat antigo e este documento, o documento prevalece" (§23).
  Decisão que não foi escrita no documento **não está consolidada** — ver o descompasso do
  Eixo A em `docs/00-estado-e-roadmap.md`.

---

## 2. Um tópico macro por sessão

A North Star foi construída assim: um bloco arquitetural por sessão, fechado antes de seguir.
Exemplos já fechados: contrato core ↔ IA (§22.3), modelo de dados legislativo (§22.4), auth
(§22.5), sessão plenária (§22.6). A sessão ativa é o motor de compliance (§22.7).

Não se mistura dois macro-temas na mesma sessão. Se um tema puxa outro, o segundo vira
parqueado para sessão própria, não galho da atual.

---

## 3. Fluxo eixo por eixo

Dentro de um tópico macro, o trabalho é por **eixos**:

1. **Abrir o eixo** — enunciar a questão e as opções reais (geralmente 2-4), com tradeoffs.
2. **Debater explicitamente** — comparar opções, trazer o custo de cada uma, descartar as
   inviáveis com razão registrada. Decisões descartadas ficam nomeadas no documento (ex.:
   "gRPC explicitamente avaliado e descartado para a V1 porque…").
3. **Chegar a decisão confirmada** — Emilio confirma (protocolo "Confirmo" / "Confirma?").
4. **Consolidar** — a decisão entra na síntese da subseção. **Item fechado não se relitiga**
   em sessões seguintes.

A estrutura final de cada subseção 22.x segue sempre o mesmo padrão:
**visão geral e taxonomia → decisões por eixo → disciplinas arquiteturais derivadas →
decisões deferidas e pontos a confirmar.**

---

## 4. Protocolo de confirmação

- Emilio **intervém com correções cirúrgicas** e espera **incorporação imediata** — não
  "vou anotar para depois", mas ajuste no mesmo turno.
- O Claude deve **pedir confirmação antes de prosseguir** quando uma decisão foi tomada
  ("Confirma?"), e seguir só após o "Confirmo".
- Quando Emilio aponta um erro de framing ou de conteúdo, o ajuste é refletido no texto
  consolidado, não apenas reconhecido na conversa.

---

## 5. Versionamento: bump vs. patch

- **Bump de versão** (v1.7 → v1.8): quando a sessão adiciona/fecha uma subseção nova ou muda
  uma decisão consolidada.
- **Patch de mesma versão**: correção cirúrgica de framing dentro de uma versão ainda
  "fresca", antes que se enraíze como premissa de outras decisões (há precedente explícito:
  o "patch de framing" aplicado em 29/04/2026 dentro da v1.6, sem bump).
- Todo bump entra no **histórico de revisões (§24)** com descrição do que mudou, por seção.

---

## 6. Disciplina de escopo: a régua das 4 perguntas (§15)

Filtro permanente contra inflação de escopo da V1. Para qualquer "pedacinho" que alguém
queira adicionar:

1. Sem X, o fluxo legislativo central quebra? Se não → **fora**.
2. X pode ser resolvido por integração com sistema que a câmara já tem? Se sim → **fora**
   (constrói conector, não módulo).
3. Construir X mínimo custa menos de 2 semanas de engenharia? Se sim → entra com escopo
   mínimo **explícito**.
4. Adiar X por 12 meses faz a câmara desistir de comprar? Se não → **roadmap futuro**.

Corolário arrastado: **itens sem requisito de cliente validado são parqueados, não
pré-construídos.** Satélite separado é o padrão para o que é real mas não cabe na V1
(Migração, Plugin de Captura Sincronizada, Geração de Ata Automática seguem esse padrão).

---

## 7. Viés por consistência disciplinar

Forte preferência por **estender padrões existentes** em vez de introduzir conceitos novos.
Quando uma decisão nova aparece, a primeira pergunta é "que disciplina já estabelecida cobre
isto?". Exemplos de disciplinas que se repetem e viram regra geral:

- **DSL e motor declarativo compartilhados** entre tramitação (§22.4 eixo C), autorização
  (§22.5 eixo B), regras de plenário (§22.6) e compliance (§22.7). Uma DSL, não várias.
- **Taxonomia de imutabilidade em três níveis** (§22.4.3): append-only puro / mutação
  controlada com travamento por estado / mutação parcial.
- **Convenção de campos transversais** (`created_at`, `updated_at`, `created_by`,
  `updated_by`, `lock_version`, `origem*`) em toda tabela de domínio.
- **`instante` como parâmetro** em toda função de relação (consulta histórica sempre possível).
- **Tunables operacionais como configuração com defaults**, nunca hardcode (bate com Invariante 4).

---

## 8. Tom e comunicação

- **Português** em toda sessão técnica.
- Conciso. Emilio é **intolerante a complexidade desnecessária e a framing rebuscado** —
  vai cortar ornamento e pedir o essencial.
- Honestidade técnica acima de agradar: trazer o custo real de cada opção, inclusive das que
  o Claude recomendaria.

---

## 9. Pontos recorrentes a confirmar com especialistas (após contratação)

Vários blocos de §22 deixam perguntas explícitas para o **especialista em regimento
legislativo** (a contratar cedo — ver §10 do documento-mestre) e para **revisão jurídica**
(direito digital + administrativo). Esses pontos estão listados nos blocos "decisões
deferidas e pontos a confirmar" de cada subseção. Não inventar respostas para eles no
discovery — registrar como pendência de validação.
