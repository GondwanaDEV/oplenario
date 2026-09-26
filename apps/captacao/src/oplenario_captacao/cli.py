"""`oplenario-captar` — leva a gravação do OBS ao O Plenário.

    oplenario-captar enviar "2026-09-22 18-00-12.mkv"            # um arquivo
    oplenario-captar enviar gravacao.mkv --sessao <id-da-sessao>  # já vinculado a uma sessão
    oplenario-captar observar "C:/Users/obs/Videos"               # fica vigiando a pasta de gravação do OBS

Ambiente: OPLENARIO_URL (endereço da API do core) e a credencial — OPLENARIO_TOKEN, ou
OPLENARIO_OIDC_TOKEN_URL + OPLENARIO_OIDC_CLIENT_ID + OPLENARIO_OIDC_CLIENT_SECRET (conta de serviço de papel
`captacao`). Sem sessão, a gravação aparece na tela "Gravações" do O Plenário para a secretaria vincular.
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from collections.abc import Callable, Mapping
from datetime import datetime, timedelta, timezone
from pathlib import Path

from oplenario_captacao.envio import ClienteOidc, Credencial, FalhaEnvio, Metadados, Recibo, TokenFixo, enviar
from oplenario_captacao.nome_obs import fuso, inicio_pelo_nome
from oplenario_captacao.pasta import ARQUIVO_ESTADO, Observador, Registro, assinatura, candidatos


def credencial_do_ambiente(env: Mapping[str, str]) -> Credencial:
    if token := env.get("OPLENARIO_TOKEN"):
        return TokenFixo(token)
    url, cid, segredo = (
        env.get(k) for k in ("OPLENARIO_OIDC_TOKEN_URL", "OPLENARIO_OIDC_CLIENT_ID", "OPLENARIO_OIDC_CLIENT_SECRET")
    )
    if url and cid and segredo:
        return ClienteOidc(url, cid, segredo)
    raise SystemExit("Falta a credencial: defina OPLENARIO_TOKEN ou as três variáveis OPLENARIO_OIDC_*.")


def metadados_do_arquivo(arquivo: Path, tz: timezone, sessao: str | None, restrito: bool) -> Metadados:
    """Início pelo nome do OBS (senão, a criação do arquivo); fim = a última escrita."""
    st = arquivo.stat()
    fim = datetime.fromtimestamp(st.st_mtime, tz=timezone.utc)
    inicio = inicio_pelo_nome(arquivo.name, tz)
    if inicio is None:
        criado = getattr(st, "st_birthtime", None) or st.st_ctime
        inicio = datetime.fromtimestamp(min(criado, st.st_mtime), tz=timezone.utc)
    # fim antes do início, ou mais de um dia depois (arquivo copiado/tocado depois da gravação): o fim não é
    # confiável — vai sem ele, em vez de dizer ao core que a sessão durou dias.
    plausivel = inicio < fim <= inicio + timedelta(hours=24)
    return Metadados(
        iniciou_em=inicio, encerrou_em=fim if plausivel else None, sessao_id=sessao, acesso_restrito=restrito
    )


def _log(msg: str) -> None:
    print(f"[{datetime.now().strftime('%d/%m %H:%M:%S')}] {msg}", flush=True)


def observar(
    pasta: Path,
    servidor: str,
    credencial: Credencial,
    tz: timezone,
    *,
    estavel_s: float,
    intervalo_s: float,
    restrito: bool = False,
    enviar_fn: Callable[..., Recibo] = enviar,
    relogio: Callable[[], float] = time.time,
    dormir: Callable[[float], None] = time.sleep,
    rodadas: int | None = None,
) -> None:
    """Varre a pasta a cada `intervalo_s`; envia o que ficou estável; nunca reenvia; falha transitória tenta de novo
    com espera crescente (até 30 min), definitiva é registrada e não trava a fila."""
    registro = Registro(pasta / ARQUIVO_ESTADO)
    obs = Observador(estavel_s)
    espera: dict[str, tuple[float, float]] = {}  # assinatura -> (próxima tentativa, atraso atual)
    n = 0
    while rodadas is None or n < rodadas:
        n += 1
        agora = relogio()
        arquivos = candidatos(pasta)
        por_nome = {p.name: p for p in arquivos}
        listagem = [(p.name, p.stat().st_size, p.stat().st_mtime) for p in arquivos]
        for nome in obs.prontos(listagem, agora):
            caminho = por_nome[nome]
            ass = assinatura(caminho)
            if registro.ja_tratado(ass) or espera.get(ass, (0.0, 0.0))[0] > agora:
                continue
            try:
                recibo = enviar_fn(servidor, credencial, caminho, metadados_do_arquivo(caminho, tz, None, restrito))
            except FalhaEnvio as e:
                if e.transitoria:
                    atraso = min(max(espera.get(ass, (0.0, 30.0))[1] * 2, 60.0), 1800.0)
                    espera[ass] = (agora + atraso, atraso)
                    _log(f"{nome}: {e} — nova tentativa em {int(atraso)} s")
                else:
                    registro.marcar_recusado(ass, str(e))
                    _log(f"{nome}: {e} — NÃO será reenviado; confira na tela Gravações do O Plenário")
                continue
            registro.marcar_enviado(ass, recibo.id)
            espera.pop(ass, None)
            _log(f"{nome}: enviado (sha256 {recibo.audio_hash[:12]}…)")
        dormir(intervalo_s)


def main(argv: list[str] | None = None, env: Mapping[str, str] | None = None) -> int:
    env = os.environ if env is None else env
    ap = argparse.ArgumentParser(prog="oplenario-captar", description="Envia a gravação do OBS ao O Plenário.")
    ap.add_argument("--servidor", default=env.get("OPLENARIO_URL"), help="endereço da API (ou OPLENARIO_URL)")
    ap.add_argument("--fuso", default="-03:00", help="fuso do relógio do PC, para ler o nome do OBS (padrão -03:00)")
    ap.add_argument("--restrito", action="store_true", help="gravação de acesso restrito (ex.: sessão secreta)")
    sub = ap.add_subparsers(dest="comando", required=True)
    e = sub.add_parser("enviar", help="envia um arquivo")
    e.add_argument("arquivo", type=Path)
    e.add_argument("--sessao", help="id da sessão (senão a secretaria vincula na tela Gravações)")
    o = sub.add_parser("observar", help="vigia a pasta de gravação do OBS e envia cada arquivo terminado")
    o.add_argument("pasta", type=Path)
    o.add_argument("--estavel", type=float, default=120, help="segundos sem mudança para considerar pronto (120)")
    o.add_argument("--intervalo", type=float, default=30, help="segundos entre varreduras (30)")
    args = ap.parse_args(argv)

    if not args.servidor:
        print("Falta o endereço do O Plenário: use --servidor ou OPLENARIO_URL.", file=sys.stderr)
        return 2
    tz = fuso(args.fuso)
    credencial = credencial_do_ambiente(env)

    if args.comando == "enviar":
        if not args.arquivo.is_file():
            print(f"Arquivo não encontrado: {args.arquivo}", file=sys.stderr)
            return 2
        try:
            r = enviar(
                args.servidor,
                credencial,
                args.arquivo,
                metadados_do_arquivo(args.arquivo, tz, args.sessao, args.restrito),
            )
        except FalhaEnvio as falha:
            print(f"Não enviado: {falha}", file=sys.stderr)
            return 1
        print(f"Enviado. Gravação {r.id} (sha256 {r.audio_hash}).")
        return 0

    if not args.pasta.is_dir():
        print(f"Pasta não encontrada: {args.pasta}", file=sys.stderr)
        return 2
    _log(f"Vigiando {args.pasta} (pronto após {int(args.estavel)} s sem mudança). Ctrl+C para parar.")
    try:
        observar(
            args.pasta,
            args.servidor,
            credencial,
            tz,
            estavel_s=args.estavel,
            intervalo_s=args.intervalo,
            restrito=args.restrito,
        )
    except KeyboardInterrupt:
        _log("Parado.")
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
