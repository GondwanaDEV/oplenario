"""A pasta observada: quais arquivos estão PRONTOS para enviar e quais já foram enviados.

Um arquivo está pronto quando tamanho e data de modificação ficam iguais por `estavel_s` segundos seguidos — é o
sinal de que o OBS terminou de escrever (o OBS não avisa). O registro do que já subiu fica num arquivo JSON na
própria pasta, para que reiniciar o utilitário (ou o PC) não reenvie nada.
"""

from __future__ import annotations

import json
import os
from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path

EXTENSOES = {".mkv", ".mp4", ".flv", ".mov", ".ts", ".m4a", ".mp3", ".wav", ".ogg", ".webm"}
ARQUIVO_ESTADO = ".oplenario-enviados.json"


@dataclass(frozen=True)
class Visto:
    tamanho: int
    modificado: float
    desde: float  # quando este (tamanho, modificado) foi visto pela primeira vez


def assinatura(caminho: Path) -> str:
    """Identifica um arquivo pelo nome + tamanho + data: o mesmo nome regravado é outro arquivo."""
    st = caminho.stat()
    return f"{caminho.name}|{st.st_size}|{int(st.st_mtime)}"


def candidatos(pasta: Path) -> list[Path]:
    return sorted(p for p in pasta.iterdir() if p.is_file() and p.suffix.lower() in EXTENSOES)


@dataclass
class Observador:
    estavel_s: float
    vistos: dict[str, Visto] = field(default_factory=dict)

    def prontos(self, arquivos: Iterable[tuple[str, int, float]], agora: float) -> list[str]:
        """Recebe (nome, tamanho, modificado) da varredura atual; devolve os nomes estáveis há `estavel_s`."""
        atuais: dict[str, Visto] = {}
        prontos: list[str] = []
        for nome, tamanho, modificado in arquivos:
            antes = self.vistos.get(nome)
            if antes and antes.tamanho == tamanho and antes.modificado == modificado:
                v = antes
            else:
                v = Visto(tamanho, modificado, agora)
            atuais[nome] = v
            if tamanho > 0 and agora - v.desde >= self.estavel_s:
                prontos.append(nome)
        self.vistos = atuais
        return prontos


class Registro:
    """O que já foi enviado (assinatura -> id do segmento no core) e o que falhou de vez (assinatura -> motivo)."""

    def __init__(self, caminho: Path) -> None:
        self.caminho = caminho
        self.enviados: dict[str, str] = {}
        self.recusados: dict[str, str] = {}
        if caminho.exists():
            dados = json.loads(caminho.read_text(encoding="utf-8"))
            self.enviados = dict(dados.get("enviados", {}))
            self.recusados = dict(dados.get("recusados", {}))

    def ja_tratado(self, assinatura: str) -> bool:
        return assinatura in self.enviados or assinatura in self.recusados

    def marcar_enviado(self, assinatura: str, segmento_id: str) -> None:
        self.enviados[assinatura] = segmento_id
        self._salvar()

    def marcar_recusado(self, assinatura: str, motivo: str) -> None:
        self.recusados[assinatura] = motivo
        self._salvar()

    def _salvar(self) -> None:
        tmp = self.caminho.with_suffix(".tmp")
        tmp.write_text(json.dumps({"enviados": self.enviados, "recusados": self.recusados}, indent=2), encoding="utf-8")
        os.replace(tmp, self.caminho)  # atômico: um corte de energia não deixa o registro pela metade
