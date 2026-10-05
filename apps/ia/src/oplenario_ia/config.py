"""Configuração do satélite — sempre do ambiente (deploy-config, §22.9 Eixo 10: fornecedor é config, não código).

O padrão é o fornecedor `fake`: nada sai do cluster até alguém configurar, de propósito, um fornecedor real com DPA de
não-treino (`[GAP]` jurídico, LGPD art. 33). O fornecedor real da plataforma é o OpenRouter (ADR-0023). Credenciais do
fornecedor não ficam na `Config` — vêm do ambiente (OPENROUTER_API_KEY, ou ANTHROPIC_API_KEY lida pelo SDK), do cofre
(Eixo 11f).
"""

from __future__ import annotations

import os
import re
from collections.abc import Mapping
from typing import Literal

from pydantic import BaseModel, Field, model_validator

Vendor = Literal["fake", "openrouter", "anthropic"]
Asr = Literal["fake", "sherpa"]
Embeddings = Literal["fake", "fastembed"]


class Config(BaseModel):
    vendor: Vendor = "fake"
    modelo: str = "claude-opus-5"
    timeout_s: float = Field(default=60.0, gt=0)
    # OpenRouter (ADR-0023): o modelo é o slug do catálogo dele (`openai/gpt-oss-120b`); `openrouter_provedores`
    # restringe quem pode atender (`provider.only`) — vazio = qualquer provedor que cumpra ZDR e não colete dado.
    openrouter_url: str = "https://openrouter.ai/api/v1"
    openrouter_provedores: list[str] = Field(default_factory=list)
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
    # O índice de busca (A.4): embeddings SELF-HOST (§22.9 Eixo 10). `fake` = hashing determinístico (dev/CI).
    embeddings: Embeddings = "fake"
    modelo_embeddings: str = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"

    @model_validator(mode="after")
    def _modelo_do_openrouter(self) -> Config:
        if self.vendor != "openrouter":
            return self
        # no OpenRouter o modelo leva o prefixo do fabricante; sem modelo explícito, o padrão vira o slug dele
        if "modelo" not in self.model_fields_set:
            self.modelo = MODELO_OPENROUTER_PADRAO
        if not modelo_openrouter_fixo(self.modelo):
            raise ValueError(
                "OPLENARIO_IA_MODELO: esperado `autor/modelo` fixo, em minúsculas — sem `openrouter/*`, alias `~` nem"
                " `:online` (ADR-0023)"
            )
        return self


MODELO_OPENROUTER_PADRAO = "openai/gpt-oss-120b"  # provisório (05/10/2026), ADR-0023

# Os modelos com que o satélite pode SUBIR pelo OpenRouter (ADR-0023). Entra aqui o que passou pela avaliação
# (`oplenario-ia-avaliar`, R-IA-4) — a avaliação em si monta a `Config` direto e roda qualquer slug fixo.
MODELOS_OPENROUTER_PERMITIDOS: frozenset[str] = frozenset(
    {
        MODELO_OPENROUTER_PADRAO,
        "qwen/qwen3.8-27b:free",  # gratuito: limite diário de requisições do OpenRouter, não serve a produção
    }
)
_SLUG_OPENROUTER = re.compile(r"^[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._:-]*$")


def modelo_openrouter_fixo(modelo: str) -> bool:
    """O slug aponta UM modelo (§22.11.8, nunca troca em silêncio). Ficam de fora o roteador e os meta-modelos
    (`openrouter/*`), o alias `~…` (segue a versão mais nova sozinho) e o sufixo `:online` (busca na web: o pedido
    sairia do filtro de governança para um buscador). Mesma regra do munex (ADR-0053 de lá)."""
    m = modelo.lower()
    return (
        _SLUG_OPENROUTER.fullmatch(modelo) is not None
        and not m.startswith("openrouter/")
        and re.search(r":online(:|$)", m) is None
    )


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
    if v := e.get("OPLENARIO_IA_OPENROUTER_URL"):
        dados["openrouter_url"] = v
    if v := e.get("OPLENARIO_IA_OPENROUTER_PROVEDORES"):
        dados["openrouter_provedores"] = [p.strip() for p in v.split(",") if p.strip()]
    for var, campo in (
        ("OPLENARIO_CORE_URL", "core_url"),
        ("OPLENARIO_IA_SEGREDO", "segredo"),
        ("OPLENARIO_IA_DATABASE_URL", "database_url"),
        ("OPLENARIO_IA_ASR", "asr"),
        ("OPLENARIO_IA_MODELOS", "modelos_dir"),
        ("OPLENARIO_IA_WHISPER", "whisper"),
        ("OPLENARIO_IA_IDIOMA", "idioma"),
        ("OPLENARIO_IA_INTERVALO_S", "intervalo_s"),
        ("OPLENARIO_IA_EMBEDDINGS", "embeddings"),
        ("OPLENARIO_IA_MODELO_EMBEDDINGS", "modelo_embeddings"),
    ):
        if v := e.get(var):
            dados[campo] = v
    config = Config.model_validate(dados)
    if config.vendor == "openrouter" and config.modelo not in MODELOS_OPENROUTER_PERMITIDOS:
        permitidos = ", ".join(sorted(MODELOS_OPENROUTER_PERMITIDOS))
        raise ValueError(f"OPLENARIO_IA_MODELO fora da lista de modelos permitidos do OpenRouter ({permitidos})")
    return config
