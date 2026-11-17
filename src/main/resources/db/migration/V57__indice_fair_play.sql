-- Índice de fair play (HU-802, T802-1): cada usuario arranca en FAIR_PLAY_INICIAL; un check-in cumplido suma
-- FAIR_PLAY_CHECKIN hasta FAIR_PLAY_MAXIMO, una ausencia resta FAIR_PLAY_AUSENCIA y una sanción (HU-803) resta según su
-- gravedad, sin bajar de 0. Se mueve solo, con cada evento, y cada movimiento queda en el historial con lo que lo movió.
-- Sin fila en fair_play, el usuario tiene el valor inicial.

INSERT INTO parametro_plataforma (clave, valor, descripcion, modificado_por)
VALUES ('FAIR_PLAY_INICIAL', 100, 'Índice de fair play con el que arranca cada jugador', 'SISTEMA'),
       ('FAIR_PLAY_MAXIMO', 100, 'Índice de fair play máximo: los check-ins no lo pasan', 'SISTEMA'),
       ('FAIR_PLAY_CHECKIN', 1, 'Puntos de fair play que suma cada check-in cumplido', 'SISTEMA'),
       ('FAIR_PLAY_AUSENCIA', 10, 'Puntos de fair play que resta cada ausencia sin aviso', 'SISTEMA'),
       ('FAIR_PLAY_SANCION_LEVE', 5, 'Puntos de fair play que resta una sanción leve', 'SISTEMA'),
       ('FAIR_PLAY_SANCION_MEDIA', 15, 'Puntos de fair play que resta una sanción media', 'SISTEMA'),
       ('FAIR_PLAY_SANCION_GRAVE', 30, 'Puntos de fair play que resta una sanción grave', 'SISTEMA');

ALTER TABLE parametro_plataforma
    ADD CONSTRAINT ck_parametro_plataforma_fair_play
        CHECK (clave NOT LIKE 'FAIR\_PLAY\_%' OR (valor >= 0 AND valor <= 1000 AND valor = trunc(valor)));

CREATE TABLE fair_play (
    usuario_id BIGINT NOT NULL,
    valor INTEGER NOT NULL,
    actualizado_en TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_fair_play PRIMARY KEY (usuario_id),
    CONSTRAINT fk_fair_play_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT ck_fair_play_valor CHECK (valor >= 0)
);

CREATE TABLE fair_play_evento (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    usuario_id BIGINT NOT NULL,
    tipo VARCHAR(10) NOT NULL,
    delta INTEGER NOT NULL,
    valor_resultante INTEGER NOT NULL,
    torneo_id BIGINT NOT NULL,
    partido_id BIGINT,
    sancion_id BIGINT,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_fair_play_evento PRIMARY KEY (id),
    CONSTRAINT fk_fair_play_evento_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT fk_fair_play_evento_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT fk_fair_play_evento_partido FOREIGN KEY (partido_id) REFERENCES partido (id),
    CONSTRAINT fk_fair_play_evento_sancion FOREIGN KEY (sancion_id) REFERENCES sancion (id),
    -- una sanción mueve el índice una sola vez
    CONSTRAINT uq_fair_play_evento_sancion UNIQUE (sancion_id),
    CONSTRAINT ck_fair_play_evento_tipo CHECK (tipo IN ('CHECKIN', 'AUSENCIA', 'SANCION')),
    CONSTRAINT ck_fair_play_evento_origen CHECK (
        CASE tipo WHEN 'SANCION' THEN sancion_id IS NOT NULL ELSE partido_id IS NOT NULL AND sancion_id IS NULL END),
    CONSTRAINT ck_fair_play_evento_valor CHECK (valor_resultante >= 0)
);

CREATE INDEX idx_fair_play_evento_usuario ON fair_play_evento (usuario_id, creado_en DESC, id DESC);
