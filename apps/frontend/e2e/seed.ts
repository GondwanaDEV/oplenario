import * as fs from "node:fs";
import * as path from "node:path";

// Task 2: helper de leitura do artefato normalizado pelo `global-setup.ts` — os specs só conhecem
// `ente.json` (nunca leem o EDN cru do seed nem parseiam `demo-ids.edn` diretamente).
const ENTE_JSON_PATH = path.join(__dirname, ".artifacts", "ente.json");

/**
 * Lê o `ente_id` semeado por `seed_demo.clj` (via `semear.sh`) e normalizado por `global-setup.ts`
 * em `.artifacts/ente.json`. Lança erro claro se o artefato estiver ausente ou malformado — nada de
 * fallback silencioso (o mesmo racional do guard do `global-setup.ts`).
 */
export function lerEnteId(): string {
  if (!fs.existsSync(ENTE_JSON_PATH)) {
    throw new Error(
      `Artefato ausente: ${ENTE_JSON_PATH}\n` +
        "O globalSetup deveria tê-lo escrito a partir do seed. Rode ./rodar.sh (ou ./semear.sh " +
        "seguido dos testes) — não rode os specs sem semear antes.",
    );
  }
  const conteudo = JSON.parse(fs.readFileSync(ENTE_JSON_PATH, "utf-8"));
  const { enteId } = conteudo;
  if (typeof enteId !== "string" || enteId.length === 0) {
    throw new Error(`ente.json malformado (sem "enteId" string): ${ENTE_JSON_PATH}`);
  }
  return enteId;
}
