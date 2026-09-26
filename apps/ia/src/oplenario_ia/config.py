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
Asr = Literal["fake", "sherpa"]


class Config(BaseModel):
    vendor: Vendor = "fake"
    modelo: str = "claude-opus-5"
    timeout_s: float = Field(default=60.0, gt=0)
    registro_jsonl: str | None = None  # caminho do registro append-only; None = em memória
    # Fronteira com o core (ADR-0008) e o trabalho da Faixa A.
    core_url: str | None = None
    segredo: str | None = None  # OPLENARIO_IA_SEGREDO — o mesmo do core, vindo do cofre
    database_url: str | None = None  # schema `ia`; None = armazenamento em memória (só dev/teste)
    asr: Asr = "fake"
    modelos_dir: str = "/modelos"
    whisper: str = "turbo"
    idioma: str = "pt"
    intervalo_s: float = Field(default=15.0, gt=0)


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
    for var, campo in (
        ("OPLENARIO_CORE_URL", "core_url"),
        ("OPLENARIO_IA_SEGREDO", "segredo"),
        ("OPLENARIO_IA_DATABASE_URL", "database_url"),
        ("OPLENARIO_IA_ASR", "asr"),
        ("OPLENARIO_IA_MODELOS", "modelos_dir"),
        ("OPLENARIO_IA_WHISPER", "whisper"),
        ("OPLENARIO_IA_IDIOMA", "idioma"),
        ("OPLENARIO_IA_INTERVALO_S", "intervalo_s"),
    ):
        if v := e.get(var):
            dados[campo] = v
    return Config.model_validate(dados)
