# ADR-0018 — Suspender e encerrar uma Casa: o que cada estado faz, quem decide e o que acontece com os dados

- **Status:** ✅ **Aceito** (30/09/2026 — "gostei do plano e vamos manter", as recomendações dos cinco eixos como
  escritas). **Fatia 1 implementada em 30/09/2026; fatia 2 (encerrar) em 02/10/2026** — ver as seções *Materialização* no fim.
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
  - **Orçamento definido com a Casa suspensa (regra de 05/10/2026): a suspensão vence.** O valor novo fica guardado e
    é o que a reativação devolve, no lugar do de antes da suspensão; até lá a cota segue zero e o satélite só recebe o
    0/0. Antes, a definição nova reabria a cota da Casa suspensa e a reativação não fazia nada. O `ia-orcamento` avisa
    na saída e no resumo da atuação ("a cota segue zero e este valor vale a partir da reativação").
    Ver `integracao_ia/components/repositorio.clj` (`definir-com-a-casa-suspensa!`) e
    `admin_sistema/suspender_casa_test`.
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

## Materialização — fatia 2: encerrar (02/10/2026)

A sequência do Eixo 4 está inteira:
**exportação entregue → confirmação → guarda de 90 dias → apagamento por dois operadores → `encerrado`**.

**O registro (supratenant, sobrevive ao apagamento):**

- Migration `…170`: `admin_sistema.exportacao_casa` (hash, manifesto e confirmação), pedido `apagar`/`fim_da_guarda`,
  e `ente.encerrada_em`, `destino_acervo_url` e `apagamento` (o resumo).
- Migration `…175`: a Casa `encerrado` é imutável por trigger. A linha é a prova e nem se apaga; só o destino do acervo
  continua editável. A confirmação de recebimento também não se desfaz.

**A exportação completa (9.6), em `oplenario.encerramento`:**

- O inventário dos dados da Casa é **descoberto no catálogo do Postgres** (`admin_sistema.inventario_da_casa()`):
  - entram as tabelas com `ente_id` e as filhas por FK;
  - ficam de fora `admin_sistema`, `ia` e as tabelas de referência.

  Uma tabela nova de tenant entra sozinha, e a exportação e o apagamento leem o mesmo inventário.
- O ZIP, em formato aberto, traz:
  - `dados/<schema>/<tabela>.csv` e `dicionario.csv`;
  - `arquivos/` com os blobs da Casa;
  - `auditoria/`, com a corrente conferida (ADR-0017) e os selos do dia;
  - `manifesto.json` com o sha256 de cada arquivo, e um `LEIA-ME.txt`.
- A leitura é feita pelo role de runtime, com a RLS da Casa: a exportação de uma Casa não enxerga outra (testado com
  duas Casas).
- **O cidadão sai pseudonimizado**, com o mesmo pseudônimo da tela da trilha (ADR-0017 4c), para proteger a
  manifestação anônima (LGPD; Lei 13.460 art. 10 §7º).
  - O id dele não aparece em nenhum byte do ZIP. O protocolo de manifestação sai sem autor e sem IP.
  - A corrente é conferida **antes** de pseudonimizar e está ancorada nos selos públicos.
  - Servidores, vereadores e agentes seguem identificados, porque são atos da função pública.
- **Quem gera:**
  - o `admin_ente`, a qualquer momento, em `/administracao` ("Exportar os dados da Câmara");
  - o operador, só com o encerramento em curso.
- **Quem baixa é só o `admin_ente` da Casa.** O operador vê metadados (estado, bytes, sha256), nunca o conteúdo: somos
  operador LGPD.
- A Casa confirma o recebimento vendo o SHA-256, ou o operador registra o ofício.
- Só vale a confirmação feita **depois** do início do encerramento. A de portabilidade não abre a guarda.

**O apagamento (Eixo 4.5):**

- O app não é dono das tabelas, e há triggers de imutabilidade. Por isso o banco é apagado por uma função **`SECURITY
  DEFINER`** do dono (`admin_sistema.apagar_dados_da_casa`, migration `…171`).
  - Ela **confere no próprio banco** o pedido `apagar` aprovado por outro operador, a Casa em encerramento e a
    exportação confirmada há 90 dias ou mais.
  - Os triggers são desligados só dentro da transação: `session_replication_role` com dono superuser; lock + `DISABLE
    TRIGGER` com dono gerenciado.
  - EXECUTE é só da Operação.
- O apagamento remove também:
  - os blobs e as exportações, no object storage (`listar` entrou no `ObjetoStore`);
  - o **realm** da Casa no Keycloak;
  - os dados da Casa no **satélite de IA** (`DELETE /v1/entes/{ente}`).
- **Retomável:**
  - IdP ou satélite fora deixam o passo **pendente**, e a Casa **não** vira `encerrado` com dado vivo em outro lugar;
  - o console oferece "Retomar";
  - o resumo parcial fica selado na atuação e se soma ao da retomada, para não perder a contagem.
- Fica conosco o resumo selado: tabelas, totais, objetos e o **hash da exportação entregue**. Também ficam a atuação da
  Operação e o evento `admin_sistema.casa.encerrada`.

**A Casa encerrada (Eixo 4.4 a + c; Eixo 5):**

- `encerrado → *` nunca acontece.
- Toda rota da Casa, inclusive a leitura, o portal e o login, responde **410** com a data e o destino do acervo.
- O portal mostra "Esta Câmara não usa mais O Plenário" com o link informado pelo operador.

**Durante o encerramento**, os protocolos do cidadão seguem abertos, como na suspensão (Eixo 2): o prazo legal corre.
A allowlist ganhou gerar e confirmar a exportação pelo `admin_ente`.

**Testes:**

- Integração de ponta a ponta com o plano de dados **real**: rotas → exportação → confirmação → 90 dias → apagamento
  two-person → `encerrado` → 410.
- O teste de vazamento ganhou a dimensão "Casa encerrada".
- A função SQL é testada com recusas, Casa vizinha intacta, triggers e RLS restaurados, e dono não-superuser.

**Riscos e o que ficou de fora:**

- **Escrita e evento atrasados — fechados (migration `…177`, 02/10/2026):**
  - **a Casa fecha quando o apagamento começa** (`apagamento_iniciado_em`, selado na atuação como "apagamento
    iniciado"). Dali em diante toda rota dela responde 410, inclusive a allowlist do cidadão e a trilha. O estado de
    uma Casa com o encerramento em curso não entra no cache de 30 s: cada requisição lê o registro, e todas as
    instâncias fecham na hora;
  - **a função do banco espera o relay** antes do primeiro DELETE: o evento que o relay já pegou termina antes, e o que
    o consumidor gravou sai junto. O teste falha sem a espera;
  - **a varredura:** depois do realm e do satélite, o host roda a função e os blobs da convenção de novo. Ela pega a
    escrita que estava em voo quando a Casa fechou. O resumo diz quanto a varredura achou (`varredura`).
  - Sobra só uma requisição que passou pelo portão antes de a Casa fechar e demorou mais que o apagamento inteiro.
- **Concorrência do apagamento — fechada:** uma execução por Casa **entre instâncias**, por um lease no registro
  (`apagamento_em_execucao_desde`, vence em 15 min para que uma instância que caiu não trave a retomada). A 2ª recebe
  409 `apagamento-rodando`. O `encerrado` solta o lease na mesma transação.
- **Pseudônimo do cidadão:** 12 caracteres hex (48 bits); com 6, dois cidadãos se confundiam com poucos milhares numa
  Casa. A exportação **recusa** a colisão em vez de fundir duas pessoas no arquivo.
- **Blob fora da convenção** `<pasta>/<ente>/`: é relatado no resumo e não é apagado.
- **Ainda não existem:**
  - o aviso por e-mail (SMTP, `[GAP]` de infra);
  - o anexo do ofício (só o texto);
  - a importação de volta (16.9) para uma Casa que retorna como Casa nova.

