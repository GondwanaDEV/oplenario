# Satélite de IA do O Plenário (ADR-0006). Imagem de produção: só as dependências de runtime.
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir . && useradd --create-home --uid 10001 ia
USER ia

EXPOSE 8090
CMD ["uvicorn", "oplenario_ia.app:app", "--host", "0.0.0.0", "--port", "8090"]
