"use client";

// A moldura dos layouts interno e do app do vereador: envolve a página (o que a Clara empurra quando aberta) e põe a
// Clara ao lado para quem pode perguntar — a secretaria, os vereadores e, só para consultar, o jurídico, o controle
// interno e a administração (o core confere de novo em POST /agente/perguntas). A div existe SEMPRE, com ou sem a
// Clara: trocar o elemento quando os papéis chegam remontaria a página inteira. Também guarda a dica da tela
// (`useDicaDaClara`), que a página publica e a Clara mostra, e o controle para a página abrir a Clara
// (`useAbrirClara`): a aba do app do vereador, ou `?clara=expandida` na URL (o endereço antigo /vereador/assistente).

import { createContext, useCallback, useContext, useEffect, useId, useMemo, useRef, useSyncExternalStore } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { useAuth, usePapeis } from "@/lib/auth";
import { Clara, conjuntoDaClara, soAuditor, type ControleDaClara } from "./clara";
import { ProvedorDaDica } from "./dica";

/** Onde a Clara não aparece: a tela cheia do assistente da secretaria. */
export const ROTAS_SEM_CLARA = new Set(["/assistente"]);

/** Quem pergunta: secretaria e vereador (perguntam e recebem propostas) e quem só consulta (jurídico, controle
 *  interno e administração da Casa). */
const PAPEIS_DA_CLARA = ["secretario", "vereador", "juridico", "auditor", "admin_ente"];

export function podeUsarAClara(papeis: string[]): boolean {
  return papeis.some((p) => PAPEIS_DA_CLARA.includes(p));
}

/** O que a página usa para abrir a Clara. `disponivel` é falso quando a Clara não está na tela (sem papel, papéis
 *  carregando, tela cheia do assistente): quem a abre não aparece. */
export type AbrirClara = {
  disponivel: boolean;
  /** A Clara está aberta (janela, expandida ou folha): o `aria-expanded` de quem a abre. */
  aberta: boolean;
  painelId: string | undefined;
  /** `origem`: o elemento que recebe o foco de volta quando a Clara recolhe. */
  abrir: (tamanho: "aberto" | "expandido", origem?: HTMLElement | null) => void;
};

const ContextoDaClara = createContext<Omit<AbrirClara, "aberta">>({
  disponivel: false,
  painelId: undefined,
  abrir: () => {},
});

// O tamanho da Clara já mora no <html data-clara> (é o que o CSS usa): quem a abre lê dali.
function assinarTamanho(avisar: () => void): () => void {
  if (typeof MutationObserver === "undefined") return () => {};
  const observador = new MutationObserver(avisar);
  observador.observe(document.documentElement, { attributes: true, attributeFilter: ["data-clara"] });
  return () => observador.disconnect();
}

export function useAbrirClara(): AbrirClara {
  const contexto = useContext(ContextoDaClara);
  const aberta = useSyncExternalStore(
    assinarTamanho,
    () => {
      const t = document.documentElement.dataset.clara;
      return t === "aberto" || t === "expandido";
    },
    () => false,
  );
  return { ...contexto, aberta: contexto.disponivel && aberta };
}

const PEDIDOS: Record<string, "aberto" | "expandido"> = { aberta: "aberto", expandida: "expandido" };

/** `publico`: o app do vereador pede sempre o conjunto do vereador (quem também é secretaria escolhe pela tela onde
 *  está); sem ele, o core escolhe pelos papéis (secretaria > vereador > consulta). */
export function MolduraDaClara({ children, publico }: { children: React.ReactNode; publico?: "vereador" }) {
  const { token } = useAuth();
  const { papeis, estado } = usePapeis();
  const caminho = usePathname() ?? "";
  const pedido = PEDIDOS[useSearchParams()?.get("clara") ?? ""];
  const moldura = useRef<HTMLDivElement>(null);
  const controle = useRef<ControleDaClara>(null);
  const painelId = useId();
  const ativa = estado === "pronto" && podeUsarAClara(papeis) && !ROTAS_SEM_CLARA.has(caminho);

  const abrir = useCallback((t: "aberto" | "expandido", origem?: HTMLElement | null) => {
    controle.current?.abrir(t, origem);
  }, []);
  const contexto = useMemo(() => ({ disponivel: ativa, painelId: ativa ? painelId : undefined, abrir }), [ativa, painelId, abrir]);

  // `?clara=expandida` (ou `aberta`) na URL abre a Clara assim que ela está na tela, e sai da URL: recarregar não
  // abre de novo, e o endereço que fica é o da página.
  useEffect(() => {
    if (!ativa || !pedido) return;
    controle.current?.abrir(pedido);
    const url = new URL(window.location.href);
    url.searchParams.delete("clara");
    window.history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
  }, [ativa, pedido]);

  return (
    <ProvedorDaDica>
      <ContextoDaClara.Provider value={contexto}>
        <div ref={moldura} className="clara-moldura" data-clara-ativa={ativa ? "" : undefined}>
          {children}
        </div>
        {ativa && (
          <Clara
            token={token}
            publico={publico}
            conjunto={conjuntoDaClara(papeis, publico)}
            auditorSo={soAuditor(papeis)}
            moldura={moldura}
            controle={controle}
            painelId={painelId}
          />
        )}
      </ContextoDaClara.Provider>
    </ProvedorDaDica>
  );
}
