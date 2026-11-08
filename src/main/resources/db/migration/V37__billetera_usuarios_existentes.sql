-- T602-1 (PICA-317): todo usuario tiene su billetera virtual con saldos en cero.
-- Crea la billetera de los usuarios existentes que todavía no la tienen.

INSERT INTO billetera (usuario_id, saldo_disponible, saldo_retenido, ganancia_acumulada, moneda, estado)
SELECT u.id, 0.00, 0.00, 0.00, 'ARS', 'ACTIVA'
FROM usuario u
WHERE NOT EXISTS (SELECT 1 FROM billetera b WHERE b.usuario_id = u.id);

