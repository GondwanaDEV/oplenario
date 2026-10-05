"""O registro auditável (§16.8, B4, §22.11.8): append-only e SEM conteúdo.

Três eventos: a execução (governança, fornecedor e modelo usados, tokens, custo, resultado, citações, incerteza), o
"reportar erro" e a revisão humana. Só contagens, identificadores e hashes — o texto nunca entra aqui. O armazenamento
é uma porta: memória (testes), arquivo JSONL append-only e Postgres (`registro_postgres`, B.9 — o que a API e o
trabalhador compartilham, e de onde a cota e o painel da Casa leem o consumo do mês).
"""

from __future__ import annotations

import threading
from datetime import datetime
from decimal import Decimal
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
    provedor: str | None = None  # quem atendeu atrás do gateway (OpenRouter → Anthropic, Bedrock…), ADR-0023
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


class ApagaPorEnte(Protocol):
    """A ÚNICA exceção ao append-only: a Casa encerrada (ADR-0018, Eixo 4.5) — somos operador (LGPD), e o que é dela
    sai inteiro. Devolve quantos eventos saíram."""

    def apagar_ente(self, ente_id: str) -> int: ...


class ConsultaExecucao(Protocol):
    """Os eventos de UMA execução, só na Casa dela (feature 8.4: o "reportar erro" confere que a execução existe, é da
    Casa e produziu um artefato, e não conta duas vezes o reporte da mesma pessoa)."""

    def eventos_da_execucao(self, ente_id: str, execucao_id: str) -> list[Evento]: ...


class ConsultaConsumo(Protocol):
    """O que a cota e o painel da Casa leem do registro (B.9)."""

    def gasto_desde(self, ente_id: str, desde: datetime) -> Decimal: ...

    def eventos_entre(self, ente_id: str, desde: datetime, ate: datetime) -> list[Evento]: ...

    def eventos_de_todas_entre(self, desde: datetime, ate: datetime) -> list[Evento]:
        """Todas as Casas (a observabilidade do operador, sem conteúdo)."""
        ...


def gasto(eventos: list[Evento]) -> Decimal:
    """A soma do custo CONHECIDO das execuções (modelo sem preço não soma — o painel diz que é parcial)."""
    return sum(
        (e.custo.valor for e in eventos if isinstance(e, RegistroExecucao) and e.custo and e.custo.valor is not None),
        Decimal(0),
    )


class RegistroMemoria:
    def __init__(self) -> None:
        self._eventos: list[Evento] = []

    def anexar(self, evento: Evento) -> None:
        self._eventos.append(evento.model_copy(deep=True))

    def eventos(self) -> list[Evento]:
        return [e.model_copy(deep=True) for e in self._eventos]

    def eventos_entre(self, ente_id: str, desde: datetime, ate: datetime) -> list[Evento]:
        return [e.model_copy(deep=True) for e in self._eventos if e.ente_id == ente_id and desde <= e.instante < ate]

    def gasto_desde(self, ente_id: str, desde: datetime) -> Decimal:
        return gasto([e for e in self._eventos if e.ente_id == ente_id and e.instante >= desde])

    def eventos_de_todas_entre(self, desde: datetime, ate: datetime) -> list[Evento]:
        return [e.model_copy(deep=True) for e in self._eventos if desde <= e.instante < ate]

    def eventos_da_execucao(self, ente_id: str, execucao_id: str) -> list[Evento]:
        return [e.model_copy(deep=True) for e in self._eventos if e.ente_id == ente_id and e.execucao_id == execucao_id]

    def apagar_ente(self, ente_id: str) -> int:
        antes = len(self._eventos)
        self._eventos = [e for e in self._eventos if e.ente_id != ente_id]
        return antes - len(self._eventos)


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

    def eventos_entre(self, ente_id: str, desde: datetime, ate: datetime) -> list[Evento]:
        return [e for e in self.eventos() if e.ente_id == ente_id and desde <= e.instante < ate]

    def gasto_desde(self, ente_id: str, desde: datetime) -> Decimal:
        return gasto([e for e in self.eventos() if e.ente_id == ente_id and e.instante >= desde])

    def eventos_de_todas_entre(self, desde: datetime, ate: datetime) -> list[Evento]:
        return [e for e in self.eventos() if desde <= e.instante < ate]

    def eventos_da_execucao(self, ente_id: str, execucao_id: str) -> list[Evento]:
        return [e for e in self.eventos() if e.ente_id == ente_id and e.execucao_id == execucao_id]

    def apagar_ente(self, ente_id: str) -> int:
        """Reescreve o arquivo sem as linhas da Casa (temporário + rename: nunca um arquivo pela metade)."""
        with self._trava:
            if not self._caminho.exists():
                return 0
            with self._caminho.open(encoding="utf-8") as f:
                linhas = [linha for linha in f if linha.strip()]
            fica = [linha for linha in linhas if _EVENTO.validate_json(linha).ente_id != ente_id]
            tmp = self._caminho.with_name(self._caminho.name + ".tmp")
            tmp.write_text("".join(fica), encoding="utf-8")
            tmp.replace(self._caminho)
            return len(linhas) - len(fica)
