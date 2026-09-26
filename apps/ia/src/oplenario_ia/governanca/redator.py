"""Redação de MINIMIZAÇÃO (B2, 2ª camada) sobre conteúdo JÁ liberado como público.

Remove identificadores privados de alta precisão (CPF, CNPJ, e-mail) e PRESERVA nome público (vereador, autoridade —
público no contexto legislativo). Corrige as limitações 1 e 2 do protótipo:

- **Dígito verificador.** Um número no formato de CPF/CNPJ só é redigido se os dígitos verificadores fecham — nº de
  ofício, protocolo e processo no mesmo formato (NNN.NNN.NNN-NN) deixam de ser mutilados. Com DV inválido e sem
  contexto, a chance de ser CPF real é desprezível.
- **Contexto.** Se o texto logo antes diz "CPF"/"CNPJ", redige mesmo com DV inválido (um CPF digitado errado ainda é
  dado pessoal). Se diz "processo"/"protocolo"/"ofício", não redige (é número de documento público).

NER de PII amplo (nome de pessoa privada, endereço) segue deferido — `[GAP]` do protótipo, limitação 6.
"""

from __future__ import annotations

import re
from collections import Counter
from collections.abc import Callable

from pydantic import BaseModel

_CNPJ = re.compile(r"(?<!\d)\d{2}\.?\d{3}\.?\d{3}/?\d{4}-?\d{2}(?!\d)")
_CPF = re.compile(r"(?<![\d/])\d{3}\.?\d{3}\.?\d{3}-?\d{2}(?![\d/])")
_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")

_JANELA = 24  # caracteres olhados antes do número
_CTX_PESSOAL = re.compile(r"\b(cpf|cnpj)\b", re.IGNORECASE)
_CTX_DOCUMENTO = re.compile(r"\b(processo|protocolo|of[ií]cio|requerimento|n[º°o]\.?\s*de\s+ordem)\b", re.IGNORECASE)


class Redacao(BaseModel):
    texto: str
    contagem: dict[str, int]


def _digitos(s: str) -> list[int]:
    return [int(c) for c in s if c.isdigit()]


def cpf_valido(s: str) -> bool:
    d = _digitos(s)
    if len(d) != 11 or len(set(d)) == 1:
        return False
    for n in (9, 10):
        soma = sum(d[i] * (n + 1 - i) for i in range(n))
        dv = (soma * 10) % 11 % 10
        if dv != d[n]:
            return False
    return True


def cnpj_valido(s: str) -> bool:
    d = _digitos(s)
    if len(d) != 14 or len(set(d)) == 1:
        return False
    pesos1 = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
    pesos2 = [6, *pesos1]
    for pesos, n in ((pesos1, 12), (pesos2, 13)):
        resto = sum(d[i] * pesos[i] for i in range(n)) % 11
        dv = 0 if resto < 2 else 11 - resto
        if dv != d[n]:
            return False
    return True


def _decidir(texto: str, inicio: int, valido: bool) -> bool:
    antes = texto[max(0, inicio - _JANELA) : inicio]
    if _CTX_PESSOAL.search(antes):
        return True
    if _CTX_DOCUMENTO.search(antes):
        return False
    return valido


def _substituidor(
    tipo: str, validador: Callable[[str], bool] | None, contagem: Counter[str]
) -> Callable[[re.Match[str]], str]:
    def trocar(m: re.Match[str]) -> str:
        valido = True if validador is None else validador(m.group(0))
        if validador is None or _decidir(m.string, m.start(), valido):
            contagem[tipo] += 1
            return f"[REDIGIDO:{tipo.upper()}]"
        return m.group(0)

    return trocar


def redigir(texto: str) -> Redacao:
    """Devolve o texto com cada identificador trocado por um marcador tipado, e a CONTAGEM por tipo (nunca os
    valores — é o que vai para a auditoria, B4). CNPJ antes de CPF (mais longo, não fragmenta)."""
    contagem: Counter[str] = Counter()
    out = _CNPJ.sub(_substituidor("cnpj", cnpj_valido, contagem), texto)
    out = _CPF.sub(_substituidor("cpf", cpf_valido, contagem), out)
    out = _EMAIL.sub(_substituidor("email", None, contagem), out)
    return Redacao(texto=out, contagem=dict(contagem))
