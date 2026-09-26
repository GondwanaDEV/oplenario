"""Avaliação completa de um trecho de sessão contra a anotação humana. Gera dados/<nome>.relatorio.md.

Entradas (ver anotacao/COMO-ANOTAR.md):
  --referencia  CSV inicio,fim,falante  — quem REALMENTE falou (anotado por quem conhece os vereadores)
  --palavra     CSV inicio,fim,falante  — quem ESTAVA COM A PALAVRA na tribuna (o que a Mesa registraria)
  --diarizacao  CSV (opcional)          — reaproveita uma diarização já feita; senão roda agora
  --transcricao CSV (opcional)          — se vier, o relatório inclui o rascunho "quem disse o quê"

Perguntas que o relatório responde (docs/26, A.1):
  1. Diarização pura: qual o DER? (rótulos casados pela melhor correspondência)
  2. Caminho C sozinho: quanto do tempo de fala a palavra da tribuna acerta?
  3. Diarização + Caminho C (os grupos de voz ganham nome pela palavra): qual o DER com nomes?
  4. Quanto tempo de máquina custa (fator de tempo real)?"""
from __future__ import annotations

import argparse
import csv
import time
from pathlib import Path

from poc.audio import TAXA, carregar
from poc.caminho_c import aplicar_nomes, cobertura_da_palavra, nomear_clusters
from poc.juntar import atribuir_falantes
from poc.linha_do_tempo import ler_csv
from poc.metricas import der, tempo_de_fala


def _pct(x: float) -> str:
    return f"{100 * x:.1f}%"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("audio")
    ap.add_argument("--referencia", required=True)
    ap.add_argument("--palavra", required=True)
    ap.add_argument("--diarizacao")
    ap.add_argument("--transcricao")
    ap.add_argument("--inicio", type=float)
    ap.add_argument("--duracao", type=float)
    ap.add_argument("--falantes", type=int, default=-1)
    ap.add_argument("--limiar", type=float, default=0.5)
    ap.add_argument("--colar", type=float, default=0.25, help="tolerância nas fronteiras da anotação (s)")
    a = ap.parse_args()

    ref = ler_csv(a.referencia)
    palavra = ler_csv(a.palavra)
    fator = None
    if a.diarizacao:
        hip = ler_csv(a.diarizacao)
    else:
        from poc.diarizar import diarizar
        amostras = carregar(a.audio, a.inicio, a.duracao)
        t0 = time.perf_counter()
        hip = diarizar(amostras, falantes=a.falantes, limiar=a.limiar)
        fator = (time.perf_counter() - t0) / (len(amostras) / TAXA)

    d_pura = der(ref, hip, colar=a.colar)
    cob = cobertura_da_palavra(ref, palavra)
    nomes = nomear_clusters(hip, palavra)
    nomeada = aplicar_nomes(hip, nomes)
    d_nomes = der(ref, nomeada, mapear=False, colar=a.colar)

    linhas = [
        f"# Relatório da PoC de áudio — {Path(a.audio).name}",
        "",
        f"Fala anotada na referência: {d_pura['fala_ref_s'] / 60:.1f} min, "
        f"{len(tempo_de_fala(ref))} pessoas. Colar de {a.colar} s nas fronteiras.",
        "",
        "| Pergunta | Resultado |",
        "| --- | --- |",
        f"| 1. DER da diarização pura | **{_pct(d_pura['der'])}** (perdida {_pct(d_pura['perdida'])}, "
        f"falso alarme {_pct(d_pura['falso_alarme'])}, confusão {_pct(d_pura['confusao'])}) |",
        f"| 2. Caminho C sozinho acerta | **{_pct(cob['acerto'])}** do tempo de fala "
        f"(fora da tribuna {_pct(cob['fora_da_tribuna'])}, outro falando {_pct(cob['outro_falando'])}) |",
        f"| 3. DER com nomes (diarização + Caminho C) | **{_pct(d_nomes['der'])}** "
        f"(confusão {_pct(d_nomes['confusao'])}) |",
        f"| 4. Fator de tempo real da diarização | {'%.2f' % fator if fator is not None else 'não medido aqui'} |",
        "",
        "## Grupos de voz → nomes (pela palavra da tribuna)",
        "",
        *[f"- {g}: {n or 'sem nome (vai para a revisão)'}" for g, n in sorted(nomes.items())],
    ]
    if a.transcricao:
        with open(a.transcricao, newline="", encoding="utf-8") as f:
            frases = [(float(l["inicio"]), float(l["fim"]), l["texto"]) for l in csv.DictReader(f)]
        linhas += ["", "## Rascunho: quem disse o quê", ""]
        for ini, _fim, nome, texto in atribuir_falantes(frases, nomeada):
            linhas.append(f"- `{int(ini // 60):02d}:{ini % 60:04.1f}` **{nome or '?'}**: {texto}")
    saida = Path(f"dados/{Path(a.audio).stem}.relatorio.md")
    saida.write_text("\n".join(linhas) + "\n", encoding="utf-8")
    print("\n".join(linhas[:12]))
    print(f"\n-> {saida}")


if __name__ == "__main__":
    main()
