(ns oplenario.compliance.gerador-remessa)

;; o "renderizador proprio" (§22.7.8 dec. 2b/D1): le o descritor declarativo de layout (dado, reusa o registry como fonte),
;; busca proveniencia via read-ports em lote, chama o port SerializadorRemessa e grava remessa_gerada (append-only) + binario no objeto_store.
;; Layout FISICO do SIM = [GAP] de conteudo regulatorio — nao inventado.
