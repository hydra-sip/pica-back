-- Custodia de los fondos de cada evento (HU-603, PICA-321): lo cobrado por un torneo (inscripciones y sus gastos) o
-- por una rifa (números y sus gastos) queda retenido hasta que el evento termina, y solo sale por la liquidación
-- (HU-606) o el reintegro. Una fila por evento; lo retenido es la suma de sus movimientos, sin un acumulado aparte que
-- se pueda descuadrar. La rifa tiene su propia custodia aunque tenga torneo: se libera al vencer su plazo de reclamo.
CREATE TABLE custodia (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    torneo_id BIGINT,
    rifa_id BIGINT,
    estado VARCHAR(20) NOT NULL DEFAULT 'RETENIDA',
    liberada_en TIMESTAMPTZ,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_custodia PRIMARY KEY (id),
    CONSTRAINT fk_custodia_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_custodia_rifa FOREIGN KEY (rifa_id) REFERENCES rifa (id),
    CONSTRAINT uq_custodia_torneo_id UNIQUE (torneo_id),
    CONSTRAINT uq_custodia_rifa_id UNIQUE (rifa_id),
    CONSTRAINT ck_custodia_evento CHECK (num_nonnulls(torneo_id, rifa_id) = 1),
    CONSTRAINT ck_custodia_estado CHECK (estado IN ('RETENIDA', 'LIBERADA', 'REINTEGRADA')),
    CONSTRAINT ck_custodia_liberada CHECK ((estado = 'RETENIDA') = (liberada_en IS NULL))
);

-- El pago que entró a la custodia. Cargas, premios y retiros no tienen.
ALTER TABLE movimiento
    ADD COLUMN custodia_id BIGINT,
    ADD CONSTRAINT fk_movimiento_custodia FOREIGN KEY (custodia_id) REFERENCES custodia (id);

CREATE INDEX idx_movimiento_custodia_id
    ON movimiento (custodia_id);

-- Los pagos de antes (simulados y del seed) entran a la custodia de su evento, que sigue retenida: ninguno se liquidó.
-- Torneo: el pago y los gastos de cada inscripción.
INSERT INTO custodia (torneo_id, creado_por, modificado_por)
SELECT DISTINCT i.torneo_id, 'V39', 'V39'
FROM movimiento m
JOIN inscripcion i ON i.id = m.inscripcion_id
WHERE m.tipo IN ('INSCRIPCION', 'COMISION');

UPDATE movimiento m
SET custodia_id = c.id
FROM inscripcion i
JOIN custodia c ON c.torneo_id = i.torneo_id
WHERE m.inscripcion_id = i.id
  AND m.tipo IN ('INSCRIPCION', 'COMISION');

-- Rifa: el pago de cada ticket y sus gastos, que se guardaron con la referencia gastos-compra-<id del pago>.
INSERT INTO custodia (rifa_id, creado_por, modificado_por)
SELECT DISTINCT t.rifa_id, 'V39', 'V39'
FROM ticket t;

UPDATE movimiento m
SET custodia_id = c.id
FROM ticket t
JOIN custodia c ON c.rifa_id = t.rifa_id
WHERE m.id = t.movimiento_id
   OR (m.tipo = 'COMISION' AND m.inscripcion_id IS NULL
       AND m.referencia_externa IN ('sim-gastos-compra-' || t.movimiento_id, 'pago-gastos-compra-' || t.movimiento_id));
