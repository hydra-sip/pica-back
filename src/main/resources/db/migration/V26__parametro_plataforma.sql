-- Parámetros de la plataforma (HU-601, PICA-281): los valores que fija la plataforma y no cada organizador. Vienen
-- cargados acá; editarlos desde el admin es HU-702. Quien los usa copia el valor al publicar (el torneo y la rifa
-- sellan el suyo), así que cambiar un parámetro no toca lo ya publicado. Es V26 porque V25 es la de la rifa asociada
-- a un torneo.
CREATE TABLE parametro_plataforma (
    clave VARCHAR(50) NOT NULL,
    valor NUMERIC(12, 2) NOT NULL,
    descripcion TEXT NOT NULL,
    modificado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    modificado_por VARCHAR(100),
    CONSTRAINT pk_parametro_plataforma PRIMARY KEY (clave),
    CONSTRAINT ck_parametro_plataforma_porcentaje CHECK (clave <> 'GASTOS_ADMINISTRATIVOS' OR valor BETWEEN 0 AND 100)
);

-- Los gastos administrativos son un % sobre el valor de la inscripción o del número, que se cobra aparte: la
-- inscripción va entera al torneo y la venta de la rifa, a sus premios y al pozo del torneo.
INSERT INTO parametro_plataforma (clave, valor, descripcion, modificado_por)
VALUES ('GASTOS_ADMINISTRATIVOS', 10,
        '% de gastos administrativos que paga aparte quien se inscribe en un torneo o compra un número de rifa',
        'SISTEMA');
