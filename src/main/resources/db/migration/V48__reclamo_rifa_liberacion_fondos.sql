-- HU-707 (PICA-22, PICA-341): registro de la liberación de fondos congelados tras resolver un reclamo de rifa.
ALTER TABLE reclamo_rifa
    ADD COLUMN fondos_liberados_en TIMESTAMPTZ,
    ADD COLUMN fondos_liberados_por VARCHAR(100),
    ADD COLUMN fondos_liberados_monto NUMERIC(14,2);

ALTER TABLE reclamo_rifa
    ADD CONSTRAINT ck_reclamo_rifa_liberacion
        CHECK ((fondos_liberados_en IS NULL) = (fondos_liberados_por IS NULL)
            AND (fondos_liberados_en IS NULL) = (fondos_liberados_monto IS NULL)),
    ADD CONSTRAINT ck_reclamo_rifa_liberacion_monto
        CHECK (fondos_liberados_monto IS NULL OR fondos_liberados_monto >= 0);

