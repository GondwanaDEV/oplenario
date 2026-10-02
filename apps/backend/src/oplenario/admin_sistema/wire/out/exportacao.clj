(ns oplenario.admin-sistema.wire.out.exportacao
  "Representacao externa (wire) da EXPORTACAO completa da Casa (ADR-0018 fatia 2, 9.6). E' METADADO — estado, quando,
  tamanho, hash, o manifesto RESUMIDO —, igual para o console do operador e para o administrador da Casa. O conteudo so'
  sai pelo download, e o download e' so' do administrador da Casa. A chave no object storage nao sai.")

(def ^:private Instante [:maybe :string])

(def ExportacaoOut
  [:map {:closed true}
   [:id :string]
   [:estado [:enum "gerando" "pronta" "falhou"]]
   [:solicitada-em Instante]
   [:solicitada-por [:enum "operador" "admin_ente"]]
   [:concluida-em Instante]
   [:sha256 [:maybe :string]]
   [:bytes [:maybe :int]]
   [:manifesto [:maybe [:map-of :keyword :any]]]
   [:erro [:maybe :string]]
   [:confirmada-em Instante]
   [:confirmada-por [:maybe [:enum "admin_ente" "oficio"]]]
   [:oficio [:maybe :string]]])

(def ExportacoesDaCasaOut
  "O bloco \"Exportar os dados da Camara\" do administrador: se existe nesta instalacao, se o encerramento esta' em curso
  (a confirmacao abre a contagem dos 90 dias) e as exportacoes, mais recente primeiro."
  [:map {:closed true}
   [:disponivel :boolean]
   [:em-encerramento :boolean]
   [:exportacoes [:vector ExportacaoOut]]])
