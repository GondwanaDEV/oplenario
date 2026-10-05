"""B.9 — a avaliação do AGENTE: o conjunto de segurança passa no pipeline, e o harness REPROVA quando o agente faz o
que não devia (senão o conjunto seria decorativo)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from pydantic import ValidationError

from oplenario_ia.agente import laco
from oplenario_ia.avaliacao.agente import CasoAgente, ConjuntoAgente, McpRoteirizado, avaliar_agente, conferir
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


# ---------- fatia 6 da Clara: o conjunto por papel e as expectativas novas ----------

CLARA = CONJUNTO.parent / "clara-papeis.json"


def _clara() -> dict[str, Any]:
    bruto: dict[str, Any] = json.loads(CLARA.read_text(encoding="utf-8"))
    return bruto


def test_o_conjunto_da_clara_por_papel_passa() -> None:
    r = avaliar_agente(ConjuntoAgente.model_validate(_clara()))
    assert r.aprovado, [(c.id, c.falhas) for c in r.casos if not c.passou]
    assert len(r.casos) >= 5


def test_a_ferramenta_pelo_nome_vem_do_catalogo_com_descricao_e_entrada() -> None:
    conjunto = ConjuntoAgente.model_validate(_clara())
    caso = next(c for c in conjunto.casos if c.id == "clara-dica-da-tela-consulta-a-materia")
    [f] = [f for f in McpRoteirizado(caso).ferramentas() if f.nome == "situacao_da_materia"]
    assert "sequencial" in f.entrada["properties"], "o modelo real precisa do formato de entrada para acertar"
    assert f.classe == "leitura" and f.descricao.startswith("Consulta uma proposição")


def test_ferramenta_fora_do_catalogo_reprova_o_conjunto() -> None:
    bruto = _clara()
    bruto["casos"][0]["ferramentas"] = ["ferramenta_que_nao_existe"]
    with pytest.raises(ValidationError, match="não está no catálogo"):
        ConjuntoAgente.model_validate(bruto)
    with pytest.raises(ValidationError, match="grupo vazio"):
        ConjuntoAgente.model_validate(
            {**_clara(), "casos": [{**_clara()["casos"][0], "esperado": {"texto_contem_algum": [[]]}}]}
        )


def test_o_harness_reprova_as_expectativas_novas() -> None:
    bruto = _clara()
    caso = next(c for c in bruto["casos"] if c["id"] == "clara-dica-da-tela-consulta-a-materia")
    caso["esperado"]["chama_com"] = {"situacao_da_materia": {"sequencial": 41}}
    caso["esperado"]["texto_contem_algum"] = [["frase que nunca sai", "outra que também não"]]
    caso["esperado"]["citacoes_conferidas_min"] = 5
    r = avaliar_agente(ConjuntoAgente.model_validate({**bruto, "casos": [caso]}))
    falhas = r.casos[0].falhas
    assert "não chamou situacao_da_materia com {'sequencial': 41}" in falhas
    assert any("nenhum de" in f and "frase que nunca sai" in f for f in falhas)
    assert any("mínimo 5" in f for f in falhas)


def test_chama_com_compara_os_argumentos_como_texto() -> None:
    caso = CasoAgente.model_validate(
        {
            "id": "x",
            "tipo": "objetiva",
            "descricao": "x",
            "pergunta": "x",
            "ferramentas": [{"nome": "situacao_da_materia"}],
            "esperado": {"chama_com": {"situacao_da_materia": {"sequencial": 42, "tipo": "projeto_lei"}}},
        }
    )
    r = laco.RespostaAgente(passos=[])
    chamou = [("situacao_da_materia", {"sequencial": "42", "tipo": "PROJETO_LEI", "ano": 2026})]
    assert not [f for f in conferir(caso.esperado, r, chamou, "") if "não chamou" in f]
    assert conferir(caso.esperado, r, [("situacao_da_materia", {"tipo": "projeto_lei"})], "")[0].startswith(
        "não chamou situacao_da_materia com"
    )
