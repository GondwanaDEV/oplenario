"""O formato de um conjunto de avaliação (§22.11.8) — JSON versionado em `apps/ia/avaliacoes/`.

Três tipos de caso: `real` (casos reais conferidos e anonimizados — entram com as capacidades, `[GAP]` LGPD),
`seguranca` (sigilo, instrução escondida, ato sem confirmação) e `objetiva` (a citação existe e diz o que foi
afirmado; o piso R-IA-1 acontece). `resposta_fake`/`parada_fake`/`erro_fake` roteirizam o fornecedor fake; caso
`apenas_fake` testa o PIPELINE (não o modelo) e é pulado contra fornecedor real. `extra="forbid"`: chave errada no
JSON reprova o conjunto, não passa em silêncio.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from oplenario_ia.confianca.citacao import PoliticaCitacao, StatusCitacao
from oplenario_ia.confianca.incerteza import MotivoIncerteza, Nivel
from oplenario_ia.confianca.indisponivel import MotivoIndisponivel
from oplenario_ia.erros import Categoria
from oplenario_ia.governanca.proveniencia import Peca
from oplenario_ia.inferencia.modelo import Parada

TipoCaso = Literal["real", "seguranca", "objetiva"]


class _Estrito(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ErroFake(_Estrito):
    categoria: Categoria
    retentavel: bool


class Esperado(_Estrito):
    resultado: Literal["artefato", "indisponivel"]
    motivo: MotivoIndisponivel | None = None
    nunca_enviado: list[str] = []
    enviado_contem: list[str] = []
    texto_contem: list[str] = []
    texto_nao_contem: list[str] = []
    incerteza: Nivel | None = None
    motivos_incerteza_inclui: list[MotivoIncerteza] = []
    status_citacoes: list[StatusCitacao] | None = None
    citacoes_conferidas_min: int | None = None
    paragrafos_sem_fonte_max: int | None = None
    contaminado: bool | None = None

    @model_validator(mode="after")
    def _coerente(self) -> Esperado:
        if self.resultado == "indisponivel" and self.motivo is None:
            raise ValueError("resultado 'indisponivel' exige o motivo esperado")
        return self


class Caso(_Estrito):
    id: str = Field(pattern=r"^[a-z0-9-]+$")
    tipo: TipoCaso
    descricao: str
    operacao: str = "avaliacao"
    instrucoes: str = "Responda com base apenas no conteúdo recebido."
    politica_citacao: PoliticaCitacao = "nenhuma"
    pecas: list[Peca]
    apenas_fake: bool = False
    resposta_fake: str | None = None
    parada_fake: Parada = "fim"
    erro_fake: ErroFake | None = None
    esperado: Esperado


class Conjunto(_Estrito):
    conjunto: str
    versao: int
    descricao: str
    casos: list[Caso] = Field(min_length=1)

    @model_validator(mode="after")
    def _ids_unicos(self) -> Conjunto:
        ids = [c.id for c in self.casos]
        if len(ids) != len(set(ids)):
            raise ValueError("ids de caso repetidos no conjunto")
        return self
