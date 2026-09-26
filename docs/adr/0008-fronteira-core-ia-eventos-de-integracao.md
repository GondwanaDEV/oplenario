# ADR-0008 — Fronteira core ↔ IA: eventos de integração por feed e caixa de entrada, conteúdo sob demanda

- **Status:** Aceito · 2026-09-26
- **Decisor:** Daouda Traore (CTO) — execução da fatia **A.3** do plano da Track IA (`docs/26`), sob o "Confirmo"
  dado com o merge do PR #38.
- **Fonte canônica:** §22.3 (contrato core↔IA: §22.3.1 topologia híbrida, §22.3.2 protocolo, §22.3.3 eventos de
  integração, §22.3.4 propriedade de dados, §22.3.5 erros), §22.6 eixo D/E (transcrição, Caminho C), §22.9 Eixo 11
  (transporte), §22.10 (monólito modular). Em conflito, a SSOT prevalece.
- **Aplica-se a:** `apps/backend` (módulo novo `integracao_ia`; `sessoes` ganha o ponteiro da transcrição) e
  `apps/ia` (cliente do core, armazenamento próprio, trabalhador de transcrição).

## Contexto

O core emite `gravacao.segmento-vinculado` desde a F4 e ninguém consome. A §22.3 decidiu **o quê** atravessa a
fronteira (eventos de integração pequenos e versionados, distintos dos eventos de domínio; conteúdo grande por URI;
idempotência; `ente_id` + `correlation_id`; 6 categorias de erro) e que "separação lógica não exige separação
física". Faltava decidir **como** o evento sai do core e como a resposta da IA volta, sem acoplar a disponibilidade
de um lado à do outro.

## Decisão

1. **Módulo `integracao_ia` no core** (bounded context da fronteira; entra na matriz do import-lint). Só ele fala
   com a IA. Dados de outros módulos chegam por **funções injetadas pelo host** (mesmo padrão dos seams existentes);
   ele nunca importa `sessoes`.
2. **Promoção explícita (core → IA).** Um consumidor do outbox traduz evento de domínio em **evento de integração**
   e o grava num **feed append-only** (`integracao_ia.evento_saida`: `seq` crescente, `ente_id`, `tipo`, `versao`,
   `chave` de idempotência única, `payload`). A lista de promoções é código revisado: promover um evento é decisão
   de contrato (§22.3.3). Primeira promoção: **`GravacaoVinculada` v1**, a partir de `gravacao.segmento-vinculado` e
   também de `gravacao.segmento-captado` quando a gravação já chega com sessão (o utilitário enviou com `--sessao`;
   o core não emite "vinculado" para ela). Mesma chave nas duas: o feed tem um evento só.
3. **O satélite puxa (feed com cursor).** `GET /integracao/ia/v1/eventos?depois=<seq>` devolve os próximos eventos;
   o satélite guarda o cursor e é idempotente pela `chave`. O core **nunca** chama o satélite para entregar evento:
   IA fora do ar não trava o relay nem o ato de ninguém; ao voltar, ela retoma do cursor.
4. **Conteúdo sob demanda, no tenant do evento.** O evento carrega URIs, não conteúdo. O satélite lê pela API do core,
   sempre com o `ente_id` explícito no caminho — o core abre a transação daquele tenant (RLS) para responder:
   - `GET /integracao/ia/v1/entes/:ente/gravacoes/:seg/conteudo` — o arquivo, em streaming, do object storage (o
     satélite não recebe credencial do storage);
   - `GET /integracao/ia/v1/entes/:ente/sessoes/:id/contexto` — a sessão, os segmentos e as **falas** (orador, nome,
     tipo, início, fim) que o Caminho C usa.
5. **A IA devolve por caixa de entrada.** `POST /integracao/ia/v1/eventos` recebe `TranscricaoConcluida` v1 /
   `TranscricaoFalhou` v1 (e, nas fatias seguintes, os de ata e resumo). O core valida o schema de `(tipo, versao)`,
   deduplica pela `chave` (`integracao_ia.evento_entrada`, que é também o registro de auditoria do que chegou) e
   aplica o efeito **na mesma transação** do registro, no tenant do evento. Reenvio = 200 sem efeito novo.
6. **Sigilo fail-closed na fronteira.** Gravação de acesso restrito (sessão secreta) **não é promovida** e as rotas de
   conteúdo e contexto a **recusam** — mesmo que alguém peça pelo id. A ata de sessão secreta segue manual na V1.
7. **Credencial de serviço.** As rotas `/integracao/ia/*` não usam o login de pessoas: exigem um segredo
   compartilhado core↔satélite (`OPLENARIO_IA_SEGREDO`, do cofre — Eixo 11f), comparado em tempo constante. Sem o
   segredo configurado, as rotas respondem 503 (desligadas). Transporte: TLS server-side + NetworkPolicy (§22.3.2,
   Eixo 11); mTLS bilateral segue diferido à Rota D.
8. **Versão no nome do contrato e no envelope.** Todo evento de integração tem `tipo` + `versao` inteira; mudança
   incompatível = versão nova convivendo com a antiga durante a transição (§22.3.3).
9. **Satélite com armazenamento próprio** (§22.3.4: a transcrição vive na IA): schema `ia` no Postgres, com papel
   próprio em produção. Guarda o cursor, a fila de trabalhos (idempotente pelo segmento) e as transcrições. O core
   guarda só o **ponteiro** (`sessoes.transcricao_sessao`: situação, métricas, modelos usados — nunca o texto).

## Consequências

- O primeiro consumidor real dos eventos de gravação existe, e a fronteira fica pronta para ata, resumo e
  embeddings sem mudar de forma.
- Latência de entrega = intervalo de consulta do satélite (segundos) — irrelevante para trabalho em lote (transcrição
  e ata levam minutos); o síncrono interativo (copiloto, busca) usa HTTP direto, como a §22.3.1 já previa.
- O feed cresce sem parar; a limpeza (reter N dias depois de todo consumidor confirmar) é operação futura, junto
  da limpeza do outbox.

## Alternativas descartadas

- **Core empurra (webhook para o satélite)** — acopla o relay à disponibilidade da IA (fila travada ou evento
  perdido quando ela cai) e exige retry/DLQ no core. Puxar põe o controle de ritmo em quem faz o trabalho.
- **Satélite lê direto as tabelas do core** — quebra a propriedade de dados (§22.3.4), a RLS e o import-lint.
- **Credencial de storage no satélite** — mais um segredo em mais um lugar, e o satélite poderia ler qualquer
  objeto (inclusive gravação restrita); pelo core, cada leitura passa pelo tenant e pelo sigilo.
- **Broker de mensagens dedicado** — infra nova sem necessidade: o volume é de dezenas de eventos por sessão, e o
  Postgres já é a fila da plataforma (§22.9).
