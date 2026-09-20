"use client";

// Painel "Atos disponíveis" da aba Tramitação (GAP docs/20 → tela): liga o endpoint que já existia
// (POST /legislativo/proposicoes/:id/tramitacao) à interface. Antes desta fatia a tramitação avançava só
// por API — o botão "Distribuir a comissão" da ficha era inerte. Aqui o operador vê o estado atual e os
// ATOS que o rito declara a partir dele, e dispara um com um clique.
//
// Fonte separada da ficha: os gatilhos vêm de GET .../tramitacao (não da ficha, que só traz o histórico).
// Depois de tramitar com sucesso o painel refaz o próprio GET (estado/atos mudam) e chama `onTramitou`
// para a ficha (cabeçalho/histórico) se atualizar também. `token` vem por prop (não useAuth) para o
// componente ser testável sem <AuthProvider>, como os irmãos da ficha.

import { useTramitacao } from "@/lib/use-tramitacao";
import { useTramitar } from "@/lib/use-tramitar";
import { derivarAcoesTramitacao } from "@/lib/tramitacao-acoes-vista";

export function AcoesTramitacao({
  proposicaoId,
  token = null,
  onTramitou,
}: {
  proposicaoId: string;
  token?: string | null;
  onTramitou?: () => void;
}) {
  const { dados, estado, recarregar } = useTramitacao(token, proposicaoId);
  const { tramitar, estado: estadoEnvio, erro } = useTramitar(token, proposicaoId);

  async function aoTramitar(gatilho: string) {
    try {
      await tramitar(gatilho);
      await recarregar();
      onTramitou?.();
    } catch {
      // erro já exposto via `erro` (useTramitar) — mostrado no alerta abaixo.
    }
  }

  if (estado === "carregando") {
    return (
      <div className="tram-acoes">
        <p role="status">Carregando atos…</p>
      </div>
    );
  }
  if (estado === "erro" || !dados) {
    return (
      <div className="tram-acoes">
        <p role="status">Não foi possível carregar os atos de tramitação.</p>
      </div>
    );
  }

  const vista = derivarAcoesTramitacao(dados);
  const enviando = estadoEnvio === "enviando";

  return (
    <div className="tram-acoes">
      <h4 className="tram-acoes-titulo">
        Atos disponíveis <span className="tram-estado">· estado atual: {vista.estadoAtual}</span>
      </h4>

      {vista.tipo === "sem-atos" ? (
        <p className="tram-nota" role="status">
          {vista.nota}
        </p>
      ) : (
        <>
          <div className="tram-botoes">
            {vista.atos.map((ato) => (
              <button
                key={ato.gatilho}
                type="button"
                className="btn btn-contorno"
                disabled={enviando}
                aria-disabled={enviando}
                title={
                  ato.exigeAutorizacao
                    ? "Pode exigir autorização"
                    : ato.condicional
                      ? "O rito pode recusar no disparo"
                      : undefined
                }
                onClick={() => aoTramitar(ato.gatilho)}
              >
                {ato.rotulo}
              </button>
            ))}
          </div>
          <p className="tram-aviso">
            Os atos podem ser recusados no disparo — o rito decide o destino e as condições.
          </p>
        </>
      )}

      {erro && (
        <p role="alert" className="campo-erro">
          {erro}
        </p>
      )}
    </div>
  );
}
