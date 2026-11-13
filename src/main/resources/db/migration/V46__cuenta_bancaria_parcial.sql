-- HU-607 (PICA-336): permitir volver a cargar una cuenta bancaria dada de baja lógica.
ALTER TABLE cuenta_bancaria DROP CONSTRAINT uq_cuenta_bancaria_usuario_valor;
CREATE UNIQUE INDEX uq_cuenta_bancaria_usuario_valor
    ON cuenta_bancaria (usuario_id, LOWER(valor))
    WHERE eliminado_en IS NULL;
