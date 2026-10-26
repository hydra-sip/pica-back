-- Quién cargó el resultado del partido (HU-301): una de sus dos partes, y la FK compuesta a juega lo asegura. El
-- rival es el que lo acepta o lo objeta. NULL mientras no haya resultado.
ALTER TABLE partido
    ADD COLUMN resultado_cargado_por BIGINT,
    ADD CONSTRAINT fk_partido_resultado_cargado_por FOREIGN KEY (id, resultado_cargado_por)
        REFERENCES juega (partido_id, inscripcion_id);
