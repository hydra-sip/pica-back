-- La respuesta del árbitro a la designación (HU-807, PICA-313; wireframes 48 y 49): cuándo respondió y, si rechazó,
-- el motivo (opcional), que el organizador ve en el paso 2. Aceptar exige el código de imparcialidad, y
-- respondido_en de una ACEPTADO es cuándo lo aceptó. Las filas que ya existían quedan sin respuesta registrada.
ALTER TABLE torneo_arbitro
    ADD COLUMN respondido_en TIMESTAMPTZ,
    ADD COLUMN motivo_rechazo VARCHAR(500),
    ADD CONSTRAINT ck_torneo_arbitro_motivo_solo_rechazo CHECK (motivo_rechazo IS NULL OR estado = 'RECHAZADO');
