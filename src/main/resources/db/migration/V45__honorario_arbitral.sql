-- HU-604 (PICA-350): el honorario arbitral es un gasto previsto del torneo. El organizador lo deposita desde su
-- billetera en la custodia del torneo para un árbitro designado, y la liquidación se lo acredita al árbitro
-- (wireframe 49). El gasto apunta al depósito vigente; si se devuelve, vuelve a null.
ALTER TABLE gasto
    ADD COLUMN tipo VARCHAR(20) NOT NULL DEFAULT 'OTRO',
    ADD COLUMN movimiento_id BIGINT,
    ADD CONSTRAINT ck_gasto_tipo CHECK (tipo IN ('HONORARIO_ARBITRAL', 'OTRO')),
    ADD CONSTRAINT fk_gasto_movimiento FOREIGN KEY (movimiento_id) REFERENCES movimiento (id),
    ADD CONSTRAINT ck_gasto_deposito CHECK (movimiento_id IS NULL
        OR (tipo = 'HONORARIO_ARBITRAL' AND beneficiario_id IS NOT NULL));

-- Un gasto pagado siempre tiene comprobante: la referencia del depósito, o el de afuera en un gasto común, que no
-- tiene beneficiario en la plataforma (V19 pedía los dos).
ALTER TABLE gasto DROP CONSTRAINT ck_gasto_pagado;
ALTER TABLE gasto ADD CONSTRAINT ck_gasto_pagado CHECK (monto_abonado = 0 OR comprobante IS NOT NULL);

CREATE UNIQUE INDEX uq_gasto_movimiento ON gasto (movimiento_id) WHERE movimiento_id IS NOT NULL;

-- Lo que la liquidación le acredita al árbitro.
ALTER TABLE movimiento DROP CONSTRAINT ck_movimiento_tipo;
ALTER TABLE movimiento
    ADD CONSTRAINT ck_movimiento_tipo CHECK (tipo IN ('CARGA', 'INSCRIPCION', 'COMPRA_NUMERO', 'PREMIO', 'REINTEGRO',
                                                      'COMISION', 'RETIRO', 'PAGO_SERVICIO', 'LIQUIDACION',
                                                      'HONORARIO'));
