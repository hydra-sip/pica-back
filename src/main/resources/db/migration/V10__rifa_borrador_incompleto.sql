-- Una rifa en BORRADOR se puede guardar incompleta (HU-402, PICA-226): los términos se aceptan al publicar y
-- la ventana de venta se puede cargar después. Fuera de BORRADOR las tres son obligatorias.
-- ck_rifa_venta sigue valiendo: con alguna de las dos fechas en NULL el CHECK no falla.
ALTER TABLE rifa
    ALTER COLUMN venta_desde DROP NOT NULL,
    ALTER COLUMN venta_hasta DROP NOT NULL,
    ALTER COLUMN terminos_aceptados_en DROP NOT NULL;

ALTER TABLE rifa
    ADD CONSTRAINT ck_rifa_completa_fuera_de_borrador CHECK (
        estado = 'BORRADOR'
        OR (venta_desde IS NOT NULL AND venta_hasta IS NOT NULL AND terminos_aceptados_en IS NOT NULL));
