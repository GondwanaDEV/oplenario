"use client";

// Tema claro/escuro — porte React do design-system/sistema/tema.js. O CSS é dirigido por [data-tema] no
// <html>; aqui guardamos a escolha em localStorage('oplenario-tema') e respeitamos prefers-color-scheme.
// O valor inicial JÁ foi posto no <html> por um script anti-FOUC no layout (antes da hidratação) — este
// provider apenas LÊ o atributo no mount e sincroniza dali em diante.

import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

export type Tema = "claro" | "escuro";

const TemaCtx = createContext<{ tema: Tema; alternar: () => void } | null>(null);

export function TemaProvider({ children }: { children: ReactNode }) {
  const [tema, setTema] = useState<Tema>("claro");

  useEffect(() => {
    // Sync único no mount: lê o que o script anti-FOUC já pôs no <html>. Precisa ser no effect (não em
    // inicializador lazy) p/ casar com a hidratação — o SSR renderiza "claro"; corrigir aqui evita o
    // mismatch de hidratação que um initializer lendo o DOM causaria.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setTema((document.documentElement.getAttribute("data-tema") as Tema) || "claro");
  }, []);

  // Os efeitos colaterais (DOM + localStorage) vivem no HANDLER, não no updater do setState — updater deve
  // ser puro (React 19 Strict Mode invoca-o 2× p/ surfar impurezas). Lê o estado atual do <html> (fonte de
  // verdade do anti-FOUC) p/ evitar divergência, escreve, e só então atualiza o estado React (puro).
  const alternar = () => {
    const prox: Tema = document.documentElement.getAttribute("data-tema") === "escuro" ? "claro" : "escuro";
    document.documentElement.setAttribute("data-tema", prox);
    try {
      localStorage.setItem("oplenario-tema", prox);
    } catch {
      /* localStorage indisponível (modo privado) — só não persiste */
    }
    setTema(prox);
  };

  return <TemaCtx.Provider value={{ tema, alternar }}>{children}</TemaCtx.Provider>;
}

export function useTema() {
  const ctx = useContext(TemaCtx);
  if (!ctx) throw new Error("useTema fora de TemaProvider");
  return ctx;
}

/** Script anti-FOUC: aplica data-tema ANTES da pintura (escolha salva ou preferência do SO). Inline no <head>. */
export const SCRIPT_TEMA_INICIAL = `(function(){try{var s=localStorage.getItem('oplenario-tema');var t=s||(window.matchMedia('(prefers-color-scheme: dark)').matches?'escuro':'claro');document.documentElement.setAttribute('data-tema',t);}catch(e){document.documentElement.setAttribute('data-tema','claro');}})();`;
