-- CR-06 (PICA-386): nadie gana dos veces en el mismo sorteo. La versión 2 del sorteo sella con cada número el comprador
-- (una etiqueta anónima por rifa) y saltea a quien ya ganó; la 1 es la de antes. Se fija al sellar la lista: las rifas
-- ya selladas siguen con la 1, así su lista publicada y su sorteo se pueden seguir recalculando.
ALTER TABLE rifa ADD COLUMN version_sorteo SMALLINT;

UPDATE rifa SET version_sorteo = 1 WHERE lista_sellada_en IS NOT NULL;

ALTER TABLE rifa
    ADD CONSTRAINT ck_rifa_version_sorteo CHECK (version_sorteo IN (1, 2)),
    ADD CONSTRAINT ck_rifa_version_sellada CHECK ((lista_sellada_en IS NULL) = (version_sorteo IS NULL));
