-- Carga de saldo pagada con la wallet en Base Sepolia (HU-609, PICA-333). Se crea pendiente al iniciarla, con la
-- referencia que el usuario paga en el contrato PasarelaCargas y los wei que tiene que mandar según la cotización
-- fija; pasa a ACREDITADA cuando el back lee el pago en la cadena, y recién ahí nace su movimiento CARGA.
CREATE TABLE carga_cripto (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    referencia CHAR(66) NOT NULL,
    usuario_id BIGINT NOT NULL,
    monto_pesos NUMERIC(14, 2) NOT NULL,
    monto_wei NUMERIC(78, 0) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'PENDIENTE',
    hash CHAR(66),
    pagador CHAR(42),
    movimiento_id BIGINT,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_carga_cripto PRIMARY KEY (id),
    CONSTRAINT uq_carga_cripto_referencia UNIQUE (referencia),
    CONSTRAINT fk_carga_cripto_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_carga_cripto_movimiento FOREIGN KEY (movimiento_id) REFERENCES movimiento (id),
    CONSTRAINT ck_carga_cripto_estado CHECK (estado IN ('PENDIENTE', 'ACREDITADA')),
    CONSTRAINT ck_carga_cripto_montos CHECK (monto_pesos > 0 AND monto_wei > 0),
    -- acreditada si y solo si ya tiene la transacción que la pagó y su movimiento
    CONSTRAINT ck_carga_cripto_acreditada CHECK (
        (estado = 'ACREDITADA') = (hash IS NOT NULL AND pagador IS NOT NULL AND movimiento_id IS NOT NULL))
);

CREATE INDEX idx_carga_cripto_usuario_id
    ON carga_cripto (usuario_id);
