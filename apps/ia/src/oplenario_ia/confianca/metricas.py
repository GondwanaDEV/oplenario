"""Qualidade em produção medida pelo que a pessoa faz (§22.11.8): aceito / editado / descartado e erros reportados,
por Casa e por operação. Só contagens — o insumo do painel da Casa (B.9)."""

from __future__ import annotations

from pydantic import BaseModel

from oplenario_ia.confianca.registro import Evento, ReporteErro, RevisaoHumana


class MetricaOperacao(BaseModel):
    aprovados: int = 0
    editados: int = 0
    descartados: int = 0
    erros_reportados: int = 0

    @property
    def revisados(self) -> int:
        return self.aprovados + self.editados + self.descartados

    @property
    def taxa_aceitacao(self) -> float | None:
        return None if self.revisados == 0 else (self.aprovados + self.editados) / self.revisados


def por_ente_e_operacao(eventos: list[Evento]) -> dict[tuple[str, str], MetricaOperacao]:
    m: dict[tuple[str, str], MetricaOperacao] = {}
    for e in eventos:
        if not isinstance(e, RevisaoHumana | ReporteErro):
            continue
        alvo = m.setdefault((e.ente_id, e.operacao), MetricaOperacao())
        if isinstance(e, ReporteErro):
            alvo.erros_reportados += 1
        elif e.desfecho == "aprovado":
            alvo.aprovados += 1
        elif e.desfecho == "editado":
            alvo.editados += 1
        else:
            alvo.descartados += 1
    return m
