# ADR-0017 — A trilha de auditoria da Casa: o que registra, quem vê, quanto tempo, LGPD e o selo

- **Status:** 🟡 **Proposto** (rascunho de 29/09/2026 para decisão do Daouda, eixo a eixo). Nada aqui está decidido
  até o "Confirmo"; cada eixo traz as opções, o custo de cada uma e uma recomendação.
- **Contexto de decisão:** §16.1 do documento-mestre ("trilha de auditoria completa: quem fez, o quê, quando, de
  onde"), princípio 7 (audit log é uma das quatro observabilidades, "retenção regulatória permanente"), Invariante 10,
  `arquitetura/22-5-auth.md` Eixo E ("Auditoria de decisões") e Eixo G (taxonomia em 4 classes, retenção por classe,
  apagamento LGPD), §22.11 ("Fulano, via agente X").
- **Relacionadas:** ADR-0009 (catálogo de ações — o vocabulário de ação sai dele), ADR-0010 (ator com `:via`),
  ADR-0016 (a atuação do operador já tem selo encadeado), tela `produto/design-system/o-plenario/telas/trilha-auditoria.html`.

## Contexto

### O que JÁ está decidido (não se reabre aqui)

O SSOT fechou a maior parte do "o quê" em §22.5:

- **Registrar:** toda escrita (permitida **e** negada), leituras sensíveis (trilha de outra pessoa, dado de outro
  vereador), nunca leitura de dado público. Granularidade: ator (identidade + vínculo), ação, recurso (tipo + id),
  decisão, razão (a cláusula que decidiu), `ente_id`, instante (Eixo E).
- **Classes:** domain events (bus) ≠ audit log de produto ≠ log de aplicação ≠ métrica, e uma nunca recebe a outra
  (Eixo G). Os eventos de auth da classe 2 estão listados lá (`LoginRealizado`, `StepUpRealizado`,
  `AcessoNegadoPorPolitica`, `AcessoSensivelRealizado`, `MFAResetadoPorAdmin`…).
- **Retenção por classe:** a tabela do Eixo G (login 18 meses por padrão, 12–36 por Casa; negação 2 anos; step-up,
  reset de fator e acesso sensível 5 anos; assinatura e política da Casa permanentes).
- **LGPD:** atos no exercício da função pública não se apagam; o rastro do cidadão se apaga a pedido; o rastro não
  funcional de servidor/vereador é pseudonimizado depois de N anos; o apagamento é ato registrado, nunca `DELETE`
  silencioso.
- **Quem lê:** "ver o próprio audit log" é self-action; **ler a trilha de outra pessoa exige step-up** e gera
  `AcessoSensivelRealizado`.
- **Agente:** toda escrita por agente registra pessoa + agente + execução + ferramenta + classe (§22.11).

### O que EXISTE no código hoje

| Peça | Onde | O que cobre |
|---|---|---|
| Tabelas de domínio append-only / imutabilidade (a)/(b) | `shared.imut_*`, mig 0012 | votos, versões de texto, presença, atas, decisões da Mesa — o **fato** está guardado e não muda |
| `shared.outbox` | kernel | o domain event de cada mudança relevante; linhas ficam (só `processed_at` muda), mas não é audit log: não tem ator uniforme, não tem negação, não é imutável |
| `integracao_ia.chamada_agente` | ADR-0010 | escritas do agente (inclusive negadas), append-only, por Casa |
| `admin_sistema.atuacao` | ADR-0016 | a atuação do OPERADOR, append-only com **selo encadeado** (`sha256(selo-anterior \| registro)`, advisory lock, `verificar-corrente`) |

### O que FALTA

- **Não há audit log da Casa.** Nenhum dos eventos da classe 2 é gravado: login, step-up, negação por política, leitura
  sensível. Quem fez cada escrita existe espalhado (coluna `*_por` em algumas tabelas, payload do outbox em outras),
  sem forma única e sem negação.
- **"De onde" não existe:** o backend não lê IP nem canal.
- **Não há tela:** a `trilha-auditoria` da Onda E segue bloqueada por falta de domínio.

São ~98 rotas de escrita (81 POST, 8 PATCH, 6 DELETE, 3 PUT) em 13 módulos. Todas já têm entrada no catálogo de
ações ou motivo em `fora-do-catalogo.edn` (ADR-0009) — isso dá o vocabulário de ação de graça.

## Eixos para decidir

### Eixo 1 — De onde vem o registro (a fonte)

- **A. Interceptor de borda.** Um interceptor em toda rota autenticada grava um registro por requisição de escrita
  (e por negação): ator, ação = `route-name`, recurso = parâmetros de caminho, decisão, razão, instante, origem.
  - ✔ Cobre tudo de uma vez, inclusive negação (que o domínio nunca vê); rota nova já nasce auditada.
  - ✘ Sabe a rota, não o efeito: "POST /proposicoes/:id/votos" sem o número da matéria nem o que mudou.
- **B. Projeção dos domain events.** Um consumidor do outbox transforma cada evento em linha da trilha.
  - ✔ Semântica rica ("votou SIM no PL 118/2026").
  - ✘ Não vê negação, login nem leitura sensível. Nem toda escrita emite evento hoje. O ator no payload não é uniforme.
- **C. Híbrido (recomendado).** O interceptor grava o **ato** (A). O handler pode anexar o **resumo do efeito** (id e
  rótulo do recurso, campos alterados, o id do domain event quando houver). Os eventos de auth (login no mint da
  sessão, step-up, reset de fator) são gravados onde acontecem. As escritas do agente continuam em
  `chamada_agente` e aparecem na trilha por junção, com "via agente X".
  - ✔ Cobertura de A + legibilidade de B onde importa.
  - ✘ O resumo do efeito é trabalho por rota: começa pelas ~20 ações que o design mostra (proposição, parecer,
    sessão, ata, votação, acesso) e o resto aparece pelo nome da ação do catálogo.

**Sub-decisão 1a — "Antes / Depois"** (o design mostra a ementa antes e depois):

- (i) Guardar os valores na trilha. ✘ Duplica dado e leva conteúdo para a trilha.
- (ii) Guardar só os **nomes dos campos** alterados e apontar a versão (recomendado). O antes/depois é lido das
  tabelas versionadas que já existem (texto da proposição, ata), como a tela da ata já faz.
- (iii) Não mostrar nada.

**Sub-decisão 1b — o que conta como "de onde":**

- IP de origem.
- Canal: web, app do vereador, painel da Mesa, agente, gravador de captação.
- Nenhum dos dois é lido hoje. O IP exige confiar no `X-Forwarded-For` do proxy do Dokploy (o proxy é nosso).

### Eixo 2 — Quem vê

Já decidido: cada pessoa vê a própria trilha; a de outra pessoa pede step-up e é registrada.

- **A. Só o `admin_ente`.** ✘ Mistura quem administra com quem fiscaliza: o fiscalizado lê a trilha de si mesmo.
- **B. Papel novo `auditor`, só leitura (recomendado),** para controle interno, procuradoria ou quem a Casa designar.
  - O `admin_ente` concede o papel, e a concessão fica na trilha.
  - O `auditor` vê a trilha da Casa inteira e exporta.
  - O `admin_ente` vê só a parte de acessos (quem ganhou e perdeu papel), que é o que ele administra.
  - O presidente da Mesa não vê por padrão; recebe o papel se a Casa quiser.
- **C. Versão pública no portal.** O que é ato oficial já é público por outras telas (votos nominais, atas,
  tramitação, dados abertos). Uma trilha pública exporia o trabalho interno dos servidores.
  **Recomendação: não** — no máximo o selo diário (Eixo 5).
- **O operador da plataforma** não lê a trilha de nenhuma Casa (esfera separada, ADR-0016). A atuação dele **na
  Casa** (provisionamento e, no futuro, acesso de suporte) aparece na trilha dela, só leitura, espelhada da
  `atuacao`. É o que o design mostra ("Operador · Suporte").

### Eixo 3 — Retenção e onde mora

A retenção por classe está decidida. Falta:

- **3a. O prazo das escritas funcionais** (o grosso da trilha; o Eixo G não lista explicitamente):
  - (i) **Permanente**, coerente com "não apagável: atos no exercício da função pública" (recomendado).
  - (ii) Prazo longo configurável (ex.: 10 anos).
- **3b. Armazenamento:**
  - (i) Tudo no Postgres, **particionado por mês** desde o início (recomendado agora). Partição velha pode ir para
    tablespace barato sem mudar código.
  - (ii) Quente 24 meses no Postgres e frio em arquivo mensal selado no object storage, com object lock (WORM).
    Mais barato no longo prazo, mas a consulta de período antigo fica mais lenta. **Recomendação: quando o volume
    pedir** — a partição mensal de (i) já é o corte.
- **3c. No fim do contrato** (portabilidade, §16.13): a Casa recebe a trilha inteira exportada com a corrente
  verificável. Recomendação: sim, no mesmo formato da exportação da tela.

### Eixo 4 — LGPD e dados pessoais na trilha

- **4a. Conteúdo:** a trilha **nunca** guarda texto, só ids, rótulos e nomes de campos (mesma regra do registro de IA,
  B4). Recomendação: sim, sem exceção.
- **4b. IP:**
  - (i) Guardar completo e mostrar truncado (`189.45.x.x`, como o design), com o IP completo apagado depois de
    **6 meses** (o prazo de guarda de registro de acesso do Marco Civil, art. 15 — entendimento prático, não parecer).
    O resto do registro segue. **Recomendado.**
  - (ii) Guardar só o prefixo /24. ✘ Perde a utilidade forense.
  - (iii) Não guardar IP.
- **4c. O cidadão na trilha da Casa:**
  - (i) Os atos do cidadão (protocolo de e-SIC, manifestação, comentário) entram **pseudonimizados** — "cidadão
    #a1b2", com o link para o protocolo, que já identifica quem precisa. O login do cidadão não entra (é da sessão
    dele). **Recomendado.**
  - (ii) Entram com o nome.
  - (iii) Não entram.
- **4d. Apagamento a pedido:** segue o Eixo G. O registro apagado vira "[registro removido a pedido do titular — base
  legal X]" **mantendo o selo original**, para a corrente continuar verificável (a prova de que havia um registro ali
  sobrevive ao conteúdo). Recomendação: sim.

### Eixo 5 — O selo encadeado

O selo de linha a linha só prova integridade contra quem **não** consegue reescrever a corrente inteira dali em
diante. Quem tem escrita no banco consegue recalcular tudo. Por isso a proposta separa a corrente da âncora.

- **5a. Escopo da corrente:**
  - (i) **Uma corrente por Casa** (recomendado): o mesmo mecanismo da `atuacao` (ADR-0016), com advisory lock por
    `ente_id`. Uma Casa não espera outra, e a RLS continua valendo.
  - (ii) Corrente global. ✘ Acopla Casas e cruza tenant.
  - (iii) Sem corrente por linha, só raiz de Merkle por hora. Mais barato de escrever; a prova de um registro vira
    caminho de Merkle (mais difícil de explicar ao jurídico).
- **5b. Quando selar:**
  - (i) **Na transação do ato**, com o lock por Casa (recomendado). Se a trilha falha, o ato não acontece
    (fail-closed, como a autorização). Custo: as escritas de uma Casa se serializam no instante do selo — em sessão
    ao vivo, com 21 votos quase simultâneos, é fila de milissegundos. O smoke da condução mede.
  - (ii) Registro sem selo na hora e um **selador** que sela em ordem segundos depois (um só consumidor, sem lock no
    caminho do voto). ✘ Existe uma janela sem selo e mais uma peça rodando. **Fica como plano B** se (i) pesar na
    sessão.
- **5c. Âncora externa:**
  - (i) **Selo do dia (recomendado):** às 00h a cabeça da corrente de cada Casa é gravada na corrente da Operação
    (`atuacao`, outra esfera, outro papel de banco) **e** publicada no portal da Casa ("selo de integridade de
    28/09: `a7f3…9c21`"). Reescrever a trilha passa a exigir reescrever também a corrente da Operação e o que já
    foi publicado e arquivado por terceiros.
  - (ii) Carimbo de tempo de terceiro (TSA ICP-Brasil / RFC 3161). Mais forte juridicamente; custa contrato com
    autoridade de carimbo. Fica para quando o ICP-Brasil sair do `STUB-ICP-v0`.
  - (iii) Sem âncora. ✘ É o que o design promete e não entrega.
- **5d. Verificação:** a tela mostra "Cadeia íntegra / quebrada a partir de X". O `auditor` pode baixar a corrente
  e o verificador (um script de 30 linhas publicado no repositório) para conferir fora da plataforma. Recomendação:
  sim — é o argumento para o jurídico na POC.

## Proposta de primeira fatia (se os eixos saírem como recomendado)

1. **Backend:**
   - módulo `auditoria` (domínio, por Casa), tabela `auditoria.registro` particionada por mês, append-only, RLS, com
     selo por Casa;
   - interceptor de escrita e de negação em todas as rotas autenticadas;
   - login e step-up gravados no mint;
   - resumo do efeito nas ~20 ações do design;
   - espelho da `atuacao` da Operação na Casa;
   - selo do dia.
2. **Papel** `auditor` + concessão pelo `admin_ente` (ADR-0005).
3. **Tela** `/auditoria` (porte de `trilha-auditoria.html`):
   - filtros de período, ator, ação e objeto;
   - estado da corrente;
   - exportação (a exportação é registrada).
4. **Fora da fatia:**
   - arquivo frio;
   - carimbo ICP;
   - leitura sensível além de "ler a trilha";
   - apagamento a pedido, que entra com o fluxo LGPD de §22.5.

## O que peço para decidir

| Eixo | Pergunta | Recomendação |
|---|---|---|
| 1 | Fonte | **C** — interceptor + resumo do efeito nas ações principais; antes/depois por **nome do campo + link para a versão (1a-ii)**; "de onde" = IP + canal |
| 2 | Quem vê | **B** — papel `auditor` (concedido pelo `admin_ente`); sem trilha pública; atuação do operador espelhada na Casa |
| 3 | Retenção / onde | escritas funcionais **permanentes**; Postgres particionado por mês; exportação completa no fim do contrato |
| 4 | LGPD | sem conteúdo; IP completo por 6 meses e truncado na tela; cidadão pseudonimizado; apagamento mantém o selo |
| 5 | Selo | corrente **por Casa**, selada **na transação**; **selo do dia** na corrente da Operação + no portal; verificador publicado |

Depois do "Confirmo" (com as correções), esta ADR vira **Aceita**, o §22.5 ganha a nota com o que foi materializado,
e a fatia 1 começa.
