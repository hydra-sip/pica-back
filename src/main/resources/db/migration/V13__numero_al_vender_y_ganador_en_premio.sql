-- Decisiones del equipo para la rifa (PICA-226):
--
-- 1. Los números se insertan al elegirlos, no al publicar. La PK (rifa_id, numero) es la que impide
--    que dos personas se queden con el mismo número: el segundo INSERT falla. Desaparece DISPONIBLE:
--    un número que no tiene fila está libre.
--    - RESERVADO: alguien lo eligió y todavía no pagó. No tiene ticket; reservado_por dice quién lo
--      eligió (y es el único que lo puede pagar). La reserva empieza en creado_en y dura lo que diga
--      la aplicación; vencida, el número se libera borrando la fila (lo hace la compra o un job).
--    - VENDIDO: se pagó; tiene ticket, y el comprador es el del ticket, así que reservado_por queda en
--      NULL para no repetirlo.
--    - REINTEGRADO: se devolvió la plata; conserva la fila y no se vuelve a vender.
-- 2. El ganador pasa de numero.posicion_sorteo a premio_rifa.numero_ganador: cada premio apunta al
--    número que lo ganó (NULL hasta el sorteo). La FK comparte rifa_id, así que el ganador es de la
--    misma rifa, y el UNIQUE impide que un número gane dos premios. Que esté VENDIDO lo controla el
--    sorteo.
DELETE FROM numero WHERE estado = 'DISPONIBLE';

ALTER TABLE numero
    DROP CONSTRAINT uq_numero_rifa_posicion_sorteo,
    DROP CONSTRAINT ck_numero_posicion_sorteo,
    DROP CONSTRAINT ck_numero_ticket,
    DROP CONSTRAINT ck_numero_estado,
    DROP COLUMN posicion_sorteo;

ALTER TABLE numero
    ADD COLUMN reservado_por BIGINT,
    ALTER COLUMN estado DROP DEFAULT,
    ADD CONSTRAINT fk_numero_reservado_por FOREIGN KEY (reservado_por) REFERENCES usuario (id),
    ADD CONSTRAINT ck_numero_estado CHECK (estado IN ('RESERVADO', 'VENDIDO', 'REINTEGRADO')),
    -- Reservado: sin ticket y con quién lo eligió. Vendido o reintegrado: con ticket y sin reservado_por.
    ADD CONSTRAINT ck_numero_reserva CHECK (
        (estado = 'RESERVADO' AND ticket_id IS NULL AND reservado_por IS NOT NULL)
        OR (estado <> 'RESERVADO' AND ticket_id IS NOT NULL AND reservado_por IS NULL));

-- Para encontrar rápido las reservas vencidas (creado_en + duración de la reserva) y liberarlas.
CREATE INDEX idx_numero_reservas
    ON numero (creado_en)
    WHERE estado = 'RESERVADO';

CREATE INDEX idx_numero_reservado_por
    ON numero (reservado_por);

ALTER TABLE premio_rifa
    ADD COLUMN numero_ganador INTEGER,
    ADD CONSTRAINT fk_premio_rifa_numero FOREIGN KEY (rifa_id, numero_ganador)
        REFERENCES numero (rifa_id, numero),
    ADD CONSTRAINT uq_premio_rifa_numero_ganador UNIQUE (rifa_id, numero_ganador);
