"""`oplenario-ia-trabalhador` — o processo que puxa o feed do core e transcreve as gravações (ADR-0008).

    OPLENARIO_CORE_URL=https://core.interno OPLENARIO_IA_SEGREDO=... OPLENARIO_IA_DATABASE_URL=postgresql://...
    OPLENARIO_IA_ASR=sherpa OPLENARIO_IA_MODELOS=/modelos oplenario-ia-trabalhador

`--uma-vez` faz uma volta só (útil para teste e para cron).
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from oplenario_ia.armazem.memoria import ArmazemMemoria
from oplenario_ia.armazem.porta import Armazem
from oplenario_ia.confianca.registro import RegistroConfianca, RegistroJsonl, RegistroMemoria
from oplenario_ia.config import Config, carregar
from oplenario_ia.fronteira.cliente import ClienteCore
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.nucleo import Nucleo
from oplenario_ia.trabalhador import Trabalhador
from oplenario_ia.transcricao.fake import DiarizadorFake, TranscritorFake
from oplenario_ia.transcricao.porta import Diarizador, Transcritor


def montar(config: Config) -> Trabalhador:
    if not config.core_url or not config.segredo:
        raise SystemExit("Faltam OPLENARIO_CORE_URL e OPLENARIO_IA_SEGREDO.")
    armazem: Armazem
    if config.database_url:
        from oplenario_ia.armazem.postgres import ArmazemPostgres

        armazem = ArmazemPostgres(config.database_url)
    else:
        logging.warning("sem OPLENARIO_IA_DATABASE_URL: fila e transcrições em MEMÓRIA (só para desenvolvimento)")
        armazem = ArmazemMemoria()
    transcritor: Transcritor
    diarizador: Diarizador | None
    if config.asr == "sherpa":
        from oplenario_ia.transcricao.sherpa import DiarizadorSherpa, TranscritorSherpa

        transcritor = TranscritorSherpa(Path(config.modelos_dir), config.whisper)
        diarizador = DiarizadorSherpa(Path(config.modelos_dir))
    else:
        transcritor, diarizador = TranscritorFake(), DiarizadorFake()
    registro: RegistroConfianca = RegistroJsonl(config.registro_jsonl) if config.registro_jsonl else RegistroMemoria()
    return Trabalhador(
        ClienteCore(config.core_url, config.segredo),
        armazem,
        transcritor,
        diarizador,
        idioma=config.idioma,
        nucleo=Nucleo(criar_porta(config), registro),
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="oplenario-ia-trabalhador", description=__doc__.split("\n")[0] if __doc__ else "")
    ap.add_argument("--uma-vez", action="store_true", help="uma volta só (puxa o feed e esvazia a fila)")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config = carregar()
    t = montar(config)
    if args.uma_vez:
        n = t.ciclo()
        logging.info("volta concluída: %d trabalho(s) processado(s)", n)
        return 0
    t.rodar(config.intervalo_s)
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
