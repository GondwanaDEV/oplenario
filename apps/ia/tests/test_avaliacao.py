"""O harness de avaliação (§22.11.8) e o custo por Casa (Eixo 8.3)."""

import json
from decimal import Decimal
from pathlib import Path

import pytest

from oplenario_ia.avaliacao.cli import main
from oplenario_ia.avaliacao.conjunto import Conjunto
from oplenario_ia.avaliacao.custo import calcular, tabela_padrao
from oplenario_ia.avaliacao.harness import avaliar
from oplenario_ia.confianca.metricas import consumo_por_ente
from oplenario_ia.confianca.registro import RegistroMemoria
from oplenario_ia.governanca.filtro import PedidoGovernado
from oplenario_ia.governanca.proveniencia import Peca, Proveniencia, Sigilo
from oplenario_ia.inferencia.fake import PortaFake
from oplenario_ia.inferencia.modelo import Uso
from oplenario_ia.nucleo import Nucleo

AVALIACOES = Path(__file__).parent.parent / "avaliacoes"
PUBLICA = {"texto": "Pauta pública.", "proveniencia": {"origem": "sessao:1", "sigilo": "publico"}}


def conjunto(*casos: dict[str, object]) -> Conjunto:
    return Conjunto.model_validate({"conjunto": "t", "versao": 1, "descricao": "d", "casos": list(casos)})


def caso(**kw: object) -> dict[str, object]:
    return {"id": "c1", "tipo": "objetiva", "descricao": "d", "pecas": [PUBLICA]} | kw


def test_conjunto_da_base_comum_passa_inteiro_no_fake() -> None:
    [arquivo] = sorted(AVALIACOES.glob("*.json"))
    r = avaliar(Conjunto.model_validate_json(arquivo.read_text()))
    assert r.aprovado, [c for c in r.casos if not c.passou]
    assert {c.tipo for c in r.casos} >= {"seguranca", "objetiva"}


def test_caso_que_nao_cumpre_a_expectativa_reprova_com_o_motivo() -> None:
    r = avaliar(
        conjunto(caso(resposta_fake="Aprovado!", esperado={"resultado": "artefato", "texto_nao_contem": ["aprovado"]}))
    )
    [c] = r.casos
    assert not r.aprovado and c.falhas == ["texto contém 'aprovado'"]


def test_vazamento_para_o_fornecedor_reprova() -> None:
    r = avaliar(conjunto(caso(esperado={"resultado": "artefato", "nunca_enviado": ["Pauta"]})))
    assert r.casos[0].falhas == ["chegou ao fornecedor: 'Pauta'"]


def test_resultado_trocado_reprova() -> None:
    r = avaliar(conjunto(caso(esperado={"resultado": "indisponivel", "motivo": "sigilo"})))
    assert r.casos[0].falhas == ["esperava indisponível (sigilo), veio artefato"]


def test_contra_fornecedor_real_casos_so_fake_sao_pulados() -> None:
    real = PortaFake(vendor="real")
    r = avaliar(
        conjunto(
            caso(id="a", apenas_fake=True, esperado={"resultado": "artefato"}),
            caso(id="b", esperado={"resultado": "artefato"}),
        ),
        real,
    )
    assert [(c.id, c.pulado) for c in r.casos] == [("a", True), ("b", False)]
    assert r.vendor == "real" and len(real.recebidos) == 1


@pytest.mark.parametrize(
    "ruim",
    [
        caso(esperado={"resultado": "artefato", "nao_existe": 1}),  # chave errada não passa em silêncio
        caso(esperado={"resultado": "indisponivel"}),  # indisponível sem motivo
        caso(id="Com Espaço", esperado={"resultado": "artefato"}),
    ],
)
def test_conjunto_malformado_e_recusado(ruim: dict[str, object]) -> None:
    with pytest.raises(ValueError):
        conjunto(ruim)


def test_ids_repetidos_sao_recusados() -> None:
    with pytest.raises(ValueError):
        conjunto(caso(esperado={"resultado": "artefato"}), caso(esperado={"resultado": "artefato"}))


def test_cli_sai_0_aprovado_1_reprovado_2_invalido_e_grava_relatorio(tmp_path: Path) -> None:
    ok = tmp_path / "ok"
    ok.mkdir()
    (ok / "a.json").write_text(conjunto(caso(esperado={"resultado": "artefato"})).model_dump_json())
    saida = tmp_path / "resultados" / "r.json"
    assert main([str(ok), "--saida", str(saida)]) == 0
    assert json.loads(saida.read_text())[0]["aprovado"] is True

    ruim = tmp_path / "ruim.json"
    ruim.write_text(conjunto(caso(esperado={"resultado": "indisponivel", "motivo": "sigilo"})).model_dump_json())
    assert main([str(ruim)]) == 1
    (tmp_path / "invalido.json").write_text('{"conjunto": "x"}')
    assert main([str(tmp_path / "invalido.json")]) == 2
    assert main([str(tmp_path / "vazio-inexistente")]) == 2


# ---------- custo ----------


def test_custo_usa_a_tabela_datada_inclusive_cache() -> None:
    t = tabela_padrao()
    uso = Uso(entrada=1_000_000, saida=100_000, cache_leitura=1_000_000, cache_escrita=0)
    c = calcular(uso, "anthropic", "claude-opus-5", t)
    assert c.valor == Decimal("5.00") + Decimal("2.50") + Decimal("0.50")
    assert c.moeda == "USD" and c.tabela == t.consultado_em


def test_modelo_sem_preco_nao_vira_custo_zero() -> None:
    assert calcular(Uso(entrada=10), "anthropic", "modelo-novo", tabela_padrao()).valor is None


def test_consumo_por_casa_soma_tokens_e_custo_e_marca_parcial() -> None:
    reg = RegistroMemoria()
    peca = Peca(texto="lei pública", proveniencia=Proveniencia(origem="n", sigilo=Sigilo.PUBLICO))

    def pedido(ente: str) -> PedidoGovernado:
        return PedidoGovernado(ente_id=ente, correlation_id="c", operacao="op", instrucoes="i", pecas=[peca])

    Nucleo(PortaFake(), reg).executar(pedido("e1"))
    Nucleo(PortaFake(), reg).executar(pedido("e1"))
    Nucleo(PortaFake(modelo="sem-preco"), reg).executar(pedido("e2"))
    c = consumo_por_ente(reg.eventos())
    assert c["e1"].execucoes == 2 and c["e1"].tokens_entrada > 0 and not c["e1"].parcial
    assert c["e2"].parcial, "modelo sem preço: o consumo da Casa avisa que está incompleto"
