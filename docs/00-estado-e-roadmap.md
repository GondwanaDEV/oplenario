# Estado atual e roadmap de §22.7

Documento de situação: onde o discovery está agora, o que falta para fechar a seção ativa
(§22.7 — motor de regras de compliance), e o que está parqueado.
Documento-mestre de referência: **v1.12** (20/06/2026).

---

## 1. Resumo em uma frase

Estamos na **North Star Architecture**, com §22.1–§22.6 e **§22.7 Eixos A, C, B e o Eixo de runtime**
fechados e consolidados no documento-mestre (**v1.12**). A trilha de **produto/comercial** foi
concluída (pasta `produto/`, discovery completa em 20/06/2026). O **Eixo C** validou a forma A2
(§22.7.5); o **Eixo B** cravou o **schema estático** do motor (**§22.7.6**): separar **definição da
regra** (domínio, sem `ente_id`) de **binding por tenant** (com `ente_id`) — divergência consciente de
§22.4 justificada por S2; cinco tabelas + registry como catálogo de infra. O **Eixo de runtime**
cravou o **comportamento temporal** (**§22.7.7, v1.12**, elevado por S1): obrigação temporal em dois
sabores (com prazo materializa, contínua só avalia); **`prazo_dominio_ativo` polimórfico**
(generalização de disc. 6); ciclo enum fixo, não template; `compliance_avaliacao` append-only = prova
de compliance. A **primeira implementação de fato** já existe — o **avaliador executável da DSL** em
`motor-dsl/` (zero-dep Python; 39 checagens verdes) validou *end-to-end* a forma A2 + o loop de runtime
(type-check do save time dos 4 templates, rejeição de regras mal-tipadas, dois sabores de obrigação,
re-stamp S3, auditoria append-only). **Próximo: +2 eixos de arquitetura** (geração de artefatos de
envio ao TCE; expansão a outros TCEs).

---

## 2. O que está fechado e consolidado (§22.1–§22.6)

§22 do documento-mestre é a referência canônica. Já fechado e escrito:

- **§22.1 — 10 invariantes arquiteturais.** Multi-ente desde o dia 1, domain events como
  cidadão de primeira classe (sem event sourcing completo), IA como plataforma, **regras de
  compliance são dados e não código (Invariante 4)**, separação rígida core ↔ presentation,
  soberania de dados BR, observabilidade como quatro coisas distintas, `ente_id` em todo
  sinal, SLIs de negócio de primeira classe, audit log como domínio de produto.
- **§22.2 — alto nível.** Tenancy (shared DB + RLS + `ente_id` na V1; pool-per-UF anos 3-5);
  core monolítico modular + Plataforma de IA como satélite desde o dia 1; ingestão de legado
  via endpoints explícitos com marcador `origem`.
- **§22.3 — contrato core ↔ Plataforma de IA.** Topologia híbrida (sync/async/streaming),
  protocolos concretos (HTTP/JSON+OpenAPI, bus de eventos + filas, SSE), buses lógicos
  separados com eventos de integração como contrato, propriedade de dados por natureza do
  artefato, taxonomia de seis categorias de falha.
- **§22.4 — modelo de dados do processo legislativo.** STI híbrido para proposições,
  versionamento de texto append-only, **máquina de estados declarativa com DSL pequena
  (eixo C)**, emendas como entidade própria, apensação via tabela de associação histórica,
  votos secretos em tabela separada, UUID + numeração canônica, disciplinas transversais.
- **§22.5 — autenticação e autorização.** gov.br para cidadão, senha+MFA/TOTP para
  servidor/vereador, WebAuthn obrigatório para admin interno; **RBAC + funções de relação +
  DSL compartilhada (eixo B)**; mandato como entidade com cascata; ICP-Brasil estritamente
  para assinatura digital; taxonomia de eventos de auth em quatro classes com retenção LGPD diferenciada.
- **§22.6 — sessão plenária + áudio + real-time.** Três entidades temporais explícitas,
  pauta versionada híbrida, presença como eventos append-only, ingestão de áudio/vídeo
  agnóstica à fonte, transcrição na granularidade de palavra na Plataforma de IA, **SSE como
  protocolo único de real-time**, geração automática de ata fora da V1 (anexação de ata
  externa suportada). **Regras de plenário — quórum, regras de votação por matéria, tempos
  de tribuna — decididas como configuração no motor declarativo** (entram na conta da DSL compartilhada).

---

## 3. ✅ O descompasso reconciliado (em v1.9 — 19/06/2026)

O descompasso que era o ponto mais delicado deste handoff **foi fechado**. O que se fez:

1. **§22.7 (Motor de regras de compliance — Eixo A)** consolidada no documento-mestre, no estilo
   das demais subseções 22.x (visão geral → decisões → disciplinas derivadas → pontos a confirmar).
2. **Nome O Plenário** adicionado em §1, como decisão provisória até os checks de domínio/INPI.
3. **Bump v1.9** registrado em §24; antiga §22.7 "Itens parqueados" reestruturada (compliance
   virou subseção própria; LLM/soberania desceu para a nova **§22.8**).

**Ressalva importante (registrada em §22.7.4):** a sessão de origem do Eixo A é posterior ao v1.8
e seu **detalhe granular não estava no material de handoff**. Por isso, as listas exatas (tipos,
operadores, builtins, schemas de envelope, mecânica fina do registry, opções de forma descartadas)
foram **explicitamente parqueadas como "a transcrever da sessão de origem"** — não inventadas.
Serão preenchidas quando a sessão for recuperada, ou validadas/descobertas pelo stress-test do
Eixo C. Até lá, o que está canônico em §22.7 é o **estrutural** (forma A2, type-check no save time,
registry central, envelopes por contexto, as 4 disciplinas).

---

## 4. Roadmap de §22.7 (eixos)

§22.7 é trabalhada por eixos. Ordem **deliberadamente não-sequencial** (decisão do Emilio):

| Eixo | Tema | Status |
|---|---|---|
| **A** | Vocabulário da DSL (tipos, operadores, builtins, registry de funções de relação, envelopes por contexto) | ✅ **Consolidado** em §22.7 (v1.9). Listas granulares "a transcrever" em §22.7.4 |
| **C** | Templates de regra do TCE-CE como **stress-test** da DSL | ✅ **Consolidado** em §22.7.5 (v1.10). Forma A2 validada; vocabulário derivado de carga real; achados S1–S4. Rascunho: `docs/05-eixo-C-stress-test-rascunho.md` |
| **B** | Schema das tabelas de template/regra | ✅ **Consolidado** em §22.7.6 (v1.11). 5 tabelas; definição (domínio, sem `ente_id`) vs. binding (tenant); registry = catálogo de infra. Rascunho: `docs/06-eixo-B-schema-rascunho.md` |
| **Runtime** | Comportamento temporal do motor (materialização/avaliação/monitoramento de prazo/auditoria) | ✅ **Consolidado** em §22.7.7 (v1.12, elevado por S1). 2 tabelas de runtime (tenant): `prazo_dominio_ativo` polimórfico (disc. 6) + `compliance_avaliacao` append-only. Ciclo enum fixo (não template); modelo evento+sweep+sob demanda. Rascunho: `docs/07-eixo-runtime-motor-rascunho.md` |
| **Impl** | Avaliador executável da DSL (primeira implementação de fato, §7) | ✅ **Construído** em `motor-dsl/` (zero-dep Python, 39 checagens verdes). Parser + type-checker do save time + loop de runtime; validou A2 + §22.7.7 end-to-end. Protótipo de validação, não decisão de stack |
| **(+2)** | Eixos de arquitetura restantes: geração de artefatos de envio ao TCE; expansão a outros TCEs | 🎯 **Em aberto** — escopos a confirmar e não assumir. Runtime fechou *comportamento temporal* + *auditoria*; *versionamento* fechou no Eixo B |
| LLM/soberania | Provedores/modelos de LLM + implicações LGPD (§22.8 item 1) | 🅿️ Parqueado |

**Por que C antes de B:** validar que a forma da DSL fechada no Eixo A realmente expressa os
requisitos reais do TCE-CE **antes** de gastar esforço modelando o schema das tabelas. Se o
stress-test revelar lacuna na DSL, corrige-se a DSL (volta ao Eixo A) com custo baixo; se a
DSL aguentar, o schema do Eixo B nasce sobre fundação validada.

**Sobre os "+5 eixos" (agora +2):** a sessão de abertura identificou cinco eixos além de A/B/C.
**Três já fecharam:** o **Eixo de runtime (§22.7.7, v1.12)** absorveu *tratamento de prazos de
compliance* (generalização de `prazo_dominio_ativo`, disc. 6) + *comportamento temporal do motor*
(avaliação/agendamento/monitoramento de prazo) + *auditoria/rastreabilidade de avaliação de regra*;
*versionamento de regras de compliance* fechou no **Eixo B** (cópia integral, §22.4 eixo C). **Restam
+2**, escopos a **confirmar, não assumir**: geração de artefatos/relatórios de envio ao TCE no formato
exigido (gera o *arquivo*; o runtime só rastreia a *obrigação de enviar*); estratégia de expansão para
outros estados (TCE-CE → 27 TCEs sem refactor, Invariante 4 — S2 mostrou `dominio` em camadas).

**Itens parqueados de §22.7 (não abrir agora):** (1) materialização concreta do motor — em
andamento via eixos A/B/C; (2) provedores/modelos de LLM e soberania (Invariante 6) —
gerenciado vs. self-hosted, modelo por feature, custo, LGPD; ligado ao invariante de soberania
de dados BR; não é pré-requisito do motor de compliance, abre em sessão própria.

---

## 5. Disciplinas que atravessam para o compliance

Vêm de §22.4.3 e §22.5.3 e são **arrastadas explicitamente** para §22.7 (não se decide de novo):

- **Motor declarativo compartilhado** (§22.4.3 disc. 5 / §22.5.3 disc. 3): a DSL e a mecânica
  de avaliação do compliance são as **mesmas** de tramitação, autorização e regras de plenário.
  É o que o Eixo A formaliza. Compliance **não ganha DSL própria**.
- **Tunables como configuração, não código** (§22.5.3 disc. 7): parâmetros operacionais de
  compliance (prazos, limiares) são dados configuráveis com defaults seguros — bate com o
  próprio Invariante 4.
- **Padrão "prazo de domínio"** (§22.4.3 disc. 6): prazos de envio ao TCE são candidato natural
  à generalização de `proposicao_prazo_ativo` → `prazo_dominio_ativo` polimórfico.
- **Taxonomia de imutabilidade** (§22.4.3 disc. 4) e **convenção de campos transversais**
  (§22.4.3 disc. 1): valem para as tabelas de template/regra que o Eixo B vai modelar.
- **Versionamento de template por cópia integral** (§22.4 eixo C): provável base para o
  versionamento de regras de compliance.

---

## 6. Onde o compliance toca o resto da arquitetura (já decidido)

Para não reabrir o que já está fechado, mapa de pontos de contato:

- **§22.6 — regras de plenário** (quórum, regras de votação por matéria, tempos de tribuna)
  foram decididas como **configuração no motor**. Entram na conta da DSL compartilhada. ✅
  **Resolvido no Eixo C (§22.7.5 S4):** usam o envelope de **_guard_ de tramitação/plenário**,
  **não** o de compliance — semântica de "ação válida agora?", não "obrigação com prazo". Núcleo
  compartilhado; envelopes distintos.
- **§17 / §16.10 — recorte do que produzimos para o TCE-CE**: atos, portarias, resoluções
  legislativas, diárias de vereadores via dado consumido pela folha existente. **TCE-CE
  totalmente coberto na V1**; arquitetura preparada para outros estados, conteúdo não.
- **Invariante 4** governa tudo: TCE-CE na V1 é configuração; expansão para 27 estados é
  conteúdo novo (dados), não refactor estrutural (código).

---

## 7. O que NÃO mudou e segue valendo

- Toda a estratégia (Rota D, sequenciamento 8-12 anos, três apostas, três públicos, ondas de
  roadmap) — §1–§21 do documento-mestre, intocadas.
- Régua de escopo da V1 (§15) e sua aplicação aos casos borderline (§17).
- Satélites já decididos como fora da V1: Migração (§16.9), Plugin de Captura Sincronizada
  (§16.4), Geração de Ata Automática (decisão v1.7).
- Decisões deferidas a outros chats (stack, dimensionamento de time, jurídico, especialista em
  regimento) — §19 e os blocos "decisões deferidas" de cada subseção de §22.

---

## 8. Pendências maiores fora de §22.7 (depois da North Star)

Da §19 do documento-mestre — **derivadas da North Star, não se antecipam**:

- **Stack técnico da V1** (linguagem, framework, banco, infra de IA, cloud) — próxima fase do
  trabalho de arquitetura, depois de fechar a North Star. Vários blocos de §22 deixam decisões
  concretas explicitamente "para o chat de stack/infra".
- **Dimensionamento de time para a V1** — derivado de stack + escopo.
- Itens estratégicos/comerciais (ordem dos módulos administrativos pós-V1, mapa competitivo,
  recalibragem do beachhead, modelo comercial vs. ciclo de pregão) — sessões próprias, fora do
  discovery de arquitetura.

---

## 9. Pontos a confirmar com especialistas (registrados, não inventar)

Espalhados pelos blocos "decisões deferidas e pontos a confirmar" de cada subseção de §22.
Os que tocam compliance / regimento mais de perto:

- **Especialista em regimento** (a contratar cedo, §10): "comissões obrigatórias por matéria" é
  configuração estática por (ente, tipo) ou depende da matéria via expressão DSL? — pergunta já
  registrada em §22.4.4 e diretamente relevante para a forma das regras de compliance.
- **Revisão jurídica** (direito digital + administrativo): pisos legais de retenção (§22.5.2
  eixo G), e qualquer interpretação de obrigação regulatória que vire regra de compliance,
  precisam de validação jurídica — o discovery modela a forma, o jurídico valida o conteúdo.
