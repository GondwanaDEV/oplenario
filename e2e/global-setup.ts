import * as fs from "node:fs";
import * as path from "node:path";

// Task 2 — decisão de arquitetura (ver seed-1-brief no repo, "Onde isto se encaixa"): este
// globalSetup NÃO semeia. O globalSetup roda DENTRO do container mcr.microsoft.com/playwright, que
// não tem CLI do docker — não dá pra disparar os 3 containers efêmeros de seed daqui. Quem semeia é
// `semear.sh`, no HOST, ANTES do Playwright subir. Este arquivo só lê e valida o artefato que
// `semear.sh` já deixou em `.artifacts/demo-ids.edn` (escrito por `seed-demo/base` via o mount
// `.artifacts:/demo-scratch`), extrai o `:ente` e o normaliza para `.artifacts/ente.json` — o formato
// que `seed.ts` (e os specs) sabem ler.
const ARTIFACTS_DIR = path.join(__dirname, ".artifacts");
const DEMO_IDS_PATH = path.join(ARTIFACTS_DIR, "demo-ids.edn");
const ENTE_JSON_PATH = path.join(ARTIFACTS_DIR, "ente.json");

export default async function globalSetup(): Promise<void> {
  if (!fs.existsSync(DEMO_IDS_PATH)) {
    throw new Error(
      `Artefato de seed ausente: ${DEMO_IDS_PATH}\n` +
        "Rode ./semear.sh (ou ./rodar.sh, que já o chama) antes dos testes — o globalSetup não semeia sozinho.",
    );
  }

  const edn = fs.readFileSync(DEMO_IDS_PATH, "utf-8");
  // EDN do Clojure: {:ente #uuid "..." :ident #uuid "..." :sessao #uuid "..."}. Sem parser EDN (decisão
  // do brief — dependência desnecessária p/ 1 campo): um regex sobre a chave `:ente` basta e falha alto
  // se não casar, em vez de degradar silenciosamente.
  const match = edn.match(/:ente\s+#uuid\s+"([0-9a-fA-F-]{36})"/);
  if (!match) {
    throw new Error(`Não foi possível extrair ":ente #uuid ..." de ${DEMO_IDS_PATH}. Conteúdo lido: ${edn}`);
  }

  fs.writeFileSync(ENTE_JSON_PATH, JSON.stringify({ enteId: match[1] }, null, 2));
}
