-- Un borrador cancelado nunca aceptó el reglamento: la fecha se guarda recién al confirmar. El CHECK de V14
-- exigía el reglamento en todo estado que no fuera BORRADOR, así que cancelar un borrador fallaba al guardar.
-- Una pendiente de pago o una confirmada siguen exigiéndolo; una cancelada que venía de ahí lo conserva.
ALTER TABLE inscripcion DROP CONSTRAINT ck_inscripcion_reglamento;
ALTER TABLE inscripcion
    ADD CONSTRAINT ck_inscripcion_reglamento
        CHECK (estado IN ('BORRADOR', 'CANCELADA') OR reglamento_aceptado_en IS NOT NULL);
