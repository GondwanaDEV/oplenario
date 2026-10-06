"""Configuração do satélite — sempre do ambiente (deploy-config, §22.9 Eixo 10: fornecedor é config, não código).

O padrão é o fornecedor `fake`: nada sai do cluster até alguém configurar, de propósito, um fornecedor real com DPA de
não-treino (`[GAP]` jurídico, LGPD art. 33). O fornecedor real da plataforma é o OpenRouter (ADR-0023). Credenciais do
fornecedor não ficam na `Config` — vêm do ambiente (OPENROUTER_API_KEY, ou ANTHROPIC_API_KEY lida pelo SDK), do cofre
(Eixo 11f).
"""

from __future__ import annotations

import logging
import os
import re
from collections.abc import Callable, Mapping
from typing import Literal

from pydantic import BaseModel, Field, model_validator

Vendor = Literal["fake", "openrouter", "anthropic"]
Asr = Literal["fake", "sherpa"]
Embeddings = Literal["fake", "fastembed"]
# A política de dado do roteamento no OpenRouter (ADR-0023). `zdr` é a regra; `excecao-gratuita` é a EXCEÇÃO TEMPORÁRIA
# decidida pelo dono do produto em 05/10/2026 ("use os melhores modelos gratuitos mesmo não sendo compliance à nossa
# política... até conseguir comprar créditos"): desligada por padrão, ligada pelo operador por variável de ambiente.
PoliticaOpenRouter = Literal["zdr", "excecao-gratuita"]

log = logging.getLogger("oplenario_ia.config")


class Config(BaseModel):
    vendor: Vendor = "fake"
    modelo: str = "claude-opus-5"
    timeout_s: float = Field(default=60.0, gt=0)
    # OpenRouter (ADR-0023): o modelo é o slug do catálogo dele (`openai/gpt-oss-120b`); `openrouter_provedores`
    # restringe quem pode atender (`provider.only`) — vazio = qualquer provedor que cumpra ZDR e não colete dado.
    openrouter_url: str = "https://openrouter.ai/api/v1"
    openrouter_provedores: list[str] = Field(default_factory=list)
    # `zdr` (padrão): data_collection deny + zdr + require_parameters. `excecao-gratuita`: só require_parameters, e só
    # com modelos `:free` da lista própria — o provedor gratuito pode guardar e usar o dado (exceção à ADR-0023).
    openrouter_politica: PoliticaOpenRouter = "zdr"
    # failover NOSSO (ADR-0023 item 4, §22.3.5): modelos tentados em ordem quando o principal falha de forma
    # retentável ou some (404). Nunca o `models` do OpenRouter. Cada um passa pela mesma regra do principal.
    modelos_reserva: list[str] = Field(default_factory=list)
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
        # no OpenRouter o modelo leva o prefixo do fabricante; sem modelo explícito, o padrão vira o slug dele (na
        # exceção gratuita, o primeiro da lista dela)
        if "modelo" not in self.model_fields_set:
            self.modelo = (
                next(iter(MODELOS_OPENROUTER_GRATUITOS_EXCECAO))
                if self.openrouter_politica == "excecao-gratuita"
                else MODELO_OPENROUTER_PADRAO
            )
        if not modelo_openrouter_fixo(self.modelo):
            raise ValueError(
                "OPLENARIO_IA_MODELO: esperado `autor/modelo` fixo, em minúsculas — sem `openrouter/*`, alias `~` nem"
                " `:online` (ADR-0023)"
            )
        for reserva in self.modelos_reserva:
            if not modelo_openrouter_fixo(reserva):
                raise ValueError(
                    f"OPLENARIO_IA_MODELOS_RESERVA: `{reserva}` não é `autor/modelo` fixo, em minúsculas — sem"
                    " `openrouter/*`, alias `~` nem `:online` (ADR-0023)"
                )
        if len({self.modelo, *self.modelos_reserva}) != 1 + len(self.modelos_reserva):
            raise ValueError("OPLENARIO_IA_MODELOS_RESERVA: modelo repetido (ou igual ao principal)")
        if self.openrouter_politica == "excecao-gratuita":
            # a exceção só existe para modelo gratuito: vale também na avaliação, que aceita qualquer slug fixo
            pagos = [m for m in (self.modelo, *self.modelos_reserva) if not m.endswith(":free")]
            if pagos:
                raise ValueError(
                    "OPLENARIO_IA_OPENROUTER_POLITICA=excecao-gratuita só aceita modelo gratuito (`:free`): "
                    + ", ".join(pagos)
                )
        return self


MODELO_OPENROUTER_PADRAO = "openai/gpt-oss-120b"  # provisório (05/10/2026), ADR-0023

# Os modelos com que o satélite pode SUBIR pelo OpenRouter (ADR-0023), cada um com a sua FOLGA DE RACIOCÍNIO: num
# modelo que raciocina, o `max_tokens` cobre o raciocínio E a resposta, e os limites das capacidades (1000 a 2000)
# foram medidos num modelo que não raciocina — o qwen gratuito gastou os 2000 inteiros raciocinando e devolveu texto
# vazio (05/10/2026). A folga soma ao limite do pedido; paga-se só o que o modelo gera. Entra aqui o que passou pela
# avaliação (`oplenario-ia-avaliar`, R-IA-4) — a avaliação em si monta a `Config` direto e roda qualquer slug fixo.
MODELOS_OPENROUTER: dict[str, int] = {
    MODELO_OPENROUTER_PADRAO: 4000,
    # `qwen/qwen3.8-27b:free` saiu (05/10/2026, 17h UTC): o OpenRouter tirou a variante gratuita (404 "This model is
    # unavailable for free"), só resta a paga
}
MODELOS_OPENROUTER_PERMITIDOS: frozenset[str] = frozenset(MODELOS_OPENROUTER)


# EXCEÇÃO TEMPORÁRIA à ADR-0023 (decisão do dono do produto, 05/10/2026, até haver créditos no OpenRouter): os
# modelos com que o satélite pode SUBIR com `OPLENARIO_IA_OPENROUTER_POLITICA=excecao-gratuita`, com a mesma folga de
# raciocínio de `MODELOS_OPENROUTER`. Critério de entrada: (1) gratuito — slug terminado em `:free`, sem custo e sem
# crédito na conta; (2) entre os melhores gratuitos do catálogo na triagem, medido com `oplenario-ia-avaliar --vendor
# openrouter --politica excecao-gratuita --modelo <slug>` (R-IA-4) — a avaliação aprova, a lista só registra;
# (3) slug fixo (`modelo_openrouter_fixo`). Os provedores gratuitos desses modelos em geral NÃO cumprem ZDR nem "sem
# coleta": é por isso que a exceção existe, e por isso ela nunca vale com a política `zdr` (lá, só a lista acima).
# Da triagem pelo laço real da Clara (06/10/2026, 4 casos de `clara-papeis`; ADR-0023, "Exceção temporária"), na
# ordem de uso: o primeiro é o padrão sem `OPLENARIO_IA_MODELO`, os outros vão em `OPLENARIO_IA_MODELOS_RESERVA`.
# Uma rodada só por modelo, sob o limite diário da conta gratuita: a ordem entre o 2º e o 3º não está separada.
# O `qwen/qwen3.8-27b:free` saiu (o OpenRouter tirou a variante gratuita, 404); o `apodex/apodex-1.1-mini:free`
# reprovou os 4 casos (só saída inválida).
MODELOS_OPENROUTER_GRATUITOS_EXCECAO: dict[str, int] = {
    "nvidia/nemotron-3-super-120b-a12b:free": 4000,
    "nvidia/nemotron-3-ultra-550b-a55b:free": 4000,
    "inclusionai/ling-3.0-flash-sante:free": 4000,
}

AVISO_EXCECAO_GRATUITA = (
    "EXCEÇÃO TEMPORÁRIA À ADR-0023 LIGADA (OPLENARIO_IA_OPENROUTER_POLITICA=excecao-gratuita): os pedidos ao OpenRouter"
    " vão SEM zdr e SEM data_collection=deny, a modelos gratuitos (`:free`) — o provedor gratuito pode GUARDAR e USAR o"
    " dado enviado (inclusive para treino). Decisão do dono do produto (05/10/2026) até haver créditos; desligar"
    " voltando a política para `zdr`."
)
_excecao_avisada = False


def avisar_excecao_gratuita(saida: Callable[[str], None] | None = None) -> bool:
    """Emite o aviso da exceção UMA vez por processo (WARNING no log, ou `saida` quando dada, ex.: a CLI de avaliação).
    Devolve se emitiu agora."""
    global _excecao_avisada
    if _excecao_avisada:
        return False
    _excecao_avisada = True
    if saida is None:
        log.warning(AVISO_EXCECAO_GRATUITA)
    else:
        saida(AVISO_EXCECAO_GRATUITA)
    return True


def modelos_da_politica(politica: PoliticaOpenRouter) -> dict[str, int]:
    """Os modelos (slug → folga de raciocínio) com que o satélite pode subir na política dada."""
    return MODELOS_OPENROUTER_GRATUITOS_EXCECAO if politica == "excecao-gratuita" else MODELOS_OPENROUTER


def folga_de_raciocinio(modelo: str, politica: PoliticaOpenRouter = "zdr") -> int:
    """Os tokens a mais que o modelo ganha para raciocinar antes da resposta; 0 para modelo fora da lista."""
    return modelos_da_politica(politica).get(modelo, 0)


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
    if v := e.get("OPLENARIO_IA_OPENROUTER_POLITICA"):
        dados["openrouter_politica"] = v
    if v := e.get("OPLENARIO_IA_MODELOS_RESERVA"):
        dados["modelos_reserva"] = [m.strip() for m in v.split(",") if m.strip()]
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
    if config.vendor != "openrouter":
        return config
    excecao = config.openrouter_politica == "excecao-gratuita"
    lista = modelos_da_politica(config.openrouter_politica)
    permitidos = ", ".join(sorted(lista))
    nome_lista = "MODELOS_OPENROUTER_GRATUITOS_EXCECAO" if excecao else "lista de modelos permitidos do OpenRouter"
    if config.modelo not in lista:
        raise ValueError(f"OPLENARIO_IA_MODELO fora da {nome_lista} ({permitidos})")
    if fora := [m for m in config.modelos_reserva if m not in lista]:
        raise ValueError(f"OPLENARIO_IA_MODELOS_RESERVA fora da {nome_lista} ({permitidos}): {', '.join(fora)}")
    if excecao:
        avisar_excecao_gratuita()
    return config
