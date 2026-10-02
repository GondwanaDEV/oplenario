# ADR-0018 — Suspender e encerrar uma Casa: o que cada estado faz, quem decide e o que acontece com os dados

- **Status:** ✅ **Aceito** (30/09/2026 — "gostei do plano e vamos manter", as recomendações dos cinco eixos como
  escritas). **Fatia 1 implementada em 30/09/2026** — ver *Materialização — fatia 1* no fim.
- **Contexto de decisão:**
  - `produto/13` 12.1: ciclo `provisionar → ativo → suspenso → encerrado`;
  - 9.6: portabilidade / saída do contrato — o ciclo `encerrado` não tinha feature de saída (G18);
  - §22.10 (`admin_sistema` supratenant);
  - §22.5 (LGPD: atos da função pública não se apagam; apagamento é ato registrado);
  - ADR-0016 ("suspender/encerrar ficam para fatia seguinte, **com o portal da LAI no ar**").
- **Relacionadas:** ADR-0014 (cota de IA), ADR-0016 (registro de Casas, atuação selada), ADR-0017 (trilha de
  auditoria).

## Contexto

- **O que existe:** `admin_sistema.ente.estado` já aceita os quatro estados, mas **só `provisionar → ativo` acontece**,
  pelo handoff. Nada no runtime lê o estado.
  - Uma Casa marcada `suspenso` à mão no banco continuaria funcionando igual: login, escrita, portal, agente, IA.
  - O console não oferece a transição.
- **O que pesa:** a Casa é um órgão público.
  - O **portal** é transparência ativa obrigatória dela (LAI art. 8).
  - O e-SIC e a ouvidoria têm **prazo legal correndo** (LAI 20+10, Lei 13.460).
  - Os atos (leis, atas, votos) são **documento público permanente**.
  - Tirar a Casa do ar por inadimplência pode deixar a **Casa** descumprindo a lei — e a nós no meio. É o risco que
    este ADR precisa desenhar.

## Eixos para decidir

### Eixo 1 — Quem suspende, por quê, e com que garantia

- **1a. Motivos** — uma lista fechada, com justificativa em texto e selo na atuação:
  - `inadimplencia`;
  - `pedido_da_casa`;
  - `ordem_judicial`;
  - `incidente_de_seguranca`.

  Recomendação: esses quatro. "Outro" não entra — força a nomear o motivo.
- **1b. Quem decide:**
  - (i) Um operador. ✘ Um clique derruba um órgão público.
  - (ii) **Dois operadores (two-person rule), recomendado.** Um pede e outro aprova, os dois com chave física. É o
    mesmo padrão do reset de chave do admin interno (§22.5 Eixo F).
    - Exceção: `incidente_de_seguranca` suspende com um só operador e exige a segunda aprovação em até 24 h; sem
      ela, a Casa volta a ativa.
  - (iii) Automático por billing. ✘ Não existe billing (12.2 parqueado) e, mesmo quando existir, não deve derrubar
    Casa sem pessoa.
- **1c. Aviso à Casa:**
  - antes da suspensão por inadimplência: aviso ao `admin_ente` com **prazo** (recomendado: 15 dias, com lembrete a
    7 e a 1);
  - nos outros motivos: aviso imediato, com o motivo.

### Eixo 2 — O que "suspensa" faz, por público

| Público | Opções | Recomendação |
|---|---|---|
| **Portal do cidadão** (leitura) | (a) fora do ar · (b) no ar, só leitura | **(b) sempre no ar** — é a transparência ativa da Casa, e derrubá-la cria descumprimento da LAI |
| **Protocolos do cidadão** (e-SIC, ouvidoria, LGPD) | (a) bloqueados com aviso + canal alternativo · (b) seguem abertos | **(b)** — o direito de pedir não depende do contrato da Casa; o recibo diz que a Casa está com o sistema restrito |
| **Servidores e vereadores** | (a) sem acesso · (b) só leitura · (c) só leitura + **responder protocolos do cidadão** + exportar | **(c)** — a Casa não fica sem cumprir prazo legal por nossa causa, mas não opera o legislativo |
| **Sessão ao vivo** | (a) cai na hora · (b) suspensão **agendada** para depois do encerramento da sessão em curso | **(b)** — nunca derrubar um plenário no meio da votação; exceção: `ordem_judicial` e `incidente_de_seguranca` cortam na hora |
| **IA da Casa** | (a) segue · (b) pausa (cota zero) | **(b)** — o que a IA faz sozinha (resumo, conferência) é custo nosso sem contrato |
| **Motor de compliance / prazos** | (a) para · (b) segue calculando e avisando | **(b)** — prazo que vence em silêncio é o pior incidente do produto (CLAUDE.md §5) |

**Em todo lugar:** uma faixa "Sistema da Câmara com acesso restrito desde DD/MM — motivo: …" (para a cidadã, só
"acesso restrito"; o motivo comercial não é público).

### Eixo 3 — Onde a regra é aplicada

- **(i) No backend, recomendado.**
  - O interceptor de autenticação da Casa lê o estado do registro por um seam do host, com cache curto (30 s).
  - Com a Casa `suspenso`, escrita fora da allowlist do Eixo 2 recebe **423 Locked** com o motivo público; leitura
    passa.
  - O catálogo de ações (ADR-0009) já classifica cada rota em `leitura`/`rascunho`/`ato`, então a allowlist é um
    conjunto pequeno de rotas nomeadas.
- **(ii) Desabilitar o realm da Casa no Keycloak.** ✘ Mata também o login do cidadão pelo gov.br e o do servidor
  que precisa responder protocolo. Não serve para "só leitura".
- **(iii) No frontend.** ✘ Não é controle.

**Consequência:** a transição emite `admin_sistema.casa.suspensa` / `.reativada` / `.encerrada` pelo outbox, e o
estado entra no teste de vazamento. Uma Casa suspensa não escreve fora da allowlist, o que vira gate de CI como o
cross-tenant.

### Eixo 4 — Encerrar (fim do contrato)

O encerramento é irreversível, por isso tem sequência:

1. **Pedido** — da Casa, por ofício (anexado), ou nosso, por fim de contrato.
   - Dois operadores aprovam.
   - A Casa passa a `suspenso` com motivo `encerramento_em_curso`.
2. **Exportação completa (9.6)** entregue à Casa:
   - dados, textos, atas, votos, trilha de auditoria com a corrente verificável (ADR-0017) e arquivos;
   - formato aberto (CSV/JSON + PDFs + o dicionário);
   - a Casa **confirma o recebimento** (registrado).
3. **Janela de guarda**, só leitura + exportar de novo. Opções: 30 · **90 (recomendado)** · 180 dias.
4. **Portal depois da janela:**
   - (a) fora do ar, com a página "esta Câmara não usa mais O Plenário; os documentos estão em …";
   - (b) arquivo estático público mantido por N anos (custo nosso);
   - (c) redirecionar para o novo sistema, se a Casa informar.

   Recomendação: **(a) + (c)** — o acervo público passa a ser responsabilidade de quem a Casa escolher; o que
   guardamos é o registro de que entregamos.
5. **Dados de tenant depois da janela.** Somos operador (LGPD), a Casa é controladora.
   - (a) **Apagar tudo do banco e do object storage (recomendado).** O apagamento é um ato selado na atuação da
     Operação, com a lista de tabelas, os totais e o hash da exportação entregue.
   - (b) Manter um arquivo cifrado com chave da Casa por N anos. ✘ Custo e responsabilidade de custódia que não são
     nossos.
   - (c) Pseudonimizar. ✘ Não se aplica a ato público.
6. **Fica conosco, supratenant:**
   - a linha do registro (`encerrado`, com datas);
   - a atuação da Operação;
   - o selo do dia (ADR-0017);
   - o hash da exportação entregue.

   É a prova de que entregamos e de que apagamos.

**Recomendação de salvaguarda:** o passo 5 exige os dois operadores de novo e roda depois de no mínimo 90 dias da
confirmação de recebimento. Sem confirmação, não apaga.

### Eixo 5 — Voltar

- `suspenso → ativo`: um operador, com motivo (ex.: pagamento regularizado), selado. Recomendação: sim.
- `encerrado → *`: **nunca.** Uma Casa que volta é uma Casa nova, provisionada de novo e importada pela migração
  (16.9) a partir da exportação que ela recebeu. Recomendação: sim — mantém o encerramento honesto.

## Proposta de primeira fatia (se os eixos saírem como recomendado)

1. **Backend (`admin_sistema`):**
   - as transições `suspender` (pedido + aprovação), `reativar` e `iniciar-encerramento`, com motivo, selo e evento;
   - o seam `estado-da-casa` no host;
   - o interceptor de Casa com a allowlist do Eixo 2 e o 423;
   - a suspensão agendada quando há sessão aberta;
   - a cota de IA zerada enquanto suspensa;
   - o teste de vazamento com a 3ª dimensão (estado).
2. **Console:** na ficha da Casa, "Suspender" (motivo + justificativa), a fila "aguardando 2º operador" e
   "Reativar".
3. **Casa e portal:** a faixa de acesso restrito; os formulários do cidadão seguem.
4. **Fora da fatia:**
   - a exportação completa (9.6) e o apagamento, que são a fatia 2, pré-requisito de "encerrar";
   - o aviso por e-mail, que depende do SMTP de produção (`[GAP]` de infra).

## O que peço para decidir

| Eixo | Pergunta | Recomendação |
|---|---|---|
| 1 | Quem suspende | 4 motivos fechados; **dois operadores** (incidente: um, confirmado em 24 h); aviso de 15 dias na inadimplência |
| 2 | O que a suspensão faz | portal **no ar**; protocolos do cidadão **abertos**; servidor **só leitura + responder protocolos + exportar**; nunca no meio de sessão; IA pausada; prazos seguem |
| 3 | Onde aplicar | **backend** (interceptor + seam do registro, 423), não no Keycloak |
| 4 | Encerrar | exportação entregue e confirmada → **90 dias** de guarda → portal com aviso/redirecionamento → **apagar**, selado, com dois operadores |
| 5 | Voltar | suspensa volta com um operador; encerrada **nunca** (Casa nova + importação) |

Depois do "Confirmo" (com as correções), esta ADR vira **Aceita** e a fatia 1 começa.

## Materialização — fatia 1 (30/09/2026)

**O que existe:**

- **Transições em `admin_sistema`** (mig `…160`):
  - **Suspender** é pedido de um operador e aprovação de outro. Quem pede não aprova, e o banco também recusa.
    Os motivos são os 4 fechados do Eixo 1.
  - **Incidente de segurança:**
    - um operador só já suspende;
    - se o 2º operador não confirmar em 24 h, a Casa volta a ativa. A verificação é preguiçosa: acontece na leitura
      do estado, sem job.
  - **Reativar** pede um operador, com motivo.
  - **Iniciar o encerramento** é pedido e aprovação, por dois operadores. A Casa fica `suspenso` com o motivo
    `encerramento_em_curso`.
  - Toda atuação fica selada na corrente da Operação (ADR-0016). Os eventos `admin_sistema.casa.suspensa` e
    `.reativada` saem pelo outbox.
- **Sessão ao vivo:** a suspensão aprovada com sessão em curso fica **agendada** até o encerramento da sessão. Ordem
  judicial e incidente cortam na hora.
- **Onde a regra vale (Eixo 3):**
  - o seam `estado-da-casa` no host, com cache de 30 s, e a restrição no interceptor de autenticação da Casa e no do
    agente (`oplenario.restricao-da-casa`);
  - escrita fora da allowlist recebe **423** "acesso restrito" (o motivo público, nunca o comercial);
  - a leitura passa sempre;
  - a trilha (ADR-0017) registra a recusa.
- **A allowlist (Eixo 2)** fica escrita por nome no código e é revisada em PR. Toda escrita nova nasce bloqueada. O que
  passa:
  - os protocolos do cidadão: e-SIC, recurso, ouvidoria, LGPD, comentário e seguir;
  - os servidores respondendo a esses protocolos, com o prazo legal correndo;
  - a remessa ao TCE: validar, submeter e registrar a resposta (o compliance segue);
  - nomear o encarregado de dados (LGPD art. 41);
  - marcar a notificação como lida.

  Conceder acesso a gente nova **fica bloqueado**, de propósito. O teste de vazamento da 3ª dimensão (estado) percorre
  todas as rotas montadas e compara com uma allowlist escrita à mão.
- **Cota de IA zerada** enquanto a Casa está suspensa, pelo orçamento da ADR-0014.
  - A reativação devolve o que valia antes.
  - Se antes era "só mede", volta a ser "só mede": mig `…161`, orçamento sem valor, aceito também pelo satélite.
- **Console e telas:**
  - Na ficha da Casa em `/operacao`: "Acesso da Câmara", com Suspender (motivo e justificativa), a fila "aguardando 2º
    operador" (aprovar, recusar, retirar), Reativar e Iniciar encerramento.
  - A faixa "acesso restrito desde DD/MM", sem motivo, aparece:
    - no interno, no app do vereador e na área da cidadã;
    - no portal e nos formulários do portal.
  - O recibo do protocolo do cidadão diz que o pedido foi recebido e que o prazo legal está correndo.

**O que ficou para a fatia 2 (nada disso foi esquecido):**

- **Exportação completa (9.6) e apagamento:** são o pré-requisito de sair de `encerramento_em_curso` para `encerrado`.
  Hoje o encerramento para em "suspensa, com encerramento em curso".
- **Aviso por e-mail** (15 dias na inadimplência, aviso da suspensão): depende do SMTP de produção (`[GAP]` de infra).
  O aviso de hoje é a faixa.
- **Anexo do ofício** no pedido de encerramento vindo da Casa: o pedido registra a origem e a justificativa, sem
  arquivo.
- **A allowlist é uma primeira leitura do Eixo 2**, revisável quando uma Casa real passar por isso. Por exemplo:
  publicar um ato já aprovado antes da suspensão hoje fica bloqueado.

