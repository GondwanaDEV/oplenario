# PoC de áudio — transcrição e "quem falou" em sessão real de Câmara

Fatia **A.1** do plano da Track IA (`docs/26`, Faixa A). Cumpre a decisão da v1.8 do documento-mestre: **medir
a qualidade da diarização (DER) e do Caminho C em Câmara real antes de fechar a arquitetura da ata-IA.** É
protótipo de medição, não produto: o satélite de produção (`apps/ia/`) vem na base comum da Track IA.

## As quatro perguntas

1. **Diarização pura** — o sistema separa bem as vozes? Métrica: DER (fala perdida + falso alarme + confusão).
2. **Caminho C sozinho** — quanto do tempo de fala a "palavra na tribuna" (o que a Mesa registra no O Plenário)
   atribui certo, sem reconhecer voz? Erros esperados: Presidência conduzindo e apartes.
3. **Diarização + Caminho C** — os grupos de voz ganham nome pela palavra da tribuna. Qual o DER com nomes?
4. **Custo de máquina** — fator de tempo real em CPU (dimensiona a GPU a alugar).

## Pilha (tudo roda offline depois do download)

| Peça | Modelo | De onde vem |
| --- | --- | --- |
| Separar vozes | segmentação **pyannote 3.0** + embedding **NeMo TitaNet** + agrupamento | releases do sherpa-onnx (GitHub) |
| Recortar a fala | **Silero VAD** | idem |
| Transcrever | **Whisper** (turbo / medium / large-v3, int8) | idem |
| Rodar | `sherpa-onnx` (ONNX Runtime, CPU), `ffmpeg` do `imageio-ffmpeg` | PyPI |

Por que não pyannote/faster-whisper direto: HuggingFace e o índice do PyTorch estão fora da política de rede
do ambiente; o GitHub e o PyPI estão dentro. Os modelos são os mesmos, só exportados para ONNX.

## Como rodar (Docker, como todo o projeto)

```sh
./rodar.sh python -m pytest -q tests                                  # métricas (sem modelo, sem rede)
./rodar.sh python -m poc.diarizar dados/sessao.mp4 --inicio 1800 --duracao 900
./rodar.sh python -m poc.transcrever dados/sessao.mp4 --inicio 1800 --duracao 900 --modelo turbo
./rodar.sh python -m poc.avaliar dados/sessao.mp4 --inicio 1800 --duracao 900 \
    --referencia dados/referencia.csv --palavra dados/palavra.csv \
    --transcricao dados/sessao.transcricao.turbo.csv                  # -> dados/sessao.relatorio.md
```

A anotação humana (a verdade para comparar) está explicada em `anotacao/COMO-ANOTAR.md`.

## Estado (26/09/2026)

**Pronto:** pipeline de ponta a ponta + métricas (13 testes). Provado no áudio público de exemplo do sherpa-onnx
(57 s, 4 falantes, mandarim) — **só mostra que o fluxo roda; não é resultado**:

| Medição nesta máquina (4 CPUs, sem GPU) | Fator de tempo real | Sessão de 2 h levaria |
| --- | --- | --- |
| Diarização | 0,10–0,12 | ~15 min |
| Whisper turbo (int8) | 0,71 | ~85 min |

Com o número de falantes informado (`--falantes`), a diarização do exemplo casou as vozes de forma consistente;
estimando o número (limiar 0,5) ela dividiu 4 vozes em 8 — o limiar tem de ser calibrado no áudio real.

**Falta para medir de verdade:**
1. **O áudio de Baturité.** O YouTube está bloqueado pela política de rede deste ambiente. Ou libera-se
   `www.youtube.com` + `*.googlevideo.com`, ou a Câmara envia o arquivo gravado localmente no OBS (melhor: sem a
   recompressão do YouTube).
2. **15 minutos anotados** por alguém que reconheça as vozes (`anotacao/COMO-ANOTAR.md`, ~1 hora de trabalho).
3. Depois: rodar Whisper large-v3 além do turbo, para comparar qualidade × custo.
