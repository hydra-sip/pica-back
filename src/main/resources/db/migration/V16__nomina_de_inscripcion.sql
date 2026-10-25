-- Nómina de la inscripción de un equipo (PICA-208).
--
-- 1. El tamaño de equipo del torneo pasa a ser el mínimo de la nómina (los titulares). El máximo, con los
--    suplentes incluidos, es una columna nueva: obligatoria en los torneos por equipos, mayor o igual que el
--    mínimo, y vacía en los individuales. Los torneos por equipos que ya existían quedan con un máximo igual
--    al mínimo.
-- 2. La nómina es la relación entre una inscripción y los integrantes (usuario-equipo) que juegan ese torneo.
--    Cada inscripción tiene la suya, así que lo que después cambie en el equipo no la modifica: dar de baja a
--    un integrante es lógico y la fila de usuario_equipo sigue ahí.
--    - La FK compuesta a usuario_equipo asegura que solo entra un integrante del equipo.
--    - La FK compuesta a inscripcion (id, equipo_id) asegura que todos son del equipo que se inscribió, sin
--      trigger. Pide un UNIQUE (id, equipo_id) en inscripcion, que no agrega restricción: id ya es único.
ALTER TABLE torneo ADD COLUMN nomina_maxima INTEGER;

UPDATE torneo SET nomina_maxima = tamanio_equipo WHERE tipo_participante = 'EQUIPO';

ALTER TABLE torneo
    ADD CONSTRAINT ck_torneo_nomina_maxima CHECK (
        CASE tipo_participante
            WHEN 'EQUIPO' THEN nomina_maxima IS NOT NULL AND nomina_maxima >= tamanio_equipo
            ELSE nomina_maxima IS NULL END);

ALTER TABLE inscripcion
    ADD CONSTRAINT uq_inscripcion_id_equipo UNIQUE (id, equipo_id);

CREATE TABLE nomina (
    inscripcion_id BIGINT NOT NULL,
    usuario_id BIGINT NOT NULL,
    equipo_id BIGINT NOT NULL,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    creado_por VARCHAR(100),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_nomina PRIMARY KEY (usuario_id, equipo_id, inscripcion_id),
    CONSTRAINT fk_nomina_integrante FOREIGN KEY (usuario_id, equipo_id)
        REFERENCES usuario_equipo (usuario_id, equipo_id),
    CONSTRAINT fk_nomina_inscripcion FOREIGN KEY (inscripcion_id, equipo_id)
        REFERENCES inscripcion (id, equipo_id)
);

CREATE INDEX idx_nomina_inscripcion_id
    ON nomina (inscripcion_id);
