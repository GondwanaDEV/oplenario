"use client";

// Hook da CAMPAINHA da tribuna (TV do plenário). Duas responsabilidades:
//  1. Liberar o áudio: navegadores só tocam som depois de um gesto do usuário NA PÁGINA (política de autoplay).
//     A TV abre numa janela nova, sem gesto — então o primeiro clique/tecla na tela (o mesmo clique do "Entrar
//     em tela cheia") retoma o AudioContext. Em quiosque com autoplay liberado, já nasce tocando.
//  2. Tocar na TRANSIÇÃO: guarda a observação anterior e decide com `deveTocarCampainha` (pura, testada) —
//     abrir a TV com a fala já estourada não toca; o "+1 min" seguido de novo esgotamento toca de novo.
// Sem Web Audio (navegador antigo, jsdom), vira no-op: a TV continua mostrando "tempo esgotado".

import { useCallback, useEffect, useRef, useState } from "react";
import { tocarCampainha } from "./campainha";
import { deveTocarCampainha, type ObservacaoTempo } from "./cronometro";

type CtorAudio = new () => AudioContext;

function construtorDeAudio(): CtorAudio | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as { AudioContext?: CtorAudio; webkitAudioContext?: CtorAudio };
  return w.AudioContext ?? w.webkitAudioContext ?? null;
}

export function useCampainha(atual: ObservacaoTempo | null): { somLiberado: boolean; liberarSom: () => void } {
  const ctxRef = useRef<AudioContext | null>(null);
  const anteriorRef = useRef<ObservacaoTempo | null>(null);
  const [somLiberado, setSomLiberado] = useState(false);

  const liberarSom = useCallback(() => {
    const Ctor = construtorDeAudio();
    if (!Ctor) return;
    try {
      if (!ctxRef.current) ctxRef.current = new Ctor();
      const ctx = ctxRef.current;
      void ctx
        .resume()
        .then(() => setSomLiberado(ctx.state === "running"))
        .catch(() => {});
    } catch {
      // navegador recusou criar o contexto: segue sem som
    }
  }, []);

  // Tenta já na montagem (quiosque com autoplay liberado) e escuta o primeiro gesto na tela.
  useEffect(() => {
    liberarSom();
    const onGesto = () => liberarSom();
    window.addEventListener("pointerdown", onGesto);
    window.addEventListener("keydown", onGesto);
    return () => {
      window.removeEventListener("pointerdown", onGesto);
      window.removeEventListener("keydown", onGesto);
    };
  }, [liberarSom]);

  const falaId = atual?.falaId ?? null;
  const esgotado = atual?.esgotado ?? false;
  useEffect(() => {
    const agora = falaId === null ? null : { falaId, esgotado };
    if (deveTocarCampainha(anteriorRef.current, agora)) {
      const ctx = ctxRef.current;
      if (ctx && ctx.state === "running") tocarCampainha(ctx);
    }
    anteriorRef.current = agora;
  }, [falaId, esgotado]);

  useEffect(() => () => void ctxRef.current?.close?.().catch(() => {}), []);

  return { somLiberado, liberarSom };
}
