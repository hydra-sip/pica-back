-- HU-606 (PICA-329): la liquidación reparte la custodia de un evento terminado.
--
-- La cuenta recaudadora de la plataforma: una billetera sin usuario, donde la liquidación acredita los gastos
-- administrativos. Sin usuario nadie entra con ella y no aparece en el admin de usuarios. Hay una sola.
ALTER TABLE billetera ALTER COLUMN usuario_id DROP NOT NULL;
CREATE UNIQUE INDEX uq_billetera_plataforma ON billetera ((usuario_id IS NULL)) WHERE usuario_id IS NULL;
INSERT INTO billetera (usuario_id, creado_por, modificado_por) VALUES (NULL, 'SISTEMA', 'SISTEMA');

-- Lo que la liquidación le acredita al organizador (los gastos del torneo) y a la plataforma (los gastos
-- administrativos). Los ganadores reciben un PREMIO.
ALTER TABLE movimiento DROP CONSTRAINT ck_movimiento_tipo;
ALTER TABLE movimiento
    ADD CONSTRAINT ck_movimiento_tipo CHECK (tipo IN ('CARGA', 'INSCRIPCION', 'COMPRA_NUMERO', 'PREMIO', 'REINTEGRO',
                                                      'COMISION', 'RETIRO', 'PAGO_SERVICIO', 'LIQUIDACION'));

-- Cada acreditación de la liquidación, y la alerta si lo repartido no da lo retenido, quedan en la auditoría.
ALTER TABLE auditoria DROP CONSTRAINT ck_auditoria_accion;
ALTER TABLE auditoria
    ADD CONSTRAINT ck_auditoria_accion CHECK (accion IN ('LOGIN', 'LOGIN_FALLIDO', 'LOGOUT', 'ALTA', 'MODIFICACION',
                                                         'BAJA', 'BLOQUEO', 'DESBLOQUEO', 'PAGO', 'LAUDO', 'SORTEO',
                                                         'CIERRE_INSCRIPCION', 'CIERRE_VENTA', 'LIQUIDACION'));
