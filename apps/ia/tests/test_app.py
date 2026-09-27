from fastapi.testclient import TestClient

from oplenario_ia.app import criar_app
from oplenario_ia.config import Config, carregar
from oplenario_ia.erros import Categoria, ErroIA


def test_saude_mostra_vendor_e_modelo_nunca_credencial() -> None:
    app = criar_app(Config(vendor="fake", modelo="fake-1"))
    r = TestClient(app).get("/saude")
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "versao": "0.1.0", "vendor": "fake", "modelo": "fake-1"}


def test_erro_ia_vira_corpo_estruturado_com_status_da_categoria() -> None:
    app = criar_app(Config())

    @app.get("/explode")
    def explode() -> None:
        raise ErroIA(Categoria.SOBRECARGA, "limite de taxa", retentavel=True)

    r = TestClient(app).get("/explode")
    assert r.status_code == 429
    assert r.json() == {
        "versao": 1,
        "categoria": "sobrecarga",
        "detalhe": "limite de taxa",
        "retentavel": True,
    }


def test_config_padrao_e_o_fake_nada_sai_do_cluster() -> None:
    assert carregar({}).vendor == "fake"
    c = carregar(
        {
            "OPLENARIO_IA_VENDOR": "anthropic",
            "OPLENARIO_IA_MODELO": "claude-sonnet-5",
            "OPLENARIO_IA_TIMEOUT_S": "12",
        }
    )
    assert (c.vendor, c.modelo, c.timeout_s) == ("anthropic", "claude-sonnet-5", 12.0)


def test_categorias_5_e_6_nao_sao_excecao() -> None:
    import pytest

    with pytest.raises(ValueError):
        ErroIA(Categoria.BAIXA_CONFIANCA, "x", retentavel=False)
