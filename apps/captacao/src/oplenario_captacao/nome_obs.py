"""De quando é a gravação: pelo nome que o OBS dá ao arquivo, ou pelo relógio do sistema de arquivos.

O OBS nomeia por padrão "2026-09-22 18-00-12.mkv" (formato "%CCYY-%MM-%DD %hh-%mm-%ss"), no horário LOCAL do PC.
O fuso vai como deslocamento fixo (padrão -03:00, Fortaleza): o `zoneinfo` do Windows não traz a base de fusos, e o
Nordeste não tem horário de verão.
"""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone

_NOME_OBS = re.compile(r"(\d{4})-(\d{2})-(\d{2})[ _T](\d{2})-(\d{2})-(\d{2})")
_FUSO = re.compile(r"^([+-])(\d{2}):?(\d{2})$")


def fuso(texto: str) -> timezone:
    m = _FUSO.match(texto.strip())
    if not m:
        raise ValueError(f"fuso inválido: {texto!r} (use o formato -03:00)")
    sinal = -1 if m.group(1) == "-" else 1
    return timezone(sinal * timedelta(hours=int(m.group(2)), minutes=int(m.group(3))))


def inicio_pelo_nome(nome: str, tz: timezone) -> datetime | None:
    """O instante em que o OBS começou a gravar, lido do nome do arquivo — ou None se o nome não segue o padrão."""
    m = _NOME_OBS.search(nome)
    if not m:
        return None
    ano, mes, dia, hora, minuto, segundo = (int(g) for g in m.groups())
    try:
        return datetime(ano, mes, dia, hora, minuto, segundo, tzinfo=tz)
    except ValueError:
        return None


def iso(instante: datetime) -> str:
    """ISO-8601 em UTC com 'Z' — o formato que o core aceita."""
    return instante.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
