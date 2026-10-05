"""Caminho C (§22.6): atribuir quem falou a partir de quem TINHA A PALAVRA na tribuna (as falas que a Mesa
registra em `fala_executada`), sozinho ou combinado com a diarização."""
import pytest

from poc.caminho_c import cobertura_da_palavra, nomear_clusters


def test_cobertura_total_quando_so_fala_quem_tem_a_palavra():
    ref = [(0, 60, "Ana"), (60, 120, "Bruno")]
    palavra = [(0, 60, "Ana"), (60, 120, "Bruno")]
    r = cobertura_da_palavra(ref, palavra)
    assert r["acerto"] == pytest.approx(1.0)


def test_presidente_conduzindo_fora_da_tribuna_e_aparte_sao_os_erros():
    ref = [
        (0, 10, "Presidente"),   # abre os trabalhos — ninguém na tribuna
        (10, 70, "Ana"),         # fala principal
        (40, 50, "Bruno"),       # aparte sobreposto
    ]
    palavra = [(10, 70, "Ana")]
    r = cobertura_da_palavra(ref, palavra)
    assert r["fora_da_tribuna"] == pytest.approx(10 / 80)
    assert r["outro_falando"] == pytest.approx(10 / 80)
    assert r["acerto"] == pytest.approx(60 / 80)


def test_nomear_clusters_pela_palavra():
    """Cada grupo de voz da diarização recebe o nome de quem tinha a palavra durante a maior parte dele."""
    hip = [(0, 60, "SPK_0"), (60, 120, "SPK_1"), (120, 130, "SPK_0"), (130, 140, "SPK_2")]
    palavra = [(0, 60, "Ana"), (60, 120, "Bruno")]
    nomes = nomear_clusters(hip, palavra)
    assert nomes == {"SPK_0": "Ana", "SPK_1": "Bruno", "SPK_2": None}


def test_cluster_sem_maioria_clara_fica_sem_nome():
    hip = [(0, 10, "SPK_0"), (10, 20, "SPK_0")]
    palavra = [(0, 10, "Ana"), (10, 20, "Bruno")]
    assert nomear_clusters(hip, palavra, maioria_minima=0.6) == {"SPK_0": None}
