"""Caminho C (§22.6 eixo D): quem falou, inferido de quem TINHA A PALAVRA — as falas que a Mesa registra na
plataforma (orador, início, fim) —, sem reconhecer voz. Porta de produção de `prototipos/poc-audio/poc/caminho_c.py`
(sem numpy: aritmética de intervalos).

1. `nomear_grupos`: cada grupo de voz da diarização recebe o nome de quem tinha a palavra durante a maior parte do
   tempo em que o grupo falou — se essa parte for ao menos `maioria_minima`. Senão fica sem nome. O presidente que
   conduz entre um orador e outro, por exemplo, não tem maioria sobre ninguém e sai sem nome, como deve.
2. `atribuir`: cada frase vai para o grupo que mais falou durante ela, e o grupo empresta o nome. Sem diarização
   (porta ausente ou áudio curto demais), a frase vai direto para quem tinha a palavra na maior parte dela.
3. `cobertura`: a fração do tempo de fala com orador atribuído — a métrica que o core recebe e a tela mostra.

Palavras SOBREPOSTAS (o aparte concedido dentro da fala de outro; uma fala que ficou aberta e outra que começou
depois) são achatadas antes: em cada instante vale a palavra concedida POR ÚLTIMO (`achatar`).
"""

from __future__ import annotations

from collections import defaultdict
from itertools import pairwise

from oplenario_ia.transcricao.modelo import Frase, Palavra, Trecho, Voz, sobreposicao


def achatar(palavras: list[Palavra]) -> list[Palavra]:
    """Linha do tempo sem sobreposição: em cada intervalo elementar vale a palavra de início mais recente (o aparte
    vence a fala-mãe; a fala nova vence a que ficou aberta). Trechos contíguos do mesmo orador se fundem."""
    marcos = sorted({t for p in palavras for t in (p.inicio, p.fim)})
    saida: list[Palavra] = []
    for a, b in pairwise(marcos):
        cobrem = [p for p in palavras if p.inicio <= a and p.fim >= b]
        if not cobrem:
            continue
        v = max(cobrem, key=lambda p: (p.inicio, -(p.fim - p.inicio), p.orador_id))
        if saida and saida[-1].orador_id == v.orador_id and saida[-1].fim == a:
            saida[-1] = Palavra(saida[-1].inicio, b, v.orador_id, v.orador_nome)
        else:
            saida.append(Palavra(a, b, v.orador_id, v.orador_nome))
    return saida


def nomear_grupos(vozes: list[Voz], palavras: list[Palavra], maioria_minima: float = 0.5) -> dict[str, Palavra | None]:
    palavras = achatar(palavras)
    duracao: dict[str, float] = defaultdict(float)
    com_orador: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))
    quem: dict[str, Palavra] = {}
    for v in vozes:
        duracao[v.grupo] += v.fim - v.inicio
        for p in palavras:
            s = sobreposicao(v.inicio, v.fim, p.inicio, p.fim)
            if s > 0:
                com_orador[v.grupo][p.orador_id] += s
                quem[p.orador_id] = p
    nomes: dict[str, Palavra | None] = {}
    for grupo, total in duracao.items():
        melhor = max(com_orador[grupo].items(), key=lambda kv: (kv[1], kv[0]), default=None)
        nomes[grupo] = quem[melhor[0]] if melhor and total > 0 and melhor[1] / total >= maioria_minima else None
    return nomes


def _mais_sobreposto(ini: float, fim: float, candidatos: list[tuple[float, float, str]]) -> str | None:
    tempo: dict[str, float] = defaultdict(float)
    for c_ini, c_fim, rotulo in candidatos:
        s = sobreposicao(ini, fim, c_ini, c_fim)
        if s > 0:
            tempo[rotulo] += s
    return max(tempo.items(), key=lambda kv: (kv[1], kv[0]))[0] if tempo else None


def atribuir(frases: list[Frase], vozes: list[Voz], palavras: list[Palavra]) -> list[Trecho]:
    palavras = achatar(palavras)
    por_id = {p.orador_id: p for p in palavras}
    if vozes:
        nomes = nomear_grupos(vozes, palavras)
        grupos = [(v.inicio, v.fim, v.grupo) for v in vozes]
        saida = []
        for f in frases:
            g = _mais_sobreposto(f.inicio, f.fim, grupos)
            p = nomes.get(g) if g else None
            saida.append(
                Trecho(
                    f.inicio, f.fim, f.texto, g, p.orador_id if p else None, p.orador_nome if p else None, f.confianca
                )
            )
        return saida
    ancoras = [(p.inicio, p.fim, p.orador_id) for p in palavras]
    saida = []
    for f in frases:
        oid = _mais_sobreposto(f.inicio, f.fim, ancoras)
        p = por_id.get(oid) if oid else None
        saida.append(
            Trecho(
                f.inicio, f.fim, f.texto, None, p.orador_id if p else None, p.orador_nome if p else None, f.confianca
            )
        )
    return saida


def cobertura(trechos: list[Trecho]) -> float:
    total = sum(t.fim - t.inicio for t in trechos)
    if total <= 0:
        return 0.0
    return round(sum(t.fim - t.inicio for t in trechos if t.orador_id) / total, 4)
