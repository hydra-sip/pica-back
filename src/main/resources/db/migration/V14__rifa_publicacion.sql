-- Publicar la rifa (HU-402, PICA-219).
--
-- sorteada_en pasa a ser la fecha PROGRAMADA del sorteo: la carga el organizador antes de publicar,
-- el participante la ve al comprar y la aplicación sortea sola cuando llega. Se reusa la columna de
-- V9 para no cambiar el modelo; el momento real en que se sorteó queda en semilla_obtenida_en.
--
-- Fuera de BORRADOR, además de la ventana y los términos (V10), son obligatorias la declaración de
-- la autorización de la lotería y la fecha del sorteo.
COMMENT ON COLUMN rifa.sorteada_en IS
    'Fecha programada del sorteo; el momento real en que se sorteó es semilla_obtenida_en';

ALTER TABLE rifa
    DROP CONSTRAINT ck_rifa_completa_fuera_de_borrador;

ALTER TABLE rifa
    ADD CONSTRAINT ck_rifa_completa_fuera_de_borrador CHECK (
        estado = 'BORRADOR'
        OR (venta_desde IS NOT NULL AND venta_hasta IS NOT NULL AND terminos_aceptados_en IS NOT NULL
            AND loteria_declarada_en IS NOT NULL AND sorteada_en IS NOT NULL)),
    -- El sorteo es después de que termina la venta. Con alguna de las dos en NULL (borrador) no falla.
    ADD CONSTRAINT ck_rifa_sorteo_despues_de_la_venta CHECK (sorteada_en > venta_hasta);
