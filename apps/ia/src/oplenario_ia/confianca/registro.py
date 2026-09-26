"""O registro auditável (§16.8, B4, §22.11.8): append-only e SEM conteúdo.

Três eventos: a execução (governança, fornecedor e modelo usados, tokens, custo, resultado, citações, incerteza), o
"reportar erro" e a revisão humana. Só contagens, identificadores e hashes — o texto nunca entra aqui. O armazenamento
é uma porta: memória (testes), arquivo JSONL append-only; o adaptador Postgres (schema do satélite, §22.3.4) entra com a
primeira capacidade em produção.
"""

from __future__ import annotations

import threading
from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal, Protocol

from pydantic import BaseModel, Field, TypeAdapter

from oplenario_ia.avaliacao.custo import Custo
from oplenario_ia.confianca.incerteza import Nivel
from oplenario_ia.confianca.indisponivel import MotivoIndisponivel
from oplenario_ia.erros import Categoria
from oplenario_ia.governanca.auditoria import Decisao
from oplenario_ia.inferencia.modelo import Parada, Uso

CategoriaReporte = Literal["fato_errado", "citacao_errada", "omissao", "linguagem", "outro"]
Desfecho = Literal["aprovado", "editado", "descartado"]


class RegistroExecucao(BaseModel):
    tipo: Literal["execucao"] = "execucao"
    execucao_id: str
    instante: datetime
    ente_id: str
    correlation_id: str
    operacao: str
    # governança (B4)
    decisao_governanca: Decisao
    motivos_bloqueio: list[str]
    redacoes: dict[str, int]
    terceiros: int
    hash_entrada: str
    # fornecedor — sempre o EFETIVAMENTE usado (§22.3.5)
    vendor: str
    modelo: str | None = None
    uso: Uso | None = None
    custo: Custo | None = None
    latencia_ms: int | None = None
    parada: Parada | None = None
    # resultado
    resultado: Literal["artefato", "indisponivel"]
    motivo_indisponivel: MotivoIndisponivel | None = None
    categoria_erro: Categoria | None = None
    n_citacoes: int = 0
    n_citacoes_conferidas: int = 0
    n_paragrafos_sem_fonte: int = 0
    incerteza: Nivel | None = None
    hash_saida: str | None = None


class ReporteErro(BaseModel):
    tipo: Literal["reporte_erro"] = "reporte_erro"
    execucao_id: str
    instante: datetime
    ente_id: str
    operacao: str
    quem: str
    categoria: CategoriaReporte


class RevisaoHumana(BaseModel):
    tipo: Literal["revisao"] = "revisao"
    execucao_id: str
    instante: datetime
    ente_id: str
    operacao: str
    revisor: str
    desfecho: Desfecho
    proporcao_alterada: float = Field(ge=0, le=1)  # quanto do rascunho a pessoa mudou — número, não texto


Evento = Annotated[RegistroExecucao | ReporteErro | RevisaoHumana, Field(discriminator="tipo")]
_EVENTO: TypeAdapter[Evento] = TypeAdapter(Evento)


class RegistroConfianca(Protocol):
    """Append-only: só anexa e lê. Não há atualizar nem apagar — correção é evento novo."""

    def anexar(self, evento: Evento) -> None: ...

    def eventos(self) -> list[Evento]: ...


class RegistroMemoria:
    def __init__(self) -> None:
        self._eventos: list[Evento] = []

    def anexar(self, evento: Evento) -> None:
        self._eventos.append(evento.model_copy(deep=True))

    def eventos(self) -> list[Evento]:
        return [e.model_copy(deep=True) for e in self._eventos]


class RegistroJsonl:
    """Uma linha JSON por evento, arquivo aberto só em modo de acréscimo."""

    def __init__(self, caminho: str | Path) -> None:
        self._caminho = Path(caminho)
        self._trava = threading.Lock()

    def anexar(self, evento: Evento) -> None:
        linha = evento.model_dump_json() + "\n"
        with self._trava, self._caminho.open("a", encoding="utf-8") as f:
            f.write(linha)

    def eventos(self) -> list[Evento]:
        if not self._caminho.exists():
            return []
        with self._caminho.open(encoding="utf-8") as f:
            return [_EVENTO.validate_json(linha) for linha in f if linha.strip()]
