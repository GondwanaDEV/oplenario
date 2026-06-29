import Link from "next/link";

// Home provisória do track FE: a 1ª fatia portada é o painel do plenário ao vivo. As demais telas
// (login, editor, portal) entram por fatia vertical conforme as rotas do backend chegam (docs/11 Track FE).
export default function Home() {
  return (
    <main className="envelope" style={{ maxWidth: 640, padding: "4rem 1.5rem" }}>
      <p className="eyebrow">O Plenário</p>
      <h1 style={{ fontFamily: "var(--display)", fontSize: "var(--t-40)", lineHeight: 1.1, margin: "0.4rem 0 1rem", color: "var(--texto)" }}>
        Onde a câmara acontece.
      </h1>
      <p style={{ color: "var(--texto-2)", maxWidth: "46ch" }}>
        Front-end em construção (track FE). A primeira fatia portada é o <b>painel do plenário ao vivo</b>:
        acesse <code className="mono">/sessoes/&lt;id&gt;/plenario?token=&lt;claims&gt;</code>.
      </p>
      <p style={{ marginTop: "1.5rem" }}>
        <Link className="btn btn-primaria" href="/sessoes/exemplo/plenario">
          Abrir painel de exemplo
        </Link>
      </p>
    </main>
  );
}
