"""Métricas da PoC de áudio — testadas com linhas do tempo sintéticas (sem modelo, sem rede).

Uma linha do tempo é uma lista de (inicio_s, fim_s, rotulo). A referência é a verdade anotada por uma pessoa;
a hipótese é o que o sistema produziu."""
import pytest

from poc.metricas import der, tempo_de_fala


def test_hipotese_identica_tem_der_zero():
    ref = [(0, 10, "Ana"), (10, 20, "Bruno")]
    assert der(ref, ref)["der"] == pytest.approx(0.0)


def test_rotulos_da_hipotese_sao_arbitrarios_quando_mapear():
    """A diarização devolve 'SPK_0', 'SPK_1'; o DER casa os rótulos pela melhor correspondência."""
    ref = [(0, 10, "Ana"), (10, 20, "Bruno")]
    hip = [(0, 10, "SPK_1"), (10, 20, "SPK_0")]
    assert der(ref, hip)["der"] == pytest.approx(0.0)


def test_sem_mapear_nomes_trocados_sao_confusao():
    ref = [(0, 10, "Ana"), (10, 20, "Bruno")]
    hip = [(0, 10, "Bruno"), (10, 20, "Ana")]
    r = der(ref, hip, mapear=False)
    assert r["confusao"] == pytest.approx(1.0)
    assert r["der"] == pytest.approx(1.0)


def test_fala_perdida_e_falso_alarme():
    ref = [(0, 10, "Ana")]
    hip = [(0, 5, "X"), (10, 15, "X")]
    r = der(ref, hip)
    assert r["perdida"] == pytest.approx(0.5)       # 5 s de Ana sem ninguém na hipótese
    assert r["falso_alarme"] == pytest.approx(0.5)  # 5 s de fala inventada, sobre 10 s de referência
    assert r["der"] == pytest.approx(1.0)


def test_confusao_parcial():
    ref = [(0, 10, "Ana"), (10, 20, "Bruno")]
    hip = [(0, 15, "A"), (15, 20, "B")]  # A cobre Ana e metade de Bruno
    r = der(ref, hip)
    assert r["confusao"] == pytest.approx(5 / 20)
    assert r["der"] == pytest.approx(5 / 20)


def test_colar_ignora_fronteiras():
    """Fronteira anotada à mão tem imprecisão; o colar desconta ±colar s em volta de cada troca da referência."""
    ref = [(0, 10, "Ana"), (10, 20, "Bruno")]
    hip = [(0, 10.2, "A"), (10.2, 20, "B")]
    assert der(ref, hip)["der"] > 0
    assert der(ref, hip, colar=0.25)["der"] == pytest.approx(0.0)


def test_fala_sobreposta_conta_os_dois():
    ref = [(0, 10, "Ana"), (5, 10, "Bruno")]  # aparte: Bruno fala junto nos 5 s finais
    hip = [(0, 10, "A")]
    r = der(ref, hip)
    assert r["perdida"] == pytest.approx(5 / 15)
    assert r["der"] == pytest.approx(5 / 15)


def test_tempo_de_fala_por_pessoa():
    assert tempo_de_fala([(0, 10, "Ana"), (12, 15, "Ana"), (20, 21, "Bruno")]) == {"Ana": 13, "Bruno": 1}
