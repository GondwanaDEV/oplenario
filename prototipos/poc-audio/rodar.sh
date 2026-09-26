#!/bin/sh
# Roda um comando da PoC dentro do container oficial do Python (mandato Docker do projeto).
# Uso: ./rodar.sh python -m poc.diarizar audio.wav      |  ./rodar.sh python -m pytest -q tests
# Volumes: poc-audio-venv (dependências), poc-audio-modelos (modelos baixados). O código vai montado só-leitura;
# `dados/` (áudio, anotações, saídas) vai com escrita.
set -e
cd "$(dirname "$0")"
mkdir -p dados
exec docker run --rm --network host \
  -e HTTPS_PROXY -e HTTP_PROXY -e https_proxy -e http_proxy \
  -e PIP_CERT=/ca.crt -e SSL_CERT_FILE=/ca.crt -e PYTHONDONTWRITEBYTECODE=1 \
  -v /root/.ccr/ca-bundle.crt:/ca.crt:ro \
  -v poc-audio-venv:/venv -v poc-audio-modelos:/modelos \
  -v "$PWD":/poc:ro -v "$PWD/dados":/poc/dados \
  -w /poc mirror.gcr.io/library/python:3.12-slim sh -c '
    [ -x /venv/bin/python ] || python -m venv /venv
    /venv/bin/pip install -q --root-user-action=ignore -r requirements.txt >/dev/null 2>&1 || /venv/bin/pip install --root-user-action=ignore -r requirements.txt
    PATH=/venv/bin:$PATH exec "$@"' sh "$@"
