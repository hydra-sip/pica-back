-- Un gasto se carga previsto en el paso 3 del asistente (HU-601, PICA-203): descripción y monto, que se descuentan
-- del pozo de competidores. Quién lo cobra, cuánto se le pagó y el comprobante se completan al pagarlo, en la
-- liquidación; hasta entonces lo abonado es 0. Es V19 porque V17 es la de publicar la rifa y V18 la del borrador
-- de torneo.
ALTER TABLE gasto
    ALTER COLUMN beneficiario_id DROP NOT NULL,
    ALTER COLUMN comprobante DROP NOT NULL,
    ALTER COLUMN monto_abonado SET DEFAULT 0;

ALTER TABLE gasto
    ADD CONSTRAINT ck_gasto_pagado CHECK (
        monto_abonado = 0 OR (beneficiario_id IS NOT NULL AND comprobante IS NOT NULL));
