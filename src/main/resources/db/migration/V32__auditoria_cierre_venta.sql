-- El cierre de la venta de una rifa (HU-407, PICA-261) queda en la auditoría con su motivo y el hash de la lista
-- sellada: por la fecha o a mano, como el cierre de la inscripción de un torneo (V24).
ALTER TABLE auditoria DROP CONSTRAINT ck_auditoria_accion;
ALTER TABLE auditoria
    ADD CONSTRAINT ck_auditoria_accion CHECK (accion IN ('LOGIN', 'LOGIN_FALLIDO', 'LOGOUT', 'ALTA', 'MODIFICACION',
                                                         'BAJA', 'BLOQUEO', 'DESBLOQUEO', 'PAGO', 'LAUDO', 'SORTEO',
                                                         'CIERRE_INSCRIPCION', 'CIERRE_VENTA'));
