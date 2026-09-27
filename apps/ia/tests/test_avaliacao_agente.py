"""B.9 — a avaliação do AGENTE: o conjunto de segurança passa no pipeline, e o harness REPROVA quando o agente faz o
que não devia (senão o conjunto seria decorativo)."""

from __future__ import annotations

import json
from pathlib import Path

from oplenario_ia.avaliacao.agente import ConjuntoAgente, avaliar_agente
from oplenario_ia.avaliacao.cli import main

CONJUNTO = Path(__file__).parent.parent / "avaliacoes" / "agente-seguranca.json"


def _conjunto() -> ConjuntoAgente:
    return ConjuntoAgente.model_validate_json(CONJUNTO.read_text(encoding="utf-8"))


def test_o_conjunto_de_seguranca_do_agente_passa() -> None:
    r = avaliar_agente(_conjunto())
    assert r.aprovado, [c.falhas for c in r.casos if not c.passou]
    assert {c.tipo for c in r.casos} == {"seguranca"}
    assert len(r.casos) >= 5


def test_o_harness_reprova_o_que_o_agente_nao_devia_fazer() -> None:
    bruto = json.loads(CONJUNTO.read_text(encoding="utf-8"))
    caso = next(c for c in bruto["casos"] if c["id"] == "seg-agente-instrucao-escondida-no-resultado-de-terceiro")
    caso["esperado"]["nunca_chama"] = ["situacao_da_materia"]
    caso["esperado"]["contaminado"] = False
    caso["esperado"]["texto_contem"] = ["frase que nunca sai"]
    r = avaliar_agente(ConjuntoAgente.model_validate({**bruto, "casos": [caso]}))
    assert not r.aprovado
    falhas = r.casos[0].falhas
    assert "chamou situacao_da_materia" in falhas
    assert any("contaminado=True" in f for f in falhas)
    assert any("frase que nunca sai" in f for f in falhas)


def test_a_cli_roda_os_dois_niveis(capsys: object) -> None:
    assert main([str(CONJUNTO.parent)]) == 0
