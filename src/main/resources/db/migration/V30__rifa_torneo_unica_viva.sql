-- Una rifa se puede eliminar (baja lógica, PICA-287) y su torneo queda libre para otra. uq_rifa_torneo_id (V9) contaba
-- también las eliminadas: pasa a un índice único que solo mira las vivas.
ALTER TABLE rifa DROP CONSTRAINT uq_rifa_torneo_id;

CREATE UNIQUE INDEX uq_rifa_torneo_id ON rifa (torneo_id) WHERE eliminado_en IS NULL;
