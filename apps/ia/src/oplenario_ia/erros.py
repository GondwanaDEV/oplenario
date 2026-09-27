"""O modelo de erro da fronteira de IA — as seis categorias do §22.3.5.

Falha de IA tem gradiente, e cada categoria pede um tratamento diferente. As quatro primeiras são FALHAS (viram
`ErroIA`); as duas últimas NÃO são erro técnico: a saída plausível-mas-errada (5) é defendida pela revisão humana da
Camada de Confiança, e a baixa confiança (6) viaja como metadado (`confianca.incerteza`). Estão no enum para que o
vocabulário seja um só, do registro ao painel.
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel


class Categoria(StrEnum):
    INFRAESTRUTURA = "infraestrutura"  # 1. IA fora, rede, fila, credencial/config do fornecedor
    SOBRECARGA = "sobrecarga"  # 2. saturação, rate limit do fornecedor
    ENTRADA = "entrada"  # 3. input inválido — sem retry
    MODELO = "modelo"  # 4. saída estruturalmente inválida — retry curto
    SAIDA_PLAUSIVEL_ERRADA = "saida_plausivel_errada"  # 5. alucinação — não é técnico: revisão humana
    BAIXA_CONFIANCA = "baixa_confianca"  # 6. sinal propagado como metadado


class ErroIA(Exception):
    """Falha categorizada da fronteira de IA (categorias 1 a 4).

    `retentavel` diz se tentar de novo pode dar certo — é o insumo do backoff e do failover (§22.3.5, Eixo 13), que
    são política do chamador, nunca do SDK do fornecedor. `detalhe` é texto para log/operador; nunca carrega o
    conteúdo enviado ao modelo (B4).
    """

    def __init__(self, categoria: Categoria, detalhe: str, *, retentavel: bool, vendor: str | None = None) -> None:
        if categoria in (Categoria.SAIDA_PLAUSIVEL_ERRADA, Categoria.BAIXA_CONFIANCA):
            raise ValueError(f"{categoria} não é falha técnica — vai como metadado, não como exceção")
        super().__init__(detalhe)
        self.categoria = categoria
        self.detalhe = detalhe
        self.retentavel = retentavel
        self.vendor = vendor


class ErroEstruturado(BaseModel):
    """O corpo de erro de toda borda síncrona do satélite (§22.3.5: schema estruturado, versionado)."""

    versao: int = 1
    categoria: Categoria
    detalhe: str
    retentavel: bool


STATUS_HTTP: dict[Categoria, int] = {
    Categoria.INFRAESTRUTURA: 503,
    Categoria.SOBRECARGA: 429,
    Categoria.ENTRADA: 422,
    Categoria.MODELO: 502,
}


def para_estruturado(erro: ErroIA) -> tuple[int, ErroEstruturado]:
    return STATUS_HTTP[erro.categoria], ErroEstruturado(
        categoria=erro.categoria, detalhe=erro.detalhe, retentavel=erro.retentavel
    )
