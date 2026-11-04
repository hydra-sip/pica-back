-- Designación de árbitros (HU-203, PICA-236): cada árbitro del torneo con sus permisos y el estado de la
-- designación. Nace PENDIENTE; ACEPTADO o RECHAZADO lo pone el árbitro (HU-807), y solo uno aceptado arbitra.
-- Las filas que ya existían (las del seed) quedan aceptadas y con los dos permisos.
ALTER TABLE torneo_arbitro
    ADD COLUMN estado VARCHAR(20) NOT NULL DEFAULT 'ACEPTADO',
    ADD COLUMN carga_resultados BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN resuelve_objeciones BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN designado_en TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE torneo_arbitro ALTER COLUMN estado SET DEFAULT 'PENDIENTE';

ALTER TABLE torneo_arbitro
    ADD CONSTRAINT ck_torneo_arbitro_estado CHECK (estado IN ('PENDIENTE', 'ACEPTADO', 'RECHAZADO')),
    ADD CONSTRAINT ck_torneo_arbitro_con_permiso CHECK (carga_resultados OR resuelve_objeciones);
