-- Perfil público e índice del organizador (HU-806, T806-1): cada organizador arranca en INDICE_ORGANIZADOR_INICIAL;
-- cada cancelación resuelta resta INDICE_ORGANIZADOR_CANCELACION (sin gastos) o
-- INDICE_ORGANIZADOR_CANCELACION_CON_GASTOS (con gastos), sin bajar de 0.

INSERT INTO parametro_plataforma (clave, valor, descripcion, modificado_por)
VALUES ('INDICE_ORGANIZADOR_INICIAL', 100, 'Índice con el que arranca cada organizador', 'SISTEMA'),
       ('INDICE_ORGANIZADOR_CANCELACION', 15, 'Puntos que resta al índice del organizador cancelar un torneo sin gastos', 'SISTEMA'),
       ('INDICE_ORGANIZADOR_CANCELACION_CON_GASTOS', 30, 'Puntos que resta al índice del organizador cancelar un torneo con gastos', 'SISTEMA');

ALTER TABLE parametro_plataforma
    ADD CONSTRAINT ck_parametro_plataforma_indice_organizador
        CHECK (clave NOT LIKE 'INDICE\_ORGANIZADOR\_%' OR (valor >= 0 AND valor <= 1000 AND valor = trunc(valor)));

CREATE TABLE indice_organizador (
    usuario_id BIGINT NOT NULL,
    valor INTEGER NOT NULL,
    actualizado_en TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_indice_organizador PRIMARY KEY (usuario_id),
    CONSTRAINT fk_indice_organizador_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_indice_organizador_valor CHECK (valor >= 0)
);

CREATE TABLE indice_organizador_evento (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    usuario_id BIGINT NOT NULL,
    torneo_id BIGINT NOT NULL,
    con_gastos BOOLEAN NOT NULL,
    delta INTEGER NOT NULL,
    valor_resultante INTEGER NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_indice_organizador_evento PRIMARY KEY (id),
    CONSTRAINT fk_indice_organizador_evento_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_indice_organizador_evento_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT uq_indice_organizador_evento_torneo UNIQUE (torneo_id),
    CONSTRAINT ck_indice_organizador_evento_valor CHECK (valor_resultante >= 0)
);

CREATE INDEX idx_indice_organizador_evento_usuario ON indice_organizador_evento (usuario_id, creado_en DESC, id DESC);
