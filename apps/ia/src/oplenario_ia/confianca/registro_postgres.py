"""O registro da Camada de Confiança em Postgres (schema `ia`, B.9): o MESMO registro para a API e o trabalhador — a
cota da Casa precisa ver o gasto de todos os processos. Append-only (a tabela só recebe INSERT; correção é evento novo)
e SEM conteúdo, como os demais (B4): o corpo é o evento inteiro, que já não carrega texto; o custo e o instante vão em
colunas para a soma do mês ser uma consulta indexada."""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Any

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from pydantic import TypeAdapter

from oplenario_ia.armazem.postgres import migrar
from oplenario_ia.confianca.registro import Evento, RegistroExecucao

_EVENTO: TypeAdapter[Evento] = TypeAdapter(Evento)


class RegistroPostgres:
    def __init__(self, url: str) -> None:
        self._url = url
        with self._conectar() as conn:
            migrar(conn)

    def _conectar(self) -> psycopg.Connection[dict[str, Any]]:
        return psycopg.connect(self._url, row_factory=dict_row, autocommit=True)

    def anexar(self, evento: Evento) -> None:
        custo = evento.custo if isinstance(evento, RegistroExecucao) else None
        with self._conectar() as c:
            c.execute(
                "INSERT INTO ia.registro_evento (tipo, ente_id, operacao, instante, custo, custo_desconhecido, corpo)"
                " VALUES (%s, %s, %s, %s, %s, %s, %s)",
                (
                    evento.tipo,
                    evento.ente_id,
                    evento.operacao,
                    evento.instante,
                    custo.valor if custo else None,
                    bool(custo and custo.valor is None),
                    Jsonb(evento.model_dump(mode="json")),
                ),
            )

    def _ler(self, sql: str, params: tuple[Any, ...]) -> list[Evento]:
        with self._conectar() as c:
            return [_EVENTO.validate_python(r["corpo"]) for r in c.execute(sql, params).fetchall()]

    def eventos(self) -> list[Evento]:
        return self._ler("SELECT corpo FROM ia.registro_evento ORDER BY id", ())

    def eventos_entre(self, ente_id: str, desde: datetime, ate: datetime) -> list[Evento]:
        return self._ler(
            "SELECT corpo FROM ia.registro_evento WHERE ente_id = %s AND instante >= %s AND instante < %s ORDER BY id",
            (ente_id, desde, ate),
        )

    def eventos_de_todas_entre(self, desde: datetime, ate: datetime) -> list[Evento]:
        # só execuções: é o que a observabilidade agrega (revisão e reporte ficam no painel de cada Casa)
        return self._ler(
            "SELECT corpo FROM ia.registro_evento WHERE tipo = 'execucao' AND instante >= %s AND instante < %s"
            " ORDER BY id",
            (desde, ate),
        )

    def gasto_desde(self, ente_id: str, desde: datetime) -> Decimal:
        with self._conectar() as c:
            r = c.execute(
                "SELECT coalesce(sum(custo), 0) AS gasto FROM ia.registro_evento"
                " WHERE ente_id = %s AND tipo = 'execucao' AND instante >= %s",
                (ente_id, desde),
            ).fetchone()
            return Decimal(r["gasto"]) if r else Decimal(0)

    def apagar_ente(self, ente_id: str) -> int:
        """ADR-0018: a Casa encerrada sai do registro (com o armazém Postgres, ele já apagou estas linhas — zero)."""
        with self._conectar() as c:
            return c.execute("DELETE FROM ia.registro_evento WHERE ente_id = %s", (ente_id,)).rowcount
