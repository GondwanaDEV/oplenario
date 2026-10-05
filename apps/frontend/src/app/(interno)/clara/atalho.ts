// O atalho que abre e recolhe a Clara de qualquer tela: Ctrl + / e, no Mac, ⌘ + / (é a tecla de comando que a mão
// procura lá; só Ctrl deixava o atalho mudo para quem usa Mac). A barra do teclado numérico também serve. O "/" é
// conferido pelo caractere (`key`), não pela posição física (`code`): em outros layouts a tecla da posição "Slash" é
// outra, e o Ctrl + - do layout alemão (afastar o zoom) seria sequestrado.

import { useSyncExternalStore } from "react";

type TeclaPressionada = Pick<KeyboardEvent, "key" | "code" | "ctrlKey" | "metaKey">;

export function ehAtalhoDaClara(e: TeclaPressionada): boolean {
  return (e.ctrlKey || e.metaKey) && (e.key === "/" || e.code === "NumpadDivide");
}

function ehMac(): boolean {
  return /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
}

const nadaMuda = () => () => {};

/** O que o botão diz: "⌘ /" no Mac, "Ctrl /" no resto. No servidor (e na hidratação) vale "Ctrl /"; o Mac troca na
 *  primeira pintura do cliente, sem divergência de hidratação. */
export function useAtalhoDaClara(): { tecla: string; titulo: string } {
  const mac = useSyncExternalStore(nadaMuda, ehMac, () => false);
  return mac
    ? { tecla: "⌘ /", titulo: "Pergunte à Clara (⌘ + /)" }
    : { tecla: "Ctrl /", titulo: "Pergunte à Clara (Ctrl + /)" };
}
