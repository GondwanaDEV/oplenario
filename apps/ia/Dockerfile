# Satélite de IA do O Plenário (ADR-0006). Imagem de produção: só as dependências de runtime.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

# EXTRAS=asr instala o ASR/diarização self-host (sherpa-onnx); os modelos entram por volume em /modelos.
ARG EXTRAS=""
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir ".${EXTRAS:+[$EXTRAS]}" && useradd --create-home --uid 10001 ia
USER ia

EXPOSE 8090
# A API (leitura da transcrição pelo core). O trabalhador da Faixa A é a MESMA imagem com outro comando:
#   docker run ... oplenario-ia oplenario-ia-trabalhador
CMD ["uvicorn", "oplenario_ia.app:app", "--host", "0.0.0.0", "--port", "8090"]
