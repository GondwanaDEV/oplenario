"use client";

// "Modo TV" (docs/22): abre a TV da sessão numa JANELA nova (não aba), para o operador arrastá-la até a TV do
// plenário (HDMI em tela estendida) e clicar uma vez em "Entrar em tela cheia". A janela herda a sessão do
// operador (cookie same-origin); em dev, `comToken` repassa o `?token=`. Reusa `.tema-btn` do chassi — o
// mesmo botão do topo das telas de sessão, sem CSS novo.

import { comToken } from "@/lib/nav";

export function BotaoModoTv({ sessaoId, token }: { sessaoId: string; token: string | null }) {
  function abrir() {
    // `noopener`: a TV não ganha referência a esta janela. Tamanho explícito = janela separada (popup),
    // que dá para arrastar até o outro monitor; sem ele a maioria dos navegadores abre só uma aba.
    window.open(comToken(`/sessoes/${encodeURIComponent(sessaoId)}/tv`, token), "oplenario-tv", "noopener,width=1280,height=720");
  }
  return (
    <button className="tema-btn" type="button" onClick={abrir} title="Abrir a TV da sessão para o público (tela cheia)">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <rect x="2.5" y="4" width="19" height="13" rx="2" />
        <path d="M8 21h8M12 17v4" />
      </svg>
      <span className="tema-rotulo">Modo TV</span>
    </button>
  );
}
