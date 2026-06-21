# 22.7 Motor de regras de compliance

> *Parte do SSOT (documento-mestre). Versão canônica do conjunto em `documento-mestre-camaras.md` §24.*
> *Em conflito com memória de chat antigo, este arquivo prevalece.*

Esta subseção materializa o Invariante 4 (regras de compliance são dados, não código): na V1, o TCE-CE é **configuração**, não branch de código, e a arquitetura comporta os outros 26 TCEs como **conteúdo novo (dados)**, sem refactor estrutural. O bloco é trabalhado **por eixos**, em ordem deliberadamente não-sequencial (decisão de método): **A** (vocabulário da DSL) → **C** (stress-test com templates reais do TCE-CE) → **B** (schema das tabelas), seguido de cinco eixos adicionais. A inversão C-antes-de-B é proposital: validar que a forma da DSL expressa requisitos reais **antes** de cravar schema. Esta subseção consolida os **Eixos A (§22.7.2–22.7.3), C (§22.7.5), B (§22.7.6) e o Eixo de runtime (§22.7.7)**: o Eixo C **validou a forma A2** contra requisitos reais do TCE-CE e **derivou de carga real** o vocabulário que a §22.7.4 deixara parqueado; o Eixo B **cravou o schema estático** das tabelas de template/regra sobre esse vocabulário; o **Eixo de runtime** (primeiro dos +5, elevado por S1) cravou o **comportamento temporal** do motor — materialização de obrigação, avaliação, monitoramento de prazo e auditoria. A geração de artefatos de envio ao TCE (§22.7.8) e a expansão a outros TCEs (§22.7.9) fecham os eixos restantes — **§22.7 está completo** (roadmap em §22.7.4).

## 22.7.1 Visão geral: a DSL é unificada aqui, não nasce aqui

A DSL declarativa **já existia** em três contextos antes de §22.7, sob a disciplina "motor declarativo compartilhado" (§22.4.3 disc. 5; §22.5.3 disc. 3), que sempre exigiu que fosse **uma só**. O Eixo A **formaliza o vocabulário comum** sobre o qual os quatro usos se apoiam:

- **Tramitação (§22.4 eixo C):** expressões booleanas pequenas — sem loops, sem variáveis mutáveis, sem efeitos colaterais; ações são identificadores que o motor mapeia para handlers; agregadores sobre subprocessos expostos como funções (`pareceres.todos_concluidos`, `pareceres.algum_rejeitou`, `pareceres.contagem_terminal >= N`).
- **Autorização (§22.5 eixo B):** decisões dinâmicas avaliadas como expressões sobre **funções de relação** expostas pelos bounded contexts donos dos recursos.
- **Regras de plenário (§22.6):** quórum, regras de votação por matéria e tempos de tribuna como configuração no motor, expondo agregadores como `presentes_plenario(sessao, instante)` e `presentes_remoto(sessao, instante)`.
- **Compliance (§22.7 — novo):** o envelope que o Eixo C vai estressar com templates reais do TCE-CE.

O Eixo A é a base que torna esses quatro usos **uma coisa só**, não quatro DSLs parecidas.

## 22.7.2 Decisões do Eixo A (vocabulário da DSL)

1. **Forma "A2" — núcleo de expressão + envelopes YAML por contexto.** Um **núcleo de expressão** comum (a gramática booleana/valor sem efeitos colaterais herdada de §22.4 eixo C) embrulhado por **envelopes em YAML específicos por contexto**. O núcleo é o mesmo para todos; o envelope adapta forma e campos ao contexto. *(Opções alternativas de forma avaliadas e descartadas: a transcrever — §22.7.4.)*
2. **Type-checking estático no momento de salvar a regra.** A regra é tipada estaticamente no **save time**, não só na avaliação; regra mal-formada/mal-tipada **não chega a persistir como ativa**. Justificativa comercial que governa §22.7: uma regra falhando em runtime e fazendo um cliente **perder janela de envio ao TCE** é incidente inaceitável — empurrar a detecção para o save time tira essa classe de falha do caminho crítico. Bate com o Invariante 4 (regra é dado, mas **dado validado** antes de virar configuração ativa).
3. **Registry central de funções de relação, com ownership por bounded context.** Existe um **registry central** onde a DSL resolve o significado e a assinatura de cada função de relação; a **propriedade (implementação e manutenção)** de cada função pertence ao **bounded context dono do recurso**. Consistente com §22.5 eixo B. Alimenta o type-checker do save time (decisão 2).
4. **Sistema de tipos com primitivos e compostos.** O Eixo A fechou um sistema de tipos com tipos primitivos e compostos, usados pelo type-checker para validar expressões e chamadas. *(Lista exata de tipos e regras de coerção: a transcrever — §22.7.4.)*
5. **Conjunto de operadores núcleo.** Operadores lógicos, de comparação e demais definidos, coerentes com a natureza "sem loops, sem mutação, sem efeitos colaterais". *(Lista exata e precedência: a transcrever — §22.7.4.)*
6. **Inventário de funções builtin.** Biblioteca da própria DSL — distinta das funções de relação dos bounded contexts — cobrindo manipulação de datas/prazos, agregação sobre coleções e utilitários. *(Lista exata e assinaturas: a transcrever — §22.7.4.)*
7. **Schemas de envelope por contexto: compliance, tramitação, autorização.** Cada contexto tem um schema de envelope próprio em torno do núcleo. **Tramitação** formaliza o que §22.4 eixo C descreve (template, estado, transição, guard, ação); **autorização** formaliza §22.5 eixo B (política por ação sobre funções de relação); **compliance** é o envelope novo, alvo do Eixo C. *(Schema concreto de cada envelope, em especial compliance: a transcrever — §22.7.4.)*

## 22.7.3 Disciplinas arquiteturais derivadas

1. **Uma DSL, um núcleo, múltiplos envelopes.** Não há quatro DSLs; qualquer evolução do núcleo é feita uma vez e propaga para todos os contextos.
2. **Validação no save time é disciplina, não otimização.** Regra mal-tipada não persiste como ativa; o type-checker é parte do contrato de salvar regra.
3. **Registry central, ownership distribuído.** Resolução central de assinaturas; implementação e manutenção no contexto dono. Promover ou alterar uma função de relação é decisão do contexto dono.
4. **Ferramental de simulação/debug compartilhado** entre os usos da DSL, consistente com a promessa de §22.4.3 disc. 5.

## 22.7.4 Decisões deferidas e pontos a confirmar

**A transcrever da sessão de origem (não consolidado como canônico no v1.9).** A sessão que fechou o Eixo A é posterior ao v1.8 e seu detalhe granular **não está no material de handoff** — só o registro-resumo. Ficam pendentes de transcrição literal, **a confirmar antes de cravar**: a lista de tipos primitivos/compostos e regras de coerção (decisão 4); a lista de operadores núcleo e precedência (5); o inventário de builtins e assinaturas (6); o schema concreto dos envelopes, em especial o de compliance (7); a mecânica fina do registry — visibilidade entre contextos e versionamento de assinatura (3); e as opções de forma descartadas no Eixo A com o porquê (1). **Estes itens não foram inventados deliberadamente.** **Status pós-Eixo C (v1.10):** o stress-test (§22.7.5) **derivou de carga real** a maior parte deste vocabulário — tipos (4), operadores (5), builtins (6) e a forma do envelope de compliance (7) estão agora **listados em §22.7.5, justificados por requisito**, não mais por memória. O que **permanece a reconciliar** contra a sessão de origem: a mecânica fina do registry (3 — visibilidade entre contextos e versionamento de assinatura) e as opções de forma descartadas no Eixo A (1). O detalhe que falta é **complemento**, não **bloqueio** — a forma A2 já está validada.

**Roadmap dos eixos seguintes.** **Eixo C** ✅ **concluído (v1.10, §22.7.5)** — forma A2 validada. **Eixo B** ✅ **concluído (v1.11, §22.7.6)** — schema estático: definição (sem `ente_id`) + binding por tenant + registry como catálogo de infra + calendários; runtime roteado a um +5 eixo. **Eixo de runtime** ✅ **concluído (v1.12, §22.7.7)** — comportamento temporal: materialização de obrigação (`prazo_dominio_ativo` polimórfico, disc. 6), modelo de avaliação (evento + sweep + sob demanda), monitoramento de prazo (S1, com re-stamp no deslize de circular) e auditoria append-only (`compliance_avaliacao` = prova de compliance). **Fechou dois** dos +5 candidatos — *comportamento temporal* (S1) + *auditoria de avaliação*; **versionamento** já fora fechado pelo Eixo B (cópia integral). **Os +2 eixos restantes — ambos agora fechados:** **geração de artefatos de envio ao TCE** (§22.7.8, v1.35 — o runtime rastreia a *obrigação de enviar*; o eixo gera o *arquivo*) e **estratégia de expansão para os outros tribunais sem refactor** (§22.7.9, v1.37 — Invariante 4; **o Eixo C mostrou que `dominio` é taxonomia em camadas, não "qual TCE", §22.7.5 S2**). **§22.7 está completo.**

**Plenário e envelope — RESOLVIDO no Eixo C (§22.7.5, achado S4).** As regras de §22.6 (quórum, votação por matéria, tempos de tribuna) **usam o envelope de _guard_ de tramitação/plenário, não o de compliance**: têm semântica de "esta ação é válida agora?" (guard em runtime), não de "obrigação que vence num prazo" (compliance). O **núcleo** é compartilhado (forma A2: um núcleo, múltiplos envelopes); os **envelopes** diferem por semântica.

## 22.7.5 Eixo C — stress-test da DSL (forma validada, vocabulário derivado de carga real)

O Eixo C pegou requisitos **reais** do TCE-CE da parte legislativa (varredura sourced: remessa/prazos, atos legislativos, transparência ativa, quórum) e os expressou como **templates no envelope de compliance**, para descobrir lacunas no vocabulário **antes** de cravar schema. Rascunho de origem com os templates escritos, fontes e a classificação completa: `docs/05-eixo-C-stress-test-rascunho.md`.

**Veredito.** ✅ **A forma A2 sobreviveu.** Três requisitos diversos encaixaram limpo no envelope (remessa mensal ao TCE; transparência em tempo real condicional ao porte; publicação de ato legislativo); um (quórum) **não encaixou de propósito** e a não-aderência foi o resultado (achado S4). **Nenhuma decisão estrutural do Eixo A precisou reabrir** — as descobertas **preenchem** a §22.7.4 com vocabulário **justificado por requisito real**.

**Vocabulário derivado (preenche o parqueado de §22.7.4, decisões 4/5/6/7):**

- **Builtins (dec. 6):** relógio injetado determinístico (`hoje`/`agora`); aritmética de calendário (`fim_de` competência; `proximo_dia_util` e `soma_dias_uteis`, ambos dependentes de um **calendário de feriados nacional+municipal como dado de domínio**); `arredonda_cima` (ceil) — **obrigatório**, o atalho "metade mais um" erra em N ímpar; `fracao(num,den)` com **aritmética exata/racional** (não float, senão o ceil de maioria erra); `prazo_vigente(dominio,tipo,chave)` (lê calendário de domínio com override por Ofício Circular) e `parametro_tenant(chave)` (lê config por tenant) — estes dois na fronteira builtin↔núcleo.
- **Funções de relação (dec. 3, expostas pelo contexto dono):** `populacao(ente)`, `membros_da_casa(ente)` (Cadastros/Ente); `remessa_enviada(ente,sistema,competencia)` (Remessa-tracking); `publicada_no_portal(despesa)`, `data_registro_contabil(despesa)` (Transparência/Execução); `publicado(ato)`, `data_promulgacao(ato)` (Atos Legislativos); `votos_favoraveis(votacao)` (Plenário, §22.6).
- **Núcleo/tipos (dec. 4/5) — extensões, não contradições da forma A2:** tipo `Competencia` (período); tipo composto `Maioria/Limiar` = `(fração, base ∈ {presentes, membros})` — o **denominador** é a armadilha; tipos temporais `Data`/`Instante`/`Duracao`; registros (acesso a campo `ato.tipo`); conjunto/enum com o **operador `in`** (pertinência) — novo.

**Achados estruturais (refinam o envelope de compliance — dec. 7):**

- **S1 — o motor de compliance _monitora prazo_, não só avalia booleano.** A semântica que define o envelope é **obrigação temporal**: estado asserido que precisa valer **até um prazo** (ou **continuamente**, quando o bloco `prazo` é ausente). Distinta de _guard_ (tramitação) e _permissão_ (autorização). É **comportamento de motor** → elevado no roadmap dos "+5 eixos" (§22.7.4).
- **S2 — `dominio` é taxonomia em CAMADAS, não "qual TCE".** Apareceram três regimes numa só UF: `federal` (LC 131, LAI), `tce_estadual` (INs do TCE-CE), `regimento_tenant` (prazo de publicação que varia por casa, parametrizado por tenant). O Invariante 4 segue válido; o modelo de domínio é mais rico que "27 tribunais".
- **S3 — `prazo` é expressão MULTI-FONTE:** resolve por calendário de domínio com override por circular, por evento + dia-útil, ou por parâmetro de tenant + dia-útil. Generaliza o `prazo_dominio_ativo` (§22.4.3 disc. 6) e exige o calendário de feriados como dado.
- **S4 — quórum/votação/tribuna usam o envelope de _guard_ (tramitação/plenário), não o de compliance.** Resolve a pendência de §22.7.4. Núcleo compartilhado; envelopes distintos por semântica.

**Reclassificações (não são regra de compliance):** **numeração de atos** é invariante de integridade de dados (`unicidade(tipo,numero,ano,camara_id)`, `numero` como string) → vai para o schema (Eixo B / §22.4), não para o motor de regras; **índices ITM/PNTP-Selo** são medição/score, não regra dura bloqueante.

**Disciplina de severidade confirmada:** `severidade: bloqueante` tem referente real (bloqueio de transferências voluntárias via LRF art. 73-C; multa pessoal ao Presidente) — é o que justifica o type-check no save time (§22.7.2 dec. 2). `aviso` cobre obrigação sem essa consequência (ex.: publicação de ato, que o TCE-CE não fiscaliza diretamente).

> **Escopo honesto do stress-test:** 1 estado (TCE-CE), 4 templates — conjunto pequeno e diverso de propósito, não exaustivo. O veredito "forma validada" está calibrado a isso. `[GAP]`s sourced (texto exato do prazo SIM da IN 04/2019; prazo definitivo da PCS sob IN 01/2025; reconhecimento da APRECE como veículo oficial) **não bloqueiam a validação de forma** — importam ao popular conteúdo no Eixo B e pedem confirmação do especialista em regimento.

## 22.7.6 Eixo B — schema das tabelas de template/regra

O Eixo B materializa em schema o vocabulário que o Eixo C validou (§22.7.5) — a virada
design→implementação. Rascunho de origem com a DDL completa e as decisões: `docs/06-eixo-B-schema-rascunho.md`.

**Fronteira de escopo.** O Eixo B é o schema **estático** do motor: definição da regra, binding por
tenant, registry/tipos e calendários. **Não** inclui as tabelas de *runtime* (instâncias de
obrigação, resultados de avaliação, monitoramento de vencimento) — o achado S1 (o motor _monitora
prazo_) roteou esse comportamento para um dos "+5 eixos". A ponte explícita: cada definição carrega
o que o runtime vai ler (forma compilada, assinatura, severidade, prazo).

**Decisão estrutural central — definição (domínio) vs. binding (tenant).** O achado S2 (`dominio` em
camadas) força **separar a definição da regra** — dado de domínio, central, **sem `ente_id`** — do
**binding por tenant** — config, **com `ente_id`**. É uma divergência **consciente** do padrão de
§22.4 eixo C ("override por câmara via cópia integral"): regra `federal`/`tribunal_de_contas` é **lei
uniforme mantida central**, não customização por câmara; copiá-la por ~1.500 entes seria
insustentável de manutenção. O versionamento por cópia integral é honrado na *definição*; o
*binding* só carrega o que varia por casa (parâmetro, on/off, pin de versão).

**As tabelas (DDL completa em `docs/06`):**
- **`template_compliance`** (domínio, sem `ente_id`): a definição versionada por cópia integral.
  `dominio`+`chave_dominio` (S2); envelope (`severidade`, `referencia_normativa`); a expressão em
  **dois formatos** — `fonte_yaml` (auditável, "regra é dado") + `forma_compilada` jsonb (AST tipado
  que o motor avalia) + `assinatura_parametros`. `registry_versao_ref` carimba contra qual versão do
  catálogo a regra passou no type-check do save time (dec. 2). Imutabilidade = conteúdo append-only
  + ponteiro de vigência (`estado_versao`), como §22.4.3 disc. 3/4. `UNIQUE(chave_template, versao)`.
- **`compliance_regra_tenant`** (tenant, `ente_id`): binding. Resolução **por escopo**, não
  linha-por-tenant — `federal`/`tribunal_de_contas` aplicam por default à jurisdição (resolução
  ente→jurisdição via UF); o binding só materializa `parametros_tenant`, opt-out auditado
  (`ativa=false`+motivo) ou pin de versão. `regimento_tenant` **exige** binding para ativar (3ª
  camada de S2). `UNIQUE(ente_id, template_chave)`.
- **`prazo_dominio_vigente`** (domínio): prazo regulatório por jurisdição/tipo/período com
  **override por Ofício Circular** (S3, "prazo deslizante") — append-only + flag `vigente`. Lido
  pelo builtin `prazo_vigente`. **Distinto** do `prazo_dominio_ativo` de *runtime* (obrigação
  concreta, no eixo de comportamento do motor): `_vigente` = referência regulatória; `_ativo` =
  obrigação. Nomes distintos de propósito.
- **`calendario_feriado`** (domínio): feriados nacional + municipal (FK `municipios`), lido por
  `proximo_dia_util`/`soma_dias_uteis`. Móveis entram como datas já resolvidas por ano na carga.

**Localização das cinco tabelas — schema `motor` (averbado v1.36, reconciliação com §22.10).** As cinco
vivem no schema do **módulo `motor`** (a biblioteca compartilhada da DSL, §22.10), **não** no schema
`compliance`. Razão dura: `template_compliance` (definição) e `compliance_regra_tenant` (binding) têm
**FK real** (`versao_fixada_id`) e a resolução "regras aplicáveis a um ente" **junta as duas** — §22.10
proíbe FK/JOIN cross-schema, logo co-localizam; `registry_catalogo_versao`, `prazo_dominio_vigente` e
`calendario_feriado` são infra do catálogo/builtins do próprio motor. `compliance_regra_tenant` é a
**única tabela tenant** no schema `motor` (RLS/partição = política global de tenancy, deferida; isolamento
por filtro + guard até lá). Isto **averba** no SSOT a posição já adotada pelo cursor/esqueleto e supera o
"Módulo: compliance" de `docs/06` (escrito antes da §22.10). O **runtime** do motor (obrigação/avaliação/
remessa) segue no schema `compliance` (§22.7.7–22.7.8): o motor **avalia**, o `compliance` **opera/persiste**.

**Registry como catálogo de infra, não tabela de tenant.** O registry de funções de relação + o
catálogo de tipos (Eixo A dec. 3/4) **não são dados de tenant**: são **infra compartilhada pelos
quatro usos da DSL**, residente no módulo core/compartilhado. As assinaturas são declaradas **em
código no bounded context dono** (co-locação evita drift), publicadas num **catálogo versionado**
que o type-checker lê; builtins idem (biblioteca da DSL). Evolução de assinatura = bump de versão do
catálogo + **passe de re-validação** que re-roda o type-check das regras vigentes e sinaliza quebras
no deploy — estende a garantia do save-time type-check à evolução do registry. **Materializa a
"mecânica fina do registry" de §22.7.4** (visibilidade entre contextos + versionamento de
assinatura) **sem reabrir a forma A2**. Única tabela aqui: `registry_catalogo_versao` (log de versão
para o `registry_versao_ref` referenciar e dirigir a re-validação).

**Reclassificações confirmadas no schema (§22.7.5):** numeração de ato é invariante de integridade
(`UNIQUE(tipo,numero,ano,camara_id)`, `numero` como string) no schema legislativo (§22.4), não regra
do motor; índices ITM/PNTP ficam fora.

**Disciplina derivada (nova).** **Domínio vs. tenant é decisão explícita por tabela do motor.**
Regra/calendário/catálogo que valem para um regime regulatório são tabelas de **domínio** (sem
`ente_id`, índice por `dominio`/jurisdição, como `municipios`); só o que varia por casa carrega
`ente_id`. Diverge **conscientemente** do default §22.2 ("`ente_id` em toda tabela") onde a tabela é
genuinamente de domínio — e é a materialização de S2 no schema.

**Pontos a confirmar e deferidos.** Persistência concreta do catálogo (tabela gerada vs. estrutura
carregada no boot) e geração de id — chat de stack (§22.4.4). Conteúdo dos calendários (feriados;
valores exatos de prazo das INs) é `[GAP]` a popular com o especialista em regimento — a **forma**
não depende do valor. Precedência literal de operadores e formas descartadas do Eixo A seguem a
reconciliar (§22.7.4). Avaliador executável da DSL (parser+type-checker rodando os 4 templates do
Eixo C contra o catálogo) é a primeira implementação de fato, logo após a consolidação.

## 22.7.7 Eixo de runtime do motor — comportamento temporal + auditoria

Primeiro dos "+5 eixos", **elevado por S1** (§22.7.5: o motor _monitora prazo_, não só avalia
booleano). Aqui o motor deixa de ser **forma** (schema estático, §22.7.6) e passa a ser
**comportamento**: como uma regra vira **obrigação concreta**, é **avaliada**, tem **prazo
monitorado** e gera **prova auditável**. Rascunho de origem: `docs/07-eixo-runtime-motor-rascunho.md`.

**Fronteira de escopo.** Dentro: materialização de obrigações, modelo de avaliação, monitoramento de
prazo (S1) e auditoria de avaliação. Fora (roteado): geração do **arquivo** de remessa ao TCE (outro
+5 eixo — aqui rastreia-se a *obrigação de enviar*, não se gera o arquivo); versionamento de regra
(fechado no Eixo B, cópia integral); expansão a outros TCEs (Invariante 4, conteúdo). Este eixo
**funde dois** dos "+5 candidatos" — *comportamento temporal* (S1) + *auditoria/rastreabilidade de
avaliação* — porque o relógio e a prova são o mesmo loop de execução.

**Nó central — a obrigação temporal (S1) parte em dois sabores.** (a) **Com prazo** (deadline-bound):
"enviar a remessa do SIM da competência 2026-05 até D" — tem `vence_em`, **materializa** uma instância
com relógio aberto. (b) **Contínua** (standing): "manter transparência em tempo real" — sem prazo
(bloco `prazo` ausente), **não materializa** obrigação; é asserção reativa cuja violação vira só
registro de avaliação. **Decisão:** `prazo_dominio_ativo` é a tabela das obrigações **com prazo**;
contínuas produzem só avaliação. Fecha o **trio de tenancy** do motor: definição = domínio (sem
`ente_id`), binding = tenant, **obrigação = tenant** (`ente_id` — é sempre de *um* ente).

**Generalização disparada (disc. 6).** O 2º caso de "prazo de domínio" chegou (envio TCE), então a
disciplina 6 manda **generalizar `proposicao_prazo_ativo` → `prazo_dominio_ativo` polimórfico** agora
(não é abstração prematura — é a regra que §22.4.3 deixou agendada). Sujeito polimórfico
`(objeto_tipo, objeto_id)` (disc. 2): competência, ato, proposição, sessão.

**As tabelas novas (DDL completa em `docs/07`):**
- **`prazo_dominio_ativo`** (tenant, `ente_id`): a obrigação materializada com relógio. Aponta a
  **versão exata** da regra (`template_compliance_id`); idempotência por
  `UNIQUE(ente_id, template_chave, objeto_tipo, objeto_id)`; ciclo de vida **enum fixo em código**
  (`pendente → cumprida | vencida | dispensada | cancelada`) — universal entre câmaras/regimes, logo
  **não é template** (mesmo princípio de emendas, §22.4 eixo D); a DSL governa a *asserção* e o
  *prazo*, não o ciclo. `acao_no_vencimento` é identificador que o motor mapeia (V1: emitir evento).
- **`compliance_avaliacao`** (tenant, `ente_id`): **append-only** (imutabilidade nível a, §22.4.3
  disc. 4) — a **prova de compliance** (Invariante 10 + confiança operacional). Carimba **regra +
  catálogo** (`template_compliance_id` + `registry_versao_ref`): fecha o ciclo do save-time type-check
  (Eixo B carimba na definição) com o runtime (carimba na avaliação). A obrigação guarda só o estado
  corrente (cache via `ultima_avaliacao_id`); a trilha vive aqui — mesma divisão de
  `texto_vigente_versao_id` → `proposicao_texto_versao` (§22.4 eixo B).

**Modelo de avaliação (lógico decidido, infra deferida — mesma disciplina de §22.4 eixo C).** Três
gatilhos, um motor: **evento** (primário — `AtoAssinado`, `CompetenciaFechada` etc. via o bus que já
existe, Invariante 2/§22.3, materializa e/ou reavalia), **sweep agendado** (único gatilho temporal —
avança vencimento e materializa recorrências de calendário), **sob demanda** (UI; útil p/ regras
contínuas). O avaliador é o **mesmo** dos 4 usos da DSL (disc. 5) — compliance não ganha avaliador
próprio. Infra (worker/fila/cron) → chat de stack.

**Monitoramento de prazo (coração do S1).** "A vencer"/"vence em breve" são **derivações de leitura**
sobre `vence_em` (relógio injetado, builtin do Eixo C), **não** estados persistidos — a máquina fica
pequena. **Re-stamp no deslize de circular (S3 ↔ Eixo B):** quando `prazo_dominio_vigente` ganha nova
linha `vigente` (Ofício Circular deslizou o prazo), as obrigações **abertas** daquele
`(dominio, tipo, periodo)` têm `vence_em` re-carimbado (com novo `prazo_fonte_ref`, auditado);
obrigações já `cumprida`/`vencida` não se mexem (fato consumado). `acao_no_vencimento` na V1 = **emitir
evento** sempre; escalonamento/notificação são **consumidores**, não lógica na obrigação (reusa o bus,
§22.3). Eventos do runtime: `ObrigacaoComplianceMaterializada/Avaliada/Cumprida/Vencida/Dispensada`.

**Pontos a confirmar e deferidos.** Política concreta de escalonamento por tipo de requisito (quem é
notificado, quando) é `[GAP]` de produto/UX + especialista; valores de prazo das INs já eram `[GAP]`
do Eixo B — a **forma** não depende deles. Infra de processamento do sweep/consumo de eventos e
geração de id → chat de stack (§22.4.4). Próximo candidato de igual valor: o **avaliador executável da
DSL** (primeira implementação de fato), agora com o loop de runtime desenhado para validar
end-to-end (materializa → avalia → monitora → audita).

## 22.7.8 Eixo de geração de artefatos de envio ao TCE (a "remessa")

**Primeiro dos +2 eixos restantes** (o outro: expansão a outros TCEs). O runtime (§22.7.7) **rastreia
a obrigação de enviar**; este eixo **gera o arquivo** cuja submissão a satisfaz — fronteira desenhada
explicitamente em §22.7.7, §22.7.4 e §22.10 (GAP 5: remessa = "artefato regulatório", classe 2, dono
`compliance`). O template da remessa SIM **já existe** como regra (`remessa_mensal_sim`, Eixo C §22.7.5):
a função de relação `remessa_enviada(ente, sistema, competencia)` é a **costura** entre a obrigação
rastreada e o artefato — gerar → submeter → aceitar é o que a vira `verdadeiro`, transitando a
obrigação `pendente → cumprida`.

**Nó central — a spec de layout é DADO, não código nem extensão da DSL (decisão "2b", Emilio).** A
especificação de qual-campo-de-onde-em-que-formato é um **descritor declarativo próprio**: dado
versionado por cópia integral como `template_compliance` (§22.7.6), que **reusa o registry de funções de
relação como fonte dos valores** mas tem seu próprio renderizador. Descartadas: *estender a DSL de
avaliação para serializar* (serialização ≠ avaliação — a DSL reduz a booleano nos 4 usos; inchá-la para
emitir arquivo é o reuso errado e fere o foco da disc. 5) e *layout em código por TCE* (feriria o
Invariante 4 e a meta de N TCEs sem refactor). A defesa contra "não construir DSLs distintas" (disc. 5)
é o **S4** já aceito — "um núcleo, múltiplos envelopes/usos": layout é outro **uso** (projeção), não
outro núcleo de avaliação. **Honra o Invariante 4** (spec = dado → novo TCE/remessa = dado novo) sem
reabrir A2.

**Onde mora / tenancy.** Adapter `gerador_remessa` **no módulo `compliance`** (dono declarado,
§22.10/GAP 5), silhueta Nubank padrão (§22.10). Mantém o **trio de tenancy** do motor: o **descritor de
layout é domínio** (lei uniforme do TCE, **sem `ente_id`** — vive no catálogo do motor como a definição
de regra, §22.7.6); o **arquivo concreto gerado é tenant** (`ente_id`, uma competência) — mesma clivagem
definição ↔ obrigação de §22.7.7.

**Contrato com o runtime + auditoria.** Gatilho **híbrido**: o evento
`ObrigacaoComplianceMaterializada(template=remessa_*)` (taxonomia §22.7.7) prepara/notifica; a geração é
**sob demanda com revisão humana** (arquivo regulatório não se envia 100% automático). O artefato gerado
é **registro append-only `remessa_gerada`** (tenant) — mesma disciplina de `compliance_avaliacao`
(Invariante 10, imutabilidade nível a): metadata imutável carimba `template_compliance_id` +
`registry_versao_ref` + `spec_layout_versao` + `hash_conteudo` + `objeto_store_ref`; o **binário mora no
`objeto_store`** (port do kernel, infra de §22.3), a metadata na tabela. **Re-emissão = nova versão**
(`UNIQUE(ente_id, template_chave, competencia, versao)`), nunca muta a anterior — versão-por-cópia como
o Eixo B; reenvio após rejeição é fato comum.

**Ciclo de vida (enum em código, não template).** `rascunho → validada → submetida → {aceita | rejeitada}`;
reenvio = nova versão. Universal entre câmaras/regimes → **enum fixo em código**, como o ciclo da
obrigação (§22.7.7) e as emendas (§22.4 eixo D); a DSL governa asserção/prazo, não ciclo. **Costura
confirmada (Emilio):** `remessa_enviada` assere a obrigação como **cumprida em `aceita`, não em
`submetida`** — envio rejeitado não cumpre a obrigação regulatória, e perder a janela por rejeição é o
"incidente inaceitável" que justifica o rigor do motor. O rótulo terminal exato (há "em análise"? aceite
síncrono?) é `[GAP]` do protocolo do TCE-CE — **único `[GAP]` que toca a *forma*** (não só a folha),
resolvido por decisão sujeita a confirmação regulatória.

**Formato de saída (acopla a expansão multi-TCE).** Port `SerializadorRemessa` com **um adapter na V1**
(o do SIM/TCE-CE); o descritor é formato-agnóstico, o adapter renderiza no formato físico
(XML/posicional/CSV/próprio). Generalização **disparada quando o 2º TCE chegar** (o outro +eixo, **S2**
camada `tribunal_de_contas`), não antes (disc. 6 — generalizar no 2º caso); a *forma* do port é barata agora,
os adapters extras ficam deferidos.

**Proveniência dos campos (read-ports em lote — nuance honesta).** A remessa carrega dados de outros
módulos (despesas → `transparencia`, atos → `legislativo`, cadastro → `cadastros`); coleta via funções
de relação / `port` → `http_client` (§22.10, sem JOIN cross-schema). **Mas** coletar *N registros de uma
competência* é **leitura em lote**, não predicado booleano de avaliação — exige um tipo de **read-port em
lote** distinto das funções-de-relação-para-avaliação. Reusa o padrão, nomeia o limite.

**Transporte/submissão.** Port `TransporteRemessa`, adapter **"download manual" na V1** (operador baixa
do `objeto_store`, sobe no portal do TCE, **confirma o envio → assere `remessa_enviada`**); adapter de
API/webservice do TCE-CE deferido (`[GAP]` — pode nem existir).

**Fronteira `[GAP]` (forma fecha, conteúdo espera).** A *forma* fecha agora com fixture ilustrativo de
layout — disciplina dos Eixos B/C ("a forma não depende do valor"). Conteúdo regulatório real do TCE-CE =
`[GAP]` que não se inventa, e bloqueia só as folhas: **(1)** layout físico do arquivo SIM
(formato/campos/tipos/ordem/encoding); **(2)** inventário de quais remessas a V1 cobre além do SIM
(PCS/IN 01-2025); **(3)** fonte de cada campo + se algum dado ainda não é capturado; **(4)** protocolo de
submissão (API? upload manual? recebimento?); **(5)** semântica de aceite/rejeição (a costura acima, já
decidida por `aceita`); **(6)** se a remessa exige assinatura ICP antes do envio (reusaria a mecânica dos
artefatos legais — classe 3 — confirmando que a classe 2 a herda).

**Fecha o terceiro dos +5; o +1 restante — expansão a outros TCEs — fecha em §22.7.9 (v1.37)** (S2, `dominio` em camadas; o port
`SerializadorRemessa` + o descritor-como-dado são o ponto de extensão). **Materialização do esqueleto
roteada a passo focado:** o módulo `compliance` no `backend/` segue em stub (só README) — atrás das 2
tabelas de runtime (§22.7.7) + a forma deste eixo; sua materialização (silhueta + tabelas + ports) é
catch-up de decisão fechada, **não** materialização de conteúdo antes da hora (§7, §22.4.4) — o layout do
SIM permanece `[GAP]`.

## 22.7.9 Eixo de expansão a outros TCEs — estratégia sem refactor (fecha §22.7)

O último dos eixos de §22.7. **Não reabre a forma** (Eixos A/C/B + runtime §22.7.7 + remessa §22.7.8 já a cravaram); **fecha a estratégia** de absorver os **33 Tribunais de Contas** brasileiros como **dado** (Invariante 4) e crava a única questão estrutural que o Eixo B empurrou pra cá: a resolução de jurisdição com a complicação dos TCMs. Rascunho de origem: `docs/08-eixo-expansao-tce-rascunho.md`.

**Inventário (a confirmar em fonte autoritativa — Atricon/IRB).** 33 tribunais: 26 TCEs + TCDF (nível estadual; o TCE em geral julga conta estadual *e* municipal); **3 TCM "dos Municípios"** (órgão estadual que julga todos os municípios da UF — TCM-BA, TCM-GO, TCM-PA); **2 TCM "do Município"** (só a capital — TCM-SP, TCM-RJ). Consequência dura: **`tribunal(câmara)` não é `UF→TCE`** — é função de `(UF, município, é-capital)`. (Ceará: TCM-CE fundido no TCE-CE em 2017 — por isso o beachhead resolve direto.)

**E1 — resolução de jurisdição (`câmara → tribunal`).** Tabela `jurisdicao_camara` de **domínio no módulo `cadastros`** (irmã de `municipios`, **sem `ente_id`**; referência geográfico-institucional, não regra): uma linha por UF (default `UF→TCE-UF`; `BA/GO/PA→TCM`) + override por município (capitais SP/RJ → TCM-SP/RJ); **precedência município→UF**. O motor a alcança pela **função de relação `tribunal_competente(ente)→Texto`** exposta pelo `cadastros` (padrão B3), que dá o `chave_dominio` da B2. **Reconcilia o Eixo B** ("resolução via `municipios.uf` JOIN") com a §22.10 (sem cross-schema JOIN): vira função de relação, não JOIN. Armadilha `UNIQUE+NULL` (município NULL = regra de UF) → índice `COALESCE`. Novo tribunal/fusão = **linha de dado**. *Forma recordada; DDL deferida à materialização do `cadastros`.* (Descartadas: binding explícito por ente — ~1.500 linhas, perde "lei uniforme central", drift; `chave_dominio` como expressão na regra — resolução é de cadastro.)

**E2 — taxonomia.** A camada `tce_estadual` foi **renomeada `tribunal_de_contas`**: `dominio ∈ {federal, tribunal_de_contas, regimento_tenant}`, `chave_dominio` = código do tribunal (TCE-CE, TCM-GO, TCM-SP…). O rótulo antigo mentia nos ~5 casos de TCM e re-importava a confusão "qual TCE" que o S2 alertou; **a forma/schema não muda** (valor de enum-texto). Aplicado no código do motor (`verificador`, templates T1/N1, comentários da `…0006`; suíte verde, 12 testes/63 asserções). §22.7.5 S2 (v1.10) e os changelogs retêm o nome anterior `tce_estadual` como registro do achado; §22.7.6/§22.7.8 e o código passam a `tribunal_de_contas` (v1.37). (Descartada: TCE vs TCM como `dominio` distintos — over-granular; a diferença é *qual* tribunal, trabalho do `chave_dominio`.)

**E3 — variação × Invariante 4 + playbook.** O que varia entre tribunais é **dado**: regras→`template_compliance`; prazos→`prazo_dominio_vigente`; feriados→`calendario_feriado`; expressão→`fonte_yaml`; jurisdição→`jurisdicao_camara`; cadência→tipo `Competencia`; layout→descritor declarativo (§22.7.8). **Exceção: 3 pontos de código bounded** — encoding do arquivo (`SerializadorRemessa`), protocolo de submissão (`TransporteRemessa`), e vocabulário novo (função de relação no registry B3). **Regra-de-ouro (load-bearing):** as exceções são keyed por **eixo compartilhado — família de encoding / protocolo / vocabulário — nunca por identidade do tribunal**; "1 serializer por TCE" ou `if tribunal == X` é o cheiro a resistir (encoding de remessa gov-BR é predominantemente XML-tabular: poucas famílias, muitos tribunais cada; função de relação nova é aditiva ao registry compartilhado, usada pelos 4 usos da DSL). **Playbook de onboarding repetível:** jurisdição → calendários/prazos → regras na DSL (**type-check no save = rede de segurança**) → estender registry se uma regra não tipa (o type-check aponta o quê) → descritor de layout + serializer da família → transporte (depois; V1 = download manual). **O que torna seguro comercialmente:** o type-check do save-time transforma onboarding de tribunal novo de **risco de engenharia** em **edição de dado verificada** — regra malformada vira erro no editor, não incidente de janela de envio (Aposta 3).

**E4 — fronteira `[GAP]` + rollout.** A **forma** está validada estruturalmente + contra 1 tribunal real (TCE-CE, Eixo C); o **conteúdo** de cada tribunal (regras, prazos, feriados, campos, sistema de remessa) é `[GAP]` — especialista/pesquisa, não inventado. Honestamente, a forma **não** foi stress-testada contra um 2º tribunal — "absorve os 33" é argumento estrutural, e o **type-check é o instrumento de medida** quando o conteúdo chega. **Rollout demand-pulled** (régua das 4 perguntas §15; escopo diferido por default): V1 = só TCE-CE (§10/§325); ordem segue a expansão comercial (NE→N/CO→S/SE), **gatilho = cliente validado na jurisdição**, não roadmap; **o 2º tribunal é o marco de validação empírica da forma** (provável TCM-BA ou TCE-NE). Parqueado (pesquisa, não conteúdo): inventário das famílias de encoding dos sistemas de remessa do NE, p/ confirmar "poucos adapters" antes da 2ª expansão.

**Com este eixo, §22.7 (motor de regras de compliance) fecha por completo** — Eixos A, C, B, runtime (§22.7.7), geração de remessa (§22.7.8) e expansão (§22.7.9). A forma do motor está cravada e validada contra 1 tribunal; o conteúdo regulatório por tribunal segue `[GAP]` por design, populado sob a régua das 4 perguntas conforme a expansão comercial pede.
