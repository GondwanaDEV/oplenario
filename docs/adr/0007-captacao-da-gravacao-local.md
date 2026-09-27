# ADR-0007 — Captação da gravação local: utilitário `apps/captacao`, papel `captacao` e vínculo pós-sessão

- **Status:** Aceito · 2026-09-26
- **Decisor:** Daouda Traore (CTO) — execução da fatia **A.2** do plano da Track IA (`docs/26`), sob o "Confirmo" dado
  com o merge do PR #38.
- **Fonte canônica:** §16.4 (utilitário CLI/pasta observada mínimo), §22.3.4 (fonte primária = gravação local
  pós-sessão, object storage BR, `fonte_ingestao`), §22.6 eixo D (segmento de gravação, vínculo Opção A), §22.5
  (papéis como dado). Em conflito, a SSOT prevalece.
- **Aplica-se a:** `apps/captacao/` (novo), `apps/backend` (`sessoes`: rota de ingestão, vínculo, fila de pendentes),
  `apps/frontend` (tela `/gravacoes`), `.github/workflows/ci.yml` (job `captacao`).

## Contexto

A §22.3.4 fixou a fonte primária da V1: o arquivo que o OBS grava **no PC da transmissão**, enviado depois da sessão
por um "endpoint de ingestão padronizado", operacionalizado pelo "utilitário CLI/watch folder mínimo da §16.4". O
endpoint existia (`POST /gravacoes`, F4.4b), mas faltavam três coisas: (1) o utilitário; (2) uma credencial para o PC
da transmissão — só o papel `secretario` enviava, e pôr a credencial da secretaria num PC de estúdio dá a ele todos os
poderes dela; (3) o vínculo depois da sessão — `vincular-gravacao` recusava sessão encerrada (gate de condução do
ledger da Fase 8), o que tornava a fonte primária impossível.

## Decisão

1. **`apps/captacao/`** — utilitário em Python **só com a biblioteca padrão** (roda no PC da transmissão sem instalar
   nada além do Python; pode virar executável único depois). Dois comandos: `enviar ARQUIVO [--sessao]` e
   `observar PASTA`. Na pasta observada, um arquivo está pronto quando tamanho e data ficam iguais por um intervalo
   (o OBS não avisa que terminou); o que já subiu fica registrado num JSON na própria pasta (reiniciar não reenvia).
   Falha **transitória** (rede, 5xx, 408, 429) tenta de novo com espera crescente; **definitiva** (outro 4xx) é
   registrada e não trava a fila. Envio em streaming (2 GiB não passam pela memória). Início da gravação pelo nome
   padrão do OBS, no fuso do PC (deslocamento fixo, padrão −03:00); fim pela última escrita.
2. **Papel `captacao`** — a credencial do PC da transmissão: uma conta de serviço OIDC por Casa (`client_credentials`
   no realm do tenant), vinculada ao papel `captacao`, que **só envia arquivos** (`POST /gravacoes`). Vincular, ver a
   fila e tudo o mais seguem da secretaria. Papel é dado (`identidade.usuario_papel`, vocabulário extensível): nenhuma
   migration.
3. **Vínculo pós-sessão** — gravação é **registro** da sessão, não escrita de **condução**: sai do gate
   `exigir-sessao-aberta!` e ganha o seu (`estados-sem-gravacao`): sessão `encerrada` e `arquivada` recebem gravação
   (a primeira é o fluxo da V1; a segunda, a importação de áudio histórico); `nao_realizada` recusa (409), tanto no
   vínculo quanto na ingestão já vinculada. O sigilo continua re-derivado no vínculo (sessão secreta força
   `acesso_restrito`).
4. **Fila de pendentes** — `GET /gravacoes/pendentes` (secretaria): as gravações sem sessão, cada uma com a sessão
   **sugerida pelo horário** (regra pura: janela da sessão ± 2 h, sem encerramento presume 6 h; vence o início mais
   próximo; `nao_realizada` nunca). A tela `/gravacoes` vincula em um toque ou deixa escolher outra sessão. **Só
   sugere**: quem vincula é uma pessoa.

## Consequências

- A fonte primária da §22.3.4 funciona de ponta a ponta: OBS → pasta → utilitário → object storage → fila → vínculo.
- O PC da transmissão, que fica em estúdio e é o equipamento mais exposto da Casa, carrega a menor credencial possível.
- A provisão da conta de serviço `captacao` no Keycloak de cada Casa é passo de implantação (README do utilitário);
  o IdP real do tenant segue o item aberto do CLAUDE.md §3.
- A gravação vinculada ainda não é consumida por ninguém: a transcrição (A.3) é a próxima fatia.

## Alternativas descartadas

- **Upload pelo navegador** — o arquivo de uma sessão passa de 1 GB e o navegador da secretaria não é onde ele está;
  o §16.4 pede o utilitário. Fica possível depois, sem mudar o core.
- **Credencial da secretaria no PC da transmissão** — privilégio excessivo num equipamento exposto.
- **Vincular automaticamente pela sugestão** — erro de horário (relógio do PC, sessão extraordinária no mesmo dia)
  poria a gravação na sessão errada, e a ata seria gerada da sessão errada. Sugerir e deixar a pessoa confirmar custa
  um toque.
- **Dependências externas no utilitário (requests, watchdog)** — exigiriam instalar pacotes no PC da Câmara; a
  varredura periódica basta para arquivos que levam horas para terminar.
