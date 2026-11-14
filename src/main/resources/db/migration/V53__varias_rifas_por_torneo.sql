-- CR-06 (PICA-387): un torneo puede tener varias rifas, cada una con su venta, su sello y su sorteo. Sale el índice
-- único de V30, que dejaba una sola viva por torneo; queda uno común para buscar las rifas de un torneo.
DROP INDEX uq_rifa_torneo_id;

CREATE INDEX idx_rifa_torneo_id ON rifa (torneo_id);
