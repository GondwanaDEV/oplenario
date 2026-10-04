import { defineConfig, mergeConfig } from "vitest/config";
import base from "./vitest.config";

// A suíte de sempre, com mocks e pintura atrasados — ver o porquê e como ler o resultado em vitest.atraso.setup.ts.
// Uso (dentro do container): `npm run test:atraso` ou `npm run test:atraso -- <arquivo>`.
export default mergeConfig(base, defineConfig({ test: { setupFiles: ["./vitest.atraso.setup.ts"] } }));
