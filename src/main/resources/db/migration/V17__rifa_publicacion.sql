-- Publicar la rifa (HU-402, PICA-219). Es V17 porque V14 a V16 son de inscripciones (PICA-208/209).
--
-- sorteada_en pasa a ser la fecha PROGRAMADA del sorteo: la carga el organizador antes de publicar,
-- el participante la ve al comprar y la aplicación sortea sola cuando llega. Se reusa la columna de
-- V9 para no cambiar el modelo; el momento real en que se sorteó queda en semilla_obtenida_en.
--
-- Fuera de BORRADOR, además de la ventana y los términos (V10), son obligatorias la declaración de
-- la autorización de la lotería y la fecha del sorteo.
COMMENT ON COLUMN rifa.sorteada_en IS
    'Fecha programada del sorteo; el momento real en que se sorteó es semilla_obtenida_en';

-- Las rifas que ya estaban publicadas sin fecha de sorteo (la del seed de desarrollo) la reciben un día
-- después del fin de la venta, para que pasen los CHECK de abajo.
UPDATE rifa
SET sorteada_en = venta_hasta + interval '1 day'
WHERE estado <> 'BORRADOR' AND sorteada_en IS NULL AND venta_hasta IS NOT NULL;

ALTER TABLE rifa
    DROP CONSTRAINT ck_rifa_completa_fuera_de_borrador;

ALTER TABLE rifa
    ADD CONSTRAINT ck_rifa_completa_fuera_de_borrador CHECK (
        estado = 'BORRADOR'
        OR (venta_desde IS NOT NULL AND venta_hasta IS NOT NULL AND terminos_aceptados_en IS NOT NULL
            AND loteria_declarada_en IS NOT NULL AND sorteada_en IS NOT NULL)),
    -- El sorteo es después de que termina la venta. Con alguna de las dos en NULL (borrador) no falla.
    ADD CONSTRAINT ck_rifa_sorteo_despues_de_la_venta CHECK (sorteada_en > venta_hasta);
