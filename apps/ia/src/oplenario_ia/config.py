"""Configuração do satélite — sempre do ambiente (deploy-config, §22.9 Eixo 10: fornecedor é config, não código).

O padrão é o fornecedor `fake`: nada sai do cluster até alguém configurar, de propósito, um fornecedor real com DPA de
não-treino (`[GAP]` jurídico, LGPD art. 33). Credenciais do fornecedor não passam por aqui — o SDK oficial as lê do
ambiente (ex.: ANTHROPIC_API_KEY), vindas do cofre (Eixo 11f).
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from typing import Literal

from pydantic import BaseModel, Field

Vendor = Literal["fake", "anthropic"]


class Config(BaseModel):
    vendor: Vendor = "fake"
    modelo: str = "claude-opus-5"
    timeout_s: float = Field(default=60.0, gt=0)
    registro_jsonl: str | None = None  # caminho do registro append-only; None = em memória


def carregar(env: Mapping[str, str] | None = None) -> Config:
    e = os.environ if env is None else env
    dados: dict[str, object] = {}
    if v := e.get("OPLENARIO_IA_VENDOR"):
        dados["vendor"] = v
    if v := e.get("OPLENARIO_IA_MODELO"):
        dados["modelo"] = v
    if v := e.get("OPLENARIO_IA_TIMEOUT_S"):
        dados["timeout_s"] = float(v)
    if v := e.get("OPLENARIO_IA_REGISTRO_JSONL"):
        dados["registro_jsonl"] = v
    return Config.model_validate(dados)
