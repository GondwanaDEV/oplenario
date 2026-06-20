"""
Walkthrough narrado do motor (rodar: python3 demo.py). Mostra, em ordem:
  1) save-time type-check dos 4 templates do Eixo C + por que T4 não cabe;
  2) rejeição de regras mal-tipadas (o incidente comercial que o type-check evita);
  3) o loop de runtime §22.7.7 (materializa → avalia → monitora → audita) com a
     prova de compliance append-only e o re-stamp por Ofício Circular (S3).

Datas/prazos são fixtures ilustrativos (o [GAP] regulatório de §22.7.5 segue GAP).
"""
from __future__ import annotations
from datetime import date

from nucleo import carregar_envelope
from verificador import verificar_template
import catalogo as cat
import templates as tpl
from runtime import Motor, Estado, Ente, Competencia, PrazoVigente


def secao(t):
    print("\n" + "=" * 68 + f"\n{t}\n" + "=" * 68)


def passo1_typecheck():
    secao("1) SAVE-TIME TYPE-CHECK — os 4 templates do Eixo C contra o registry")
    for chave, fonte in tpl.CANONICOS.items():
        r = verificar_template(carregar_envelope(fonte))
        print(f"\n· {chave} [{r.chave}]  →  {r.status}")
        if r.registry_versao_ref:
            print(f"    carimbo registry_versao_ref = {r.registry_versao_ref}")
        for a in r.avisos:
            print(f"    aviso: {a}")
        for e in r.erros:
            print(f"    erro: {e}")
        if chave == "T4":
            print("    ↳ núcleo de expressão tipou (expressao_ok="
                  f"{r.expressao_ok}), mas o envelope de compliance NÃO cabe:")
            print("      quórum é GUARD de plenário, não obrigação-com-prazo (S4).")


def passo2_negativos():
    secao("2) REGRA MAL-TIPADA É REJEITADA — não chega a virar 'vigente' (dec. 2)")
    print("Uma regra de compliance que falha em runtime faz o cliente perder janela")
    print("de envio ao TCE — incidente inaceitável. Por isso o type-check é no SAVE.\n")
    for chave, fonte in tpl.NEGATIVOS.items():
        r = verificar_template(carregar_envelope(fonte))
        print(f"· {chave} [{r.chave}]  →  {r.status}")
        for e in r.erros:
            print(f"    ✗ {e}")


def passo3_runtime():
    secao("3) LOOP DE RUNTIME (§22.7.7): materializa → avalia → monitora → audita")
    estado = Estado()
    estado.prazos.append(PrazoVigente("TCE-CE", "SIM_mensal", "2026-05",
                                      date(2026, 6, 30), "IN 04/2019", vigente=True))
    ente = Ente("cmf", populacao=2_700_000, membros=43)
    amb = {"ente": ente, "competencia": Competencia(2026, 5)}
    regra = carregar_envelope(tpl.T1)
    motor = Motor(estado, agora=date(2026, 6, 19))

    print("\nEstado: câmara 'cmf', competência 2026-05, remessa SIM ainda NÃO enviada.")
    print("Evento dispara a avaliação da regra T1 (remessa_mensal_sim):\n")
    motor.avaliar_regra(regra, cat.CATALOGO_VERSAO, amb, "competencia", "2026-05")
    _dump_obrigacoes(motor)
    _dump_monitor(motor)

    print("\n→ A câmara envia a remessa. Novo evento reavalia o MESMO objeto:\n")
    estado.remessas.add(("cmf", "SIM", "2026-05"))
    motor.avaliar_regra(regra, cat.CATALOGO_VERSAO, amb, "competencia", "2026-05")
    _dump_obrigacoes(motor)

    print("\n→ TCE-CE emite Ofício Circular deslizando o prazo da competência 2026-05.")
    print("  (Numa câmara onde ainda estivesse pendente, a obrigação aberta seria")
    print("   re-carimbada; aqui já cumpriu, então não se move.)\n")
    motor.aplicar_circular("TCE-CE", "SIM_mensal", "2026-05", date(2026, 7, 15), "OC 16/2026")
    _dump_obrigacoes(motor)

    _dump_auditoria(motor)
    _dump_eventos(motor)


def _dump_obrigacoes(motor: Motor):
    print("  obrigações (prazo_dominio_ativo):")
    if not motor.obrigacoes:
        print("    (nenhuma materializada)")
    for o in motor.obrigacoes.values():
        print(f"    - {o.id} {o.template_chave} [{o.objeto_tipo}:{o.objeto_id}] "
              f"estado={o.estado} vence_em={o.vence_em} fonte={o.prazo_fonte_ref}")


def _dump_monitor(motor: Motor):
    print("  monitor (derivação de leitura sobre vence_em — não persiste):")
    for m in motor.monitorar():
        print(f"    - {m['obrigacao']} {m['template']} situacao={m['situacao']}")


def _dump_auditoria(motor: Motor):
    print("\n  PROVA DE COMPLIANCE — compliance_avaliacao (append-only, Invariante 10):")
    for a in motor.avaliacoes:
        print(f"    #{a.id} {a.template_chave} veredito={a.veredito} "
              f"origem={a.origem_avaliacao} reg={a.registry_versao_ref} ({a.detalhe})")


def _dump_eventos(motor: Motor):
    print("\n  eventos de domínio emitidos:")
    for e in motor.eventos:
        print(f"    · {e}")


if __name__ == "__main__":
    passo1_typecheck()
    passo2_negativos()
    passo3_runtime()
    print("\n" + "=" * 68)
    print("Fim. Para a suite de aceitação completa: python3 test_motor.py")
