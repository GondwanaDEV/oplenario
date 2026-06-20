"""
Suite de aceitação do avaliador. É o PROPÓSITO do protótipo (docs/05 §2, CLAUDE.md §7):
os 4 templates do Eixo C SÃO os casos de aceitação; os negativos provam o save-time
type-check; o loop de runtime valida §22.7.7 end-to-end.

Rodar:  python3 test_motor.py   (sai != 0 se algo falhar)
"""
from __future__ import annotations
from datetime import date
from fractions import Fraction

from nucleo import carregar_envelope, parse_expr
from verificador import verificar_template
import catalogo as cat
import templates as tpl
from runtime import (
    Motor, Avaliador, Estado, Ente, AtoDespesa, AtoLegislativo, Competencia, PrazoVigente,
)

_FALHAS = []


def _check(nome, cond, detalhe=""):
    marca = "ok " if cond else "FALHA"
    print(f"  [{marca}] {nome}" + (f" — {detalhe}" if detalhe and not cond else ""))
    if not cond:
        _FALHAS.append(nome)


# ---------------------------------------------------------------------------
def teste_typecheck_canonicos():
    print("\n# save-time type-check — 4 templates canônicos (Eixo C)")
    for chave in ("T1", "T2", "T3"):
        r = verificar_template(carregar_envelope(tpl.CANONICOS[chave]))
        _check(f"{chave} VALIDA", r.status == "VALIDA", f"erros={r.erros}")
        _check(f"{chave} carimba registry", r.registry_versao_ref == cat.CATALOGO_VERSAO)
    # T4: núcleo tipa, envelope de compliance NÃO cabe (S4)
    r4 = verificar_template(carregar_envelope(tpl.CANONICOS["T4"]))
    _check("T4 expressao_ok (núcleo reaproveitável)", r4.expressao_ok is True)
    _check("T4 status INVALIDA (envelope errado)", r4.status == "INVALIDA")
    _check("T4 não carimba (não persiste como vigente)", r4.registry_versao_ref is None)


def teste_typecheck_negativos():
    print("\n# save-time type-check — negativos são REJEITADOS (dec. 2)")
    esperado = {
        "N1": "arg",            # arg de tipo errado
        "N2": "desconhecida",   # função inexistente
        "N3": "esperava Booleano",  # exige não-booleano
        "N4": "comparação",     # tipos incompatíveis
        "N5": "chave desconhecida",  # parametro_tenant fora do schema
    }
    for chave, frag in esperado.items():
        r = verificar_template(carregar_envelope(tpl.NEGATIVOS[chave]))
        _check(f"{chave} INVALIDA", r.status == "INVALIDA")
        achou = any(frag in e for e in r.erros)
        _check(f"{chave} erro pertinente ({frag!r})", achou, f"erros={r.erros}")


def teste_aritmetica_exata():
    print("\n# aritmética EXATA — a armadilha do quórum (T4)")
    av = Avaliador(Estado(), date(2026, 6, 19))
    # 2/3 de 7 = 4.666… → ceil = 5. "metade mais um" / truncar float daria 4 (errado).
    v = av.avaliar(parse_expr("arredonda_cima( fracao(2,3) * 7 )"), {})
    _check("ceil(2/3·7) == 5 (exato)", v == 5, f"veio {v}")
    _check("truncar float daria 4 (bug evitado)", int(2 / 3 * 7) == 4)
    v2 = av.avaliar(parse_expr("fracao(2,3) * 9"), {})
    _check("2/3·9 == 6 exato (Fraction)", v2 == Fraction(6, 1))


def _estado_t1():
    estado = Estado()
    estado.prazos.append(PrazoVigente("TCE-CE", "SIM_mensal", "2026-05",
                                      date(2026, 6, 30), "IN 04/2019", vigente=True))
    return estado


def teste_runtime_deadline_bound():
    print("\n# runtime — sabor COM prazo: materializa → avalia → cumpre")
    estado = _estado_t1()
    ente = Ente("cmf", populacao=2_700_000, membros=43)
    comp = Competencia(2026, 5)
    amb = {"ente": ente, "competencia": comp}
    regra = carregar_envelope(tpl.T1)
    motor = Motor(estado, date(2026, 6, 19))

    av = motor.avaliar_regra(regra, cat.CATALOGO_VERSAO, amb, "competencia", "2026-05")
    obrig = motor.obrigacoes[("cmf", "remessa_mensal_sim", "competencia", "2026-05")]
    _check("remessa não enviada → nao_conforme", av.veredito == "nao_conforme")
    _check("obrigação materializada pendente", obrig.estado == "pendente")
    _check("vence_em = prazo vigente (2026-06-30)", obrig.vence_em == date(2026, 6, 30))
    _check("prazo_fonte_ref carimbado", obrig.prazo_fonte_ref == "IN 04/2019")

    # envia a remessa e reavalia (mesmo objeto → idempotente)
    estado.remessas.add(("cmf", "SIM", "2026-05"))
    av2 = motor.avaliar_regra(regra, cat.CATALOGO_VERSAO, amb, "competencia", "2026-05")
    _check("após envio → conforme", av2.veredito == "conforme")
    _check("obrigação vira cumprida", obrig.estado == "cumprida")
    _check("cumprida_em carimbado", obrig.cumprida_em == date(2026, 6, 19))
    _check("auditoria append-only cresce", len(motor.avaliacoes) == 2)
    _check("não duplicou obrigação (idempotência)", len(motor.obrigacoes) == 1)


def teste_runtime_continua():
    print("\n# runtime — sabor CONTÍNUO: não materializa, só audita (§22.7.7)")
    continua = """
template: cont_transparencia_permanente
contexto: compliance
dominio: federal
parametros: { despesa: AtoDespesa }
aplica_quando: verdadeiro
exige: publicada_no_portal(despesa)
severidade: bloqueante
referencia_normativa: "LAI art.8 par.1 (dever continuo)"
"""
    r = verificar_template(carregar_envelope(continua))
    _check("regra contínua é VALIDA", r.status == "VALIDA", f"erros={r.erros}")
    estado = Estado()
    ente = Ente("cmf", 2_700_000, 43)
    despesa = AtoDespesa("d1", date(2026, 6, 10), publicada=False)
    motor = Motor(estado, date(2026, 6, 19))
    av = motor.avaliar_regra(carregar_envelope(continua), cat.CATALOGO_VERSAO,
                             {"ente": ente, "despesa": despesa}, "despesa", "d1")
    _check("contínua NÃO materializa obrigação", len(motor.obrigacoes) == 0)
    _check("contínua gera avaliação sem obrigacao_id", av.obrigacao_id is None)
    _check("despesa não publicada → nao_conforme", av.veredito == "nao_conforme")


def teste_runtime_aplica_quando():
    print("\n# runtime — aplica_quando=falso → inaplicável (sem obrigação)")
    estado = Estado()
    ente = Ente("vila_pequena", populacao=8_000, membros=9)   # ≤10k: dispensado (LAI 8º§4º)
    despesa = AtoDespesa("d9", date(2026, 6, 10), publicada=False)
    motor = Motor(estado, date(2026, 6, 19))
    av = motor.avaliar_regra(carregar_envelope(tpl.T2), cat.CATALOGO_VERSAO,
                             {"ente": ente, "despesa": despesa}, "despesa", "d9")
    _check("população ≤10k → inaplicavel", av.veredito == "inaplicavel")
    _check("inaplicável não materializa obrigação", len(motor.obrigacoes) == 0)


def teste_s3_restamp_circular():
    print("\n# runtime — S3: Ofício Circular desliza prazo → re-stamp das ABERTAS")
    estado = _estado_t1()
    ente = Ente("cmf", 2_700_000, 43)
    amb = {"ente": ente, "competencia": Competencia(2026, 5)}
    regra = carregar_envelope(tpl.T1)
    motor = Motor(estado, date(2026, 6, 19))
    motor.avaliar_regra(regra, cat.CATALOGO_VERSAO, amb, "competencia", "2026-05")
    obrig = motor.obrigacoes[("cmf", "remessa_mensal_sim", "competencia", "2026-05")]
    n_aval_antes = len(motor.avaliacoes)

    motor.aplicar_circular("TCE-CE", "SIM_mensal", "2026-05", date(2026, 7, 15), "OC 16/2026")
    _check("pendente re-carimbada p/ nova data", obrig.vence_em == date(2026, 7, 15))
    _check("nova fonte de prazo registrada", obrig.prazo_fonte_ref == "OC 16/2026")
    _check("evento de reprazo emitido",
           any("Reprazada" in e for e in motor.eventos))
    _check("re-sweep gerou avaliação (origem=sweep)",
           any(a.origem_avaliacao == "sweep" for a in motor.avaliacoes))
    _check("auditoria só cresce (append-only)", len(motor.avaliacoes) > n_aval_antes)


def teste_s3_cumprida_nao_move():
    print("\n# runtime — S3: obrigação CUMPRIDA não desliza (só abertas movem)")
    estado = _estado_t1()
    estado.remessas.add(("cmf", "SIM", "2026-05"))    # já enviada → vai cumprir
    ente = Ente("cmf", 2_700_000, 43)
    amb = {"ente": ente, "competencia": Competencia(2026, 5)}
    motor = Motor(estado, date(2026, 6, 19))
    motor.avaliar_regra(carregar_envelope(tpl.T1), cat.CATALOGO_VERSAO, amb, "competencia", "2026-05")
    obrig = motor.obrigacoes[("cmf", "remessa_mensal_sim", "competencia", "2026-05")]
    _check("obrigação está cumprida", obrig.estado == "cumprida")
    venc_antes = obrig.vence_em
    motor.aplicar_circular("TCE-CE", "SIM_mensal", "2026-05", date(2026, 7, 15), "OC 16/2026")
    _check("cumprida mantém vence_em (não move)", obrig.vence_em == venc_antes)


def main():
    teste_typecheck_canonicos()
    teste_typecheck_negativos()
    teste_aritmetica_exata()
    teste_runtime_deadline_bound()
    teste_runtime_continua()
    teste_runtime_aplica_quando()
    teste_s3_restamp_circular()
    teste_s3_cumprida_nao_move()
    print("\n" + ("=" * 60))
    if _FALHAS:
        print(f"RESULTADO: {len(_FALHAS)} FALHA(S): {_FALHAS}")
        return 1
    print("RESULTADO: TODOS OS TESTES PASSARAM ✅")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
