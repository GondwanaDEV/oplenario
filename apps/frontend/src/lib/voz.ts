// A VOZ da leitura da ata (Faixa A / A.7). Provisória: usa a síntese de voz do PRÓPRIO NAVEGADOR da Mesa (Web Speech
// API) — nada sai da Câmara, sem custo. A voz definitiva (fornecedor ou própria) é decisão pendente do Daouda; trocá-la
// é trocar este módulo, o ato registrado ("voz_sintetizada") não muda.
//
// Lê trecho a trecho (cada frase é uma fala curta): os navegadores cortam falas longas, e trecho a trecho a tela
// consegue destacar onde está a leitura.

export type Trecho = { paragrafo: number; texto: string };

export interface Controle {
  pausar(): void;
  retomar(): void;
  parar(): void;
}

export interface Leitor {
  /** A voz em português escolhida, ou null (sem voz pt instalada / sem síntese no navegador). */
  voz(): string | null;
  ler(trechos: Trecho[], ao: { trecho: (i: number) => void; fim: () => void; erro: () => void }): Controle;
}

type Sintese = Pick<SpeechSynthesis, "getVoices" | "speak" | "cancel" | "pause" | "resume">;
type FabricaFala = (texto: string) => SpeechSynthesisUtterance;

/** A voz em português preferida: pt-BR antes de pt-PT antes de qualquer pt. */
export function escolherVoz(vozes: Pick<SpeechSynthesisVoice, "lang" | "name">[]): string | null {
  const pt = vozes.filter((v) => v.lang.toLowerCase().startsWith("pt"));
  const br = pt.find((v) => v.lang.toLowerCase().replace("_", "-") === "pt-br");
  return (br ?? pt[0])?.name ?? null;
}

export function leitorDoNavegador(
  sintese: Sintese | undefined = typeof window !== "undefined" ? window.speechSynthesis : undefined,
  fala: FabricaFala = (t) => new SpeechSynthesisUtterance(t),
): Leitor {
  const vozObj = () => {
    const vozes = sintese?.getVoices() ?? [];
    const nome = escolherVoz(vozes);
    return vozes.find((v) => v.name === nome) ?? null;
  };
  return {
    voz: () => vozObj()?.name ?? null,
    ler(trechos, ao) {
      let parado = false;
      const voz = vozObj();
      const falar = (i: number) => {
        if (parado || !sintese) return;
        if (i >= trechos.length) return ao.fim();
        const u = fala(trechos[i].texto);
        u.lang = voz?.lang ?? "pt-BR";
        if (voz) u.voice = voz;
        u.onstart = () => ao.trecho(i);
        u.onend = () => falar(i + 1);
        u.onerror = (e) => {
          // cancelar (parar) também dispara erro "interrupted"/"canceled": não é falha
          if (!parado && !["interrupted", "canceled"].includes((e as SpeechSynthesisErrorEvent).error)) ao.erro();
        };
        sintese.speak(u);
      };
      sintese?.cancel();
      falar(0);
      return {
        pausar: () => sintese?.pause(),
        retomar: () => sintese?.resume(),
        parar: () => {
          parado = true;
          sintese?.cancel();
        },
      };
    },
  };
}
