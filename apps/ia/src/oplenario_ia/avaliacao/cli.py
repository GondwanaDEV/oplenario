"""`oplenario-ia-avaliar` — roda os conjuntos de avaliação. Sai com 1 se algum caso reprovar (o CI bloqueia o merge).

    oplenario-ia-avaliar avaliacoes                         # fornecedor fake, custo zero (CI)
    oplenario-ia-avaliar avaliacoes --vendor anthropic      # fornecedor real: GASTA DINHEIRO; gate antes de trocar
                                                            # fornecedor ou modelo (§22.11.8)

O fornecedor vem SÓ da linha de comando (nunca do ambiente): o CI não passa a gastar por uma variável esquecida.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from pydantic import ValidationError

from oplenario_ia.avaliacao.conjunto import Conjunto
from oplenario_ia.avaliacao.harness import Relatorio, avaliar
from oplenario_ia.config import Config
from oplenario_ia.inferencia.fabrica import criar_porta
from oplenario_ia.inferencia.porta import PortaInferencia


def _arquivos(caminhos: list[str]) -> list[Path]:
    arquivos: list[Path] = []
    for c in map(Path, caminhos):
        arquivos += sorted(c.glob("*.json")) if c.is_dir() else [c]
    return arquivos


def _imprimir(r: Relatorio) -> None:
    ok = sum(c.passou and not c.pulado for c in r.casos)
    pulados = sum(c.pulado for c in r.casos)
    rodados = len(r.casos) - pulados
    extra = f", {pulados} só-fake pulado(s)" if pulados else ""
    print(f"[{r.conjunto} v{r.versao}] {r.vendor}: {ok}/{rodados} aprovado(s){extra} — custo {r.custo_total} USD")
    for c in r.casos:
        if not c.passou:
            print(f"  REPROVADO {c.id} ({c.tipo})")
            for f in c.falhas:
                print(f"    - {f}")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="oplenario-ia-avaliar", description=__doc__.split("\n")[0] if __doc__ else "")
    ap.add_argument("caminhos", nargs="+", help="arquivos .json de conjunto ou diretórios com eles")
    ap.add_argument("--vendor", choices=["fake", "anthropic"], default="fake")
    ap.add_argument("--modelo", default=Config().modelo)
    ap.add_argument("--saida", type=Path, help="grava o relatório JSON (ex.: avaliacoes/resultados/…)")
    args = ap.parse_args(argv)

    faltando = [c for c in args.caminhos if not Path(c).exists()]
    if faltando:
        print(f"não encontrado: {', '.join(faltando)}", file=sys.stderr)
        return 2
    arquivos = _arquivos(args.caminhos)
    if not arquivos:
        print("nenhum conjunto de avaliação encontrado", file=sys.stderr)
        return 2
    porta: PortaInferencia | None = None
    if args.vendor != "fake":
        print(f"ATENÇÃO: avaliando contra {args.vendor}/{args.modelo} — isto gasta dinheiro.", file=sys.stderr)
        porta = criar_porta(Config(vendor=args.vendor, modelo=args.modelo))

    relatorios: list[Relatorio] = []
    for arquivo in arquivos:
        try:
            conjunto = Conjunto.model_validate_json(arquivo.read_text(encoding="utf-8"))
        except ValidationError as e:
            print(f"{arquivo}: conjunto inválido\n{e}", file=sys.stderr)
            return 2
        relatorios.append(avaliar(conjunto, porta))
        _imprimir(relatorios[-1])

    if args.saida is not None:
        args.saida.parent.mkdir(parents=True, exist_ok=True)
        corpo = [json.loads(r.model_dump_json()) | {"aprovado": r.aprovado} for r in relatorios]
        args.saida.write_text(json.dumps(corpo, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if all(r.aprovado for r in relatorios) else 1


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
