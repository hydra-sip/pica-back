-- CR-02 (PICA-396): fase de grupos antes de la llave. El formato nuevo trae sus reglas, que elige el organizador y
-- quedan selladas al publicar: tamaño del grupo, cuántos pasan y los puntos. En eliminación directa van en null.
ALTER TABLE torneo DROP CONSTRAINT ck_torneo_formato;
ALTER TABLE torneo ADD CONSTRAINT ck_torneo_formato
    CHECK (formato IN ('ELIMINACION_DIRECTA', 'GRUPOS_Y_ELIMINACION'));

ALTER TABLE torneo
    ADD COLUMN tamanio_grupo INTEGER,
    ADD COLUMN clasifican_por_grupo INTEGER,
    ADD COLUMN puntos_victoria INTEGER,
    ADD COLUMN puntos_empate INTEGER;

ALTER TABLE torneo ADD CONSTRAINT ck_torneo_reglas_grupos CHECK (
    (formato = 'ELIMINACION_DIRECTA' AND tamanio_grupo IS NULL AND clasifican_por_grupo IS NULL
        AND puntos_victoria IS NULL AND puntos_empate IS NULL)
    OR (formato = 'GRUPOS_Y_ELIMINACION'
        -- sin el IS NOT NULL, un null deja el CHECK en null y Postgres lo acepta
        AND tamanio_grupo IS NOT NULL AND clasifican_por_grupo IS NOT NULL
        AND puntos_victoria IS NOT NULL AND puntos_empate IS NOT NULL
        AND tamanio_grupo BETWEEN 3 AND 8
        AND clasifican_por_grupo BETWEEN 1 AND tamanio_grupo - 1
        AND puntos_victoria BETWEEN 1 AND 10
        AND puntos_empate >= 0 AND puntos_empate < puntos_victoria));

COMMENT ON COLUMN torneo.tamanio_grupo IS 'Grupos y eliminación: integrantes por grupo con el cupo lleno (3 a 8)';
COMMENT ON COLUMN torneo.clasifican_por_grupo IS 'Grupos y eliminación: cuántos de cada grupo pasan a la llave';
COMMENT ON COLUMN torneo.puntos_victoria IS 'Grupos y eliminación: puntos por partido ganado';
COMMENT ON COLUMN torneo.puntos_empate IS 'Grupos y eliminación: puntos por partido empatado (perder da 0)';

-- Un grupo por letra; (id, torneo_id) único para que las FK compuestas obliguen a que todo sea del mismo torneo.
CREATE TABLE grupo (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    torneo_id BIGINT NOT NULL,
    letra CHAR(1) NOT NULL,
    CONSTRAINT pk_grupo PRIMARY KEY (id),
    CONSTRAINT fk_grupo_torneo FOREIGN KEY (torneo_id) REFERENCES torneo (id),
    CONSTRAINT uq_grupo_torneo_letra UNIQUE (torneo_id, letra),
    CONSTRAINT uq_grupo_id_torneo UNIQUE (id, torneo_id),
    CONSTRAINT ck_grupo_letra CHECK (letra BETWEEN 'A' AND 'Z')
);

ALTER TABLE inscripcion ADD CONSTRAINT uq_inscripcion_id_torneo UNIQUE (id, torneo_id);

-- Cada inscripción en un solo grupo, del mismo torneo.
CREATE TABLE grupo_inscripcion (
    grupo_id BIGINT NOT NULL,
    inscripcion_id BIGINT NOT NULL,
    torneo_id BIGINT NOT NULL,
    CONSTRAINT pk_grupo_inscripcion PRIMARY KEY (grupo_id, inscripcion_id),
    CONSTRAINT uq_grupo_inscripcion_inscripcion UNIQUE (inscripcion_id),
    CONSTRAINT fk_grupo_inscripcion_grupo FOREIGN KEY (grupo_id, torneo_id) REFERENCES grupo (id, torneo_id),
    CONSTRAINT fk_grupo_inscripcion_inscripcion FOREIGN KEY (inscripcion_id, torneo_id)
        REFERENCES inscripcion (id, torneo_id)
);

-- La fase del partido: los de grupos y los de desempate son de un grupo; los de la llave, no. En grupos la ronda es la
-- fecha del grupo; en un desempate, la ronda de su mini cuadro.
ALTER TABLE partido
    ADD COLUMN fase VARCHAR(20) NOT NULL DEFAULT 'ELIMINACION',
    ADD COLUMN grupo_id BIGINT;

ALTER TABLE partido
    ADD CONSTRAINT ck_partido_fase CHECK (fase IN ('GRUPOS', 'DESEMPATE', 'ELIMINACION')),
    ADD CONSTRAINT ck_partido_fase_grupo CHECK ((fase = 'ELIMINACION') = (grupo_id IS NULL)),
    ADD CONSTRAINT fk_partido_grupo FOREIGN KEY (grupo_id, torneo_id) REFERENCES grupo (id, torneo_id);

-- La llave sigue siendo una por torneo; cada grupo tiene sus fechas y su mini cuadro de desempate.
ALTER TABLE partido DROP CONSTRAINT uq_partido_torneo_ronda_posicion;
CREATE UNIQUE INDEX uq_partido_llave ON partido (torneo_id, ronda, posicion) WHERE fase = 'ELIMINACION';
CREATE UNIQUE INDEX uq_partido_grupo ON partido (grupo_id, fase, ronda, posicion) WHERE fase <> 'ELIMINACION';
CREATE INDEX idx_partido_grupo_id ON partido (grupo_id);
