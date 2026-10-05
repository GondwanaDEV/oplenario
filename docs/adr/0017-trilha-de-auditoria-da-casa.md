# ADR-0017 — A trilha de auditoria da Casa: o que registra, quem vê, quanto tempo, LGPD e o selo

- **Status:** ✅ **Aceito** (29/09/2026 — "confirmo as recomendações da ADR-0017" do Daouda, os cinco eixos como
  recomendados). A fatia 1 está materializada; o que ela fez diferente do texto dos eixos, e por quê, está em
  **Materialização (fatia 1)** no fim.
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

Decidido em 29/09/2026: as cinco recomendações, sem correção.

## Materialização (fatia 1)

**O que existe:**

- **Módulo `auditoria`** (`apps/backend/src/oplenario/auditoria/`, migration `20260929000110-auditoria`):
  - `auditoria.registro`, particionada por mês, com RLS e append-only por trigger;
  - a única mudança aceita é o IP virar `NULL`, e o role da aplicação só tem `UPDATE (ip)`;
  - `auditoria.selo_diario`, também append-only;
  - `auditoria.garantir_particoes` (SECURITY DEFINER), que cria os meses seguintes. O que cair fora de um mês criado
    vai para a partição DEFAULT, sem se perder.
- **O interceptor da trilha** fica entre os globais do host (`it/globais-com`), por fora do interceptor de erro.
  - Ele vê a resposta final (o 403 inclusive) e o ator que a autenticação resolveu.
  - Registra quatro classes:
    - escrita (`permitido`/`negado`/`falhou`);
    - negação por política (403, em qualquer método);
    - entrada, marcada pelo mint da sessão;
    - leitura sensível: ler a trilha de outras pessoas e exportar.
  - Leitura comum, anônimo e operador não entram. O operador tem a corrente dele (ADR-0016).
- **Quem vê** (`GET /auditoria`), com o escopo decidido no servidor:
  - `auditor`: a Casa inteira, a integridade (`GET /auditoria/integridade`), a exportação CSV
    (`GET /auditoria/exportar.csv`, ela mesma registrada) e a atuação da Operação na Casa;
  - `admin_ente`: os atos de acesso (`identidade/*`) e os próprios;
  - qualquer pessoa: a própria trilha.
- **O papel `auditor`** é concedido pelo `admin_ente` em `/administracao`.
  - O vínculo é de **servidor**: o backend recusa `auditor` num vínculo de vereador e `vereador` num de servidor.
  - O auditor pousa em `/auditoria` e só vê essa entrada na navegação.
- **LGPD:**
  - nenhum conteúdo, só nomes de campos;
  - IP completo no banco, truncado na tela e no CSV;
  - anulado quando o primeiro registro de um dia novo chega e o IP tem mais de 6 meses;
  - cidadão pseudonimizado por Casa (`#a1b2c3d4e5f6`, 48 bits — eram 6 caracteres até 02/10/2026, ver ADR-0018).
- **O selo** é `sha256` do selo anterior da mesma Casa mais os campos canônicos, com o IP fora. A escrita é serializada
  por advisory lock por Casa.
  - O primeiro registro de um dia novo fecha o anterior: grava o selo do dia, ancora-o na corrente da Operação
    (`selo-do-dia-da-trilha` na atuação) e ele aparece no portal, em Dados abertos (`GET /portal/casa/:ente/integridade`).

**Onde a fatia 1 difere do texto dos eixos (e por quê):**

1. **O registro não está na transação do ato.**
   - Cada handler abre a própria transação, e o interceptor não tem como entrar nela sem reescrever os ~150 handlers.
   - O registro é gravado logo depois do ato, na mesma requisição e antes da resposta sair, numa transação própria.
   - Falhar aqui não desfaz o ato nem muda a resposta: vira `log/error` "registro NAO gravado", que é alerta de
     operação.
   - ~~O custo aceito: um ato pode existir sem registro se o banco cair entre as duas transações.~~ **Fechado em
     04/10/2026** pela tentativa gravada antes do handler — ver o **Adendo** no fim. O registro continua fora da
     transação do ato; o que acabou foi a perda silenciosa.
2. **O selo do dia é preguiçoso.** Não há agendador em produção. O dia fecha quando chega o primeiro registro do dia
   seguinte, e uma Casa sem atos num dia não gera selo daquele dia (não há o que selar). O mesmo gatilho anula os IPs
   antigos e cria as partições.
3. **O "resumo do efeito"** nas ~20 ações do design ainda não foi escrito ação por ação. O registro traz o recurso pelo
   parâmetro de caminho (tipo + id) e o nome da ação do catálogo. O rótulo legível ("PL 118/2026") e os campos
   alterados entram quando o handler devolve `:auditoria {:rotulo :campos}` na resposta — hoje só o login e a
   exportação o fazem. É incremental, rota a rota, sem migration.
4. **Step-up** não existe no sistema ainda. Quando existir, entra como classe `entrada` pelo mesmo caminho do login.
5. **O verificador** é a rota de integridade do auditor, que recalcula a corrente inteira em páginas de 5000. Um
   verificador offline (script sobre o CSV exportado) fica para quando um cliente pedir.
6. **A atuação da Operação** aparece como lista à parte na tela do auditor, com o selo da corrente da Operação, e não
   misturada aos registros da Casa: são correntes diferentes.

## Adendo (04/10/2026) — a tentativa antes, o desfecho depois

**O problema.** O registro era gravado depois do ato, em outra transação. Se o processo caísse ou a gravação falhasse
entre as duas, o ato existia e a trilha não tinha linha nenhuma, sem sinal.

**O que foi medido antes de escolher:**

| Pergunta | Resposta | Onde |
|---|---|---|
| Quando o interceptor grava? | No `:leave`, depois do handler, antes de a resposta sair | `auditoria/diplomat/http/in.clj` |
| Em que transação? | Própria (`com-tenant*`), nunca a do ato | `auditoria/components/repositorio.clj` |
| E se falhar? | `log/error`; a resposta segue 2xx | `auditoria/controllers.clj` |
| Quantas rotas de escrita? | 163 de 314 (137 POST, 11 PATCH, 9 PUT, 6 DELETE) | tabela de `rotas/montar` |
| Há um ponto único de transação por pedido? | Não: 108 aberturas (`com-tenant*`/`with-transaction`) em 31 arquivos, dentro dos repositórios; um pedido abre várias | `src/oplenario/**` |

**As duas formas:**

- **A. Mesma transação.** Sem ponto único de abertura, exigiria passar a transação do interceptor para cada método de
  repositório (os 108 sítios) ou trocar todos por uma transação por pedido. Além disso, o lock da corrente ficaria
  preso até o fim da transação de domínio: as escritas de uma Casa passariam a se serializar pelo ato inteiro, e não
  pelo instante do selo, com risco de deadlock contra os locks do domínio. **Descartada.**
- **B. Tentativa antes, desfecho depois (escolhida).** Não toca nenhum controller e não entra em transação de domínio.

**Como ficou:**

- Logo antes do handler de toda rota de escrita — depois da autenticação, da autorização da rota e da validação da
  borda —, o interceptor `tentativa` grava na corrente um registro com `decisao = iniciado`, em transação própria já
  commitada.
- Depois do handler, o desfecho é gravado como antes e aponta a tentativa em `detalhe.tentativa` (o `seq` dela).
  O `detalhe` já entrava no selo: **o formato do selo não mudou**, e registros antigos e novos conferem na mesma
  corrente.
- **Se a tentativa não puder ser gravada, o padrão é não bloquear:** fica um `log/error` com a rota e a Casa (sem
  conteúdo) e o handler roda, como era antes do adendo.
  - Por quê: o produto vende disponibilidade na janela da sessão. Recusar toda escrita porque a trilha caiu seria um
    modo novo de derrubar o plenário no meio de uma votação.
  - `AUDITORIA_EXIGIR_TENTATIVA=true` liga o modo que recusa: 503 e o handler não roda (o fail-closed do Eixo 5b).
    Só o valor exato `true` liga; ausente ou qualquer outro valor não bloqueia.
- **A faxina do dia novo** (anular IPs de mais de 6 meses, criar partições) saiu da transação do registro: roda depois
  do commit, em transação própria e fora do lock da corrente. Se falhar, vai para o log; o registro, o selo do dia e o
  pedido seguem. O selo do dia continua na mesma transação do registro que fecha o dia.
- **Sem lista de rotas:** `rotas/montar` passa a tabela inteira por `com-tentativa`, e
  `auditoria/toda_escrita_tem_tentativa_test` confere as 163 e nomeia a rota que ficar de fora.
- **Leitura:** a tentativa com desfecho não é linha da tela (um ato, uma linha). A sem desfecho aparece como "Sem
  desfecho — ação iniciada, desfecho não registrado", tem filtro próprio, sai no CSV como `sem desfecho registrado`
  e é contada na conferência da cadeia ("Cadeia íntegra, com desfecho faltando").
- A tentativa só é acusada 2 minutos depois de gravada: antes disso o pedido ainda pode estar em curso.
- Migration `20261004000188`: o `CHECK` de `decisao` aceita `iniciado` e há um índice parcial do apontamento. Feito
  na tabela-mãe, vale para as partições que existem e para as futuras.

**Custo por escrita:** uma gravação a mais na corrente (o lock da Casa, uma leitura da cabeça e um `INSERT`, numa
transação curta). A gravação que já existia passou de quatro comandos para três (a cabeça e o relógio vêm na mesma
consulta). Leitura, negação antes do handler, entrada e leitura sensível continuam com um registro só.

**Contenção medida** (`janela_de_perda_test`, 21 escritas simultâneas da mesma Casa, pool de 10 conexões, handler com
transação de tenant própria de ~5 ms; três rodadas, depois de aquecer):

| | Tempo total das 21 | Maior tempo de uma escrita |
|---|---|---|
| Sem a tentativa (como era) | 80 a 152 ms | 78 a 132 ms |
| Com a tentativa | 144 a 184 ms | 132 a 167 ms |

- Todas concluíram, sem deadlock nem timeout; a corrente ficou íntegra, com 21 tentativas e 21 desfechos.
- A tentativa custou entre nada e 1,8 vez o tempo, conforme a rodada: cerca de 60 a 80 ms a mais para os 21 votos.
- Se pesar em produção, o plano B já está no Eixo 5b-ii (selar depois, com um selador único fora do caminho do
  voto). Não foi implementado.

**Medição no voto real (05/10/2026)** (`auditoria/custo_no_voto_real_test`, o mesmo formato de `janela_de_perda_test`;
só mede e confere, não asserta tempo).

- **Método.**
  - Uma Casa com 21 vereadores (mandato vigente, vínculo e papel `vereador` pelos repositórios reais) e, por rodada,
    uma sessão aberta com os 21 presentes e uma votação nominal aberta e nova.
  - Os 21 `POST /sessoes/:id/votacoes/:votacao-id/meu-voto` saem no mesmo instante, em processo (`pt/response-for`, sem
    Jetty e sem rede), pela tabela de `rotas/montar` e pelos globais do host: erro, trilha, autenticação sobre o
    `repo-identidade` real, papel, policy de mandato e presença, escrita do voto sob o lock da votação, outbox na
    transação do ato e o relay de pé.
  - Duas condições: **com** a tentativa (a tabela de hoje) e **sem** (a mesma tabela com o interceptor `tentativa`
    retirado de cada escrita; nenhuma chave de produção foi acrescentada).
  - Uma Casa por condição, 10 rodadas medidas por condição (mais 2 de aquecimento), ordem das condições alternada a
    cada rodada, e o teste espera o outbox esvaziar antes de cada disparo. Três corridas, cada uma com banco próprio.
- **Correção, nas três corridas** (234 asserções, 0 falhas): os 21 responderam 201, 21 votos gravados (um por
  vereador), a corrente de cada rodada tem 21 pares tentativa+desfecho (com) ou 21 desfechos sem apontamento (sem), a
  corrente selada confere inteira e nenhuma tentativa ficou sem desfecho. Nenhum voto perdido, deadlock, timeout ou 5xx.
- **Números** (210 requisições por condição em cada corrida; parede = do disparo até o 21º responder):

| Corrida | Condição | Parede por rodada, mediana (mín–máx), ms | Latência por requisição, mediana / p95 / máx, ms |
|---|---|---|---|
| 1 | sem | 198 (167–311) | 129 / 232 / 309 |
| 1 | com | 222 (139–569) | 125 / 416 / 539 |
| 2 | sem | 282 (183–1099) | 152 / 1031 / 1091 |
| 2 | com | 349 (174–651) | 218 / 510 / 595 |
| 3 | sem | 226 (133–666) | 123 / 561 / 663 |
| 3 | com | 255 (191–1192) | 169 / 365 / 1190 |
| 30 rodadas | sem | 212 (133–1099) | |
| 30 rodadas | com | 255,5 (139–1192) | |

  Pareando cada rodada (com menos sem, mesma rodada, ordem alternada): mediana +35,5 ms, de −705 a +1059 ms; a
  rodada com a tentativa foi a mais lenta em 19 das 30.
- **Ambiente.** VM OrbStack de 3,9 GiB e 8 processadores, compartilhada com outras sessões: durante as corridas havia
  outros containers a 100–213% de CPU. Postgres local em container, pool de trabalho de 10 conexões, Postgres e JVM
  sem ajuste. Sem Jetty, sem rede e sem 21 celulares de verdade.
- **O que os números sustentam.**
  - A mediana da parede com a tentativa ficou acima da sem nas três corridas (+24, +67 e +29 ms).
  - A mediana de "sem" varia mais entre corridas (198 a 282 ms) do que essa diferença, e as duas condições têm rodadas
    lentas sem relação com a tentativa: acima de 650 ms, "sem" teve 3 (666, 803, 1099) e "com" teve 2 (651, 1192).
  - O p95 e o máximo por requisição não separam as condições: dependem de qual corrida pegou carga alheia na VM.
  - Em resumo: o custo mediano da tentativa nos 21 votos é de dezenas de milissegundos (+35,5 ms pareado, +43,5 ms entre
    as medianas das 30 rodadas) e fica dentro do ruído do ambiente na cauda. Toda rodada, das duas condições, terminou
    em menos de 1,2 s.
- **O que não foi medido:** Jetty e rede reais, o Postgres de produção, escritas de outros tipos ao mesmo tempo na
  mesma Casa e mais de uma Casa votando ao mesmo tempo. O plano B (Eixo 5b-ii) continua não implementado e, com estes
  números, sem gatilho.

**A garantia:** o ato só fica fora da trilha se a própria trilha estiver fora no momento do ato, e isso fica no log.

**O que ela cobre:**

- queda do processo ou do banco entre o ato e o registro;
- falha na gravação do desfecho;
- toda rota de escrita de ator da Casa, inclusive as do agente.

**O que ela NÃO cobre:**

- **A trilha fora antes do ato** (modo padrão): a tentativa não grava, o ato acontece e, se o desfecho também não
  gravar, não há linha na corrente — só o `log/error`. Quem precisa que isso nunca aconteça liga
  `AUDITORIA_EXIGIR_TENTATIVA=true` e aceita que a trilha fora para a Casa.
- **Não diz se o ato aconteceu.** A tentativa sem desfecho também aparece quando o processo cai antes de o ato
  commitar. Ela aponta onde conferir (ação, recurso, quem, quando); a conferência é do auditor.
- **A entrada (login):** o handler do mint não tem ator antes de rodar; segue com um registro só, gravado depois.
- **Um pedido que dure mais de 2 minutos** aparece como sem desfecho até terminar.
- **O que não passa por HTTP** (consumidores do outbox, jobs) não era auditado e continua não sendo.
- **Quem escreve direto no banco** continua fora, como antes: é o que o selo e a âncora cobrem.

**Pendências da trilha depois deste adendo:**

- o resumo legível por ação (item 3 acima);
- step-up (item 4) e o verificador offline (item 5);
- a entrada sem tentativa (acima).
