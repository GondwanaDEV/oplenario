"""Utilitário de captação (Faixa A / A.2): o envio em streaming, a credencial, a pasta observada e o CLI."""

from __future__ import annotations

import hashlib
import json
import os
import threading
from collections.abc import Iterator
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

import pytest

from oplenario_captacao.cli import main, metadados_do_arquivo, observar
from oplenario_captacao.envio import ClienteOidc, FalhaEnvio, Metadados, Recibo, TokenFixo, enviar
from oplenario_captacao.nome_obs import fuso, inicio_pelo_nome, iso
from oplenario_captacao.pasta import ARQUIVO_ESTADO, Observador, Registro

BRT = fuso("-03:00")

# ---------- um core de mentira, HTTP de verdade ----------


class Core:
    def __init__(self) -> None:
        self.pedidos: list[dict[str, Any]] = []
        self.respostas: list[tuple[int, dict[str, Any]]] = []

    def responder(self, status: int, corpo: dict[str, Any]) -> None:
        self.respostas.append((status, corpo))


@pytest.fixture
def core() -> Iterator[tuple[Core, str]]:
    estado = Core()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            n = int(self.headers.get("Content-Length", "0"))
            corpo = self.rfile.read(n)
            u = urlparse(self.path)
            estado.pedidos.append(
                {
                    "path": u.path,
                    "query": {k: v[0] for k, v in parse_qs(u.query).items()},
                    "auth": self.headers.get("Authorization"),
                    "tipo": self.headers.get("Content-Type"),
                    "corpo": corpo,
                }
            )
            status, resp = (
                estado.respostas.pop(0)
                if estado.respostas
                else (201, {"id": "seg-1", "audio-hash": hashlib.sha256(corpo).hexdigest(), "lock-version": 0})
            )
            b = json.dumps(resp).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)

        def log_message(self, *a: Any) -> None:
            pass

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield estado, f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def gravacao(tmp: Path, nome: str = "2026-09-22 17-50-00.mkv", conteudo: bytes = b"\x1a\x45\xdf\xa3 video") -> Path:
    p = tmp / nome
    p.write_bytes(conteudo)
    fim = datetime(2026, 9, 22, 21, 0, tzinfo=BRT).timestamp()
    os.utime(p, (fim, fim))
    return p


# ---------- nome do OBS ----------


def test_inicio_pelo_nome_do_obs_no_fuso_do_pc() -> None:
    assert iso(inicio_pelo_nome("2026-09-22 17-50-00.mkv", BRT)) == "2026-09-22T20:50:00Z"  # type: ignore[arg-type]
    assert inicio_pelo_nome("Replay 2026-09-22_17-50-00.mp4", BRT) is not None
    assert inicio_pelo_nome("sessao-ordinaria.mkv", BRT) is None
    assert inicio_pelo_nome("2026-13-40 99-00-00.mkv", BRT) is None
    with pytest.raises(ValueError):
        fuso("Fortaleza")


def test_metadados_inicio_pelo_nome_e_fim_pela_ultima_escrita(tmp_path: Path) -> None:
    m = metadados_do_arquivo(gravacao(tmp_path), BRT, None, False)
    q = parse_qs(m.query())
    assert q["iniciou-em"] == ["2026-09-22T20:50:00Z"]
    assert q["encerrou-em"] == ["2026-09-23T00:00:00Z"]
    assert q["fonte-ingestao"] == ["gravacao_local_pos_sessao"]
    assert q["motivo-inicio"] == ["inicio_sessao"] and q["motivo-fim"] == ["fim_sessao"]
    assert "sessao-id" not in q and "acesso-restrito" not in q


def test_fim_implausivel_nao_vai(tmp_path: Path) -> None:
    arq = gravacao(tmp_path, "2026-09-10 10-00-00.mkv")  # última escrita em 22/09: arquivo copiado depois
    q = parse_qs(metadados_do_arquivo(arq, BRT, None, False).query())
    assert q["iniciou-em"] == ["2026-09-10T13:00:00Z"]
    assert "encerrou-em" not in q and "motivo-fim" not in q


# ---------- envio ----------


def test_envia_o_arquivo_inteiro_com_metadata_e_credencial(core: tuple[Core, str], tmp_path: Path) -> None:
    estado, url = core
    conteudo = os.urandom(3 * 1024 * 1024 + 7)  # vários blocos de leitura
    arq = gravacao(tmp_path, conteudo=conteudo)
    r = enviar(
        url,
        TokenFixo("tok"),
        arq,
        Metadados(
            iniciou_em=datetime(2026, 9, 22, 20, 50, tzinfo=timezone.utc), sessao_id="s-12", acesso_restrito=True
        ),
    )
    [p] = estado.pedidos
    assert p["path"] == "/gravacoes" and p["auth"] == "Bearer tok" and p["tipo"] == "application/octet-stream"
    assert p["corpo"] == conteudo, "o corpo chegou íntegro"
    assert p["query"]["sessao-id"] == "s-12" and p["query"]["acesso-restrito"] == "true"
    assert r == Recibo(id="seg-1", audio_hash=hashlib.sha256(conteudo).hexdigest())


@pytest.mark.parametrize(
    ("status", "transitoria"), [(503, True), (429, True), (401, False), (409, False), (413, False)]
)
def test_classifica_a_falha_do_core(core: tuple[Core, str], tmp_path: Path, status: int, transitoria: bool) -> None:
    estado, url = core
    estado.responder(status, {"erro": "motivo do core"})
    with pytest.raises(FalhaEnvio) as e:
        enviar(url, TokenFixo("t"), gravacao(tmp_path), Metadados(iniciou_em=datetime.now(timezone.utc)))
    assert e.value.transitoria is transitoria and e.value.status == status
    assert "motivo do core" in str(e.value)


def test_core_fora_do_ar_e_transitorio(tmp_path: Path) -> None:
    with pytest.raises(FalhaEnvio) as e:
        enviar(
            "http://127.0.0.1:9", TokenFixo("t"), gravacao(tmp_path), Metadados(iniciou_em=datetime.now(timezone.utc))
        )
    assert e.value.transitoria


def test_credencial_oidc_reaproveita_o_token_ate_perto_de_expirar() -> None:
    chamadas: list[bytes] = []
    agora = [0.0]

    class Resp:
        def __init__(self, n: int) -> None:
            self.n = n

        def read(self) -> bytes:
            return json.dumps({"access_token": f"t{self.n}", "expires_in": 300}).encode()

        def __enter__(self) -> Resp:
            return self

        def __exit__(self, *a: object) -> None:
            pass

    def abrir(req: Any, timeout: float) -> Resp:
        chamadas.append(req.data)
        return Resp(len(chamadas))

    c = ClienteOidc("https://idp/token", "captacao-baturite", "segredo", abrir=abrir, relogio=lambda: agora[0])
    assert c.token() == "t1"
    agora[0] = 200
    assert c.token() == "t1", "ainda válido: não pede outro"
    agora[0] = 280
    assert c.token() == "t2", "a 30 s de expirar: renova"
    assert parse_qs(chamadas[0].decode())["grant_type"] == ["client_credentials"]


# ---------- pasta observada ----------


def test_so_fica_pronto_depois_de_estavel_e_nao_vazio() -> None:
    o = Observador(estavel_s=120)
    assert o.prontos([("a.mkv", 10, 1.0)], agora=0) == []
    assert o.prontos([("a.mkv", 20, 2.0)], agora=100) == [], "cresceu: o OBS ainda está gravando, o relógio zera"
    assert o.prontos([("a.mkv", 20, 2.0)], agora=200) == []
    assert o.prontos([("a.mkv", 20, 2.0)], agora=221) == ["a.mkv"]
    assert o.prontos([("vazio.mkv", 0, 1.0)], agora=0) == [] and o.prontos([("vazio.mkv", 0, 1.0)], agora=999) == []


def test_registro_sobrevive_a_reinicio(tmp_path: Path) -> None:
    r = Registro(tmp_path / ARQUIVO_ESTADO)
    r.marcar_enviado("a|1|1", "seg-1")
    r.marcar_recusado("b|1|1", "401")
    r2 = Registro(tmp_path / ARQUIVO_ESTADO)
    assert r2.ja_tratado("a|1|1") and r2.ja_tratado("b|1|1") and not r2.ja_tratado("c|1|1")


def test_observar_envia_uma_vez_espera_na_falha_transitoria_e_desiste_na_definitiva(tmp_path: Path) -> None:
    gravacao(tmp_path, "2026-09-22 17-50-00.mkv")
    gravacao(tmp_path, "2026-09-22 22-00-00.mkv")
    (tmp_path / "notas.txt").write_text("não é gravação")
    relogio = [0.0]
    tentativas: list[str] = []
    roteiro = {
        "2026-09-22 17-50-00.mkv": [FalhaEnvio("fora", transitoria=True), None],
        "2026-09-22 22-00-00.mkv": [FalhaEnvio("recusado (401)", transitoria=False)],
    }

    def enviar_fn(servidor: str, cred: object, caminho: Path, meta: Metadados) -> Recibo:
        tentativas.append(caminho.name)
        r = roteiro[caminho.name].pop(0)
        if r is not None:
            raise r
        return Recibo(id="seg-9", audio_hash="ab" * 32)

    def dormir(s: float) -> None:
        relogio[0] += s

    observar(
        tmp_path,
        "http://x",
        TokenFixo("t"),
        BRT,
        estavel_s=60,
        intervalo_s=30,
        enviar_fn=enviar_fn,
        relogio=lambda: relogio[0],
        dormir=dormir,
        rodadas=12,
    )
    assert tentativas.count("notas.txt") == 0
    assert tentativas.count("2026-09-22 22-00-00.mkv") == 1, "definitiva: uma tentativa só"
    assert tentativas.count("2026-09-22 17-50-00.mkv") == 2, "transitória: esperou e tentou de novo, depois parou"
    reg = Registro(tmp_path / ARQUIVO_ESTADO)
    assert list(reg.enviados.values()) == ["seg-9"] and len(reg.recusados) == 1


# ---------- CLI ----------


def test_cli_enviar(core: tuple[Core, str], tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    estado, url = core
    arq = gravacao(tmp_path)
    assert main(["--servidor", url, "enviar", str(arq), "--sessao", "s-1"], env={"OPLENARIO_TOKEN": "tok"}) == 0
    assert estado.pedidos[0]["query"]["sessao-id"] == "s-1"
    assert "Enviado" in capsys.readouterr().out


def test_cli_erros_de_uso(tmp_path: Path) -> None:
    assert main(["enviar", str(tmp_path / "x.mkv")], env={"OPLENARIO_TOKEN": "t"}) == 2, "sem servidor"
    assert main(["--servidor", "http://x", "enviar", str(tmp_path / "x.mkv")], env={"OPLENARIO_TOKEN": "t"}) == 2
    with pytest.raises(SystemExit):
        main(["--servidor", "http://x", "enviar", str(gravacao(tmp_path))], env={})


def test_cli_recusa_do_core_sai_com_1(core: tuple[Core, str], tmp_path: Path) -> None:
    estado, url = core
    estado.responder(409, {"erro": "sessao nao realizada nao recebe gravacao"})
    assert (
        main(["--servidor", url, "enviar", str(gravacao(tmp_path)), "--sessao", "s"], env={"OPLENARIO_TOKEN": "t"}) == 1
    )


def test_iso_sempre_em_utc() -> None:
    assert iso(datetime(2026, 1, 1, 0, 0, tzinfo=timezone(timedelta(hours=-3)))) == "2026-01-01T03:00:00Z"
