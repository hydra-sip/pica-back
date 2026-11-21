-- Retiro de saldo a la wallet (HU-610, PICA-455). Al pedirlo se descuenta el saldo con un movimiento RETIRO PENDIENTE
-- y el back firma un permiso para el contrato PasarelaRetiros: la referencia, la wallet, los wei y el vencimiento. El
-- usuario lo cobra desde esa wallet; con el cobro leído en la cadena pasa a COBRADO y el movimiento a CONFIRMADO. Si
-- vence sin cobrarse, pasa a DEVUELTO, el movimiento a RECHAZADO y el saldo vuelve a la billetera.
CREATE TABLE retiro_cripto (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    referencia CHAR(66) NOT NULL,
    usuario_id BIGINT NOT NULL,
    destino CHAR(42) NOT NULL,
    monto_pesos NUMERIC(14, 2) NOT NULL,
    monto_wei NUMERIC(78, 0) NOT NULL,
    vence_en TIMESTAMPTZ NOT NULL,
    firma VARCHAR(132) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    hash CHAR(66),
    movimiento_id BIGINT NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_retiro_cripto PRIMARY KEY (id),
    CONSTRAINT uq_retiro_cripto_referencia UNIQUE (referencia),
    CONSTRAINT uq_retiro_cripto_hash UNIQUE (hash),
    CONSTRAINT uq_retiro_cripto_movimiento UNIQUE (movimiento_id),
    CONSTRAINT fk_retiro_cripto_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_retiro_cripto_movimiento FOREIGN KEY (movimiento_id) REFERENCES movimiento (id),
    CONSTRAINT ck_retiro_cripto_estado CHECK (estado IN ('PENDIENTE', 'COBRADO', 'DEVUELTO')),
    CONSTRAINT ck_retiro_cripto_montos CHECK (monto_pesos > 0 AND monto_wei > 0),
    -- solo un retiro cobrado tiene la transacción que lo cobró (la devolución automática puede encontrarlo cobrado
    -- sin que el front haya mandado el hash: ahí queda COBRADO sin hash)
    CONSTRAINT ck_retiro_cripto_hash CHECK (hash IS NULL OR estado = 'COBRADO')
);

CREATE INDEX idx_retiro_cripto_usuario_id
    ON retiro_cripto (usuario_id);

-- la devolución automática busca los pendientes vencidos
CREATE INDEX idx_retiro_cripto_pendientes
    ON retiro_cripto (vence_en) WHERE estado = 'PENDIENTE';
