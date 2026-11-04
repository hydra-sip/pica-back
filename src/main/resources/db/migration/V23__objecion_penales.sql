-- El resultado que propone quien objeta (HU-302): en eliminación directa un empate se define por penales.
ALTER TABLE objecion
    ADD COLUMN penales_local INTEGER,
    ADD COLUMN penales_visitante INTEGER,
    ADD CONSTRAINT ck_objecion_penales CHECK (num_nulls(penales_local, penales_visitante) IN (0, 2)
        AND (penales_local IS NULL OR (penales_local >= 0 AND penales_visitante >= 0)));
