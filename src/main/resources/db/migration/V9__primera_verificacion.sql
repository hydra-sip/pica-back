-- Primera vez que la cuenta tuvo el mail verificado; no se borra si después un admin le cambia el email.
-- NULL y PENDIENTE_VERIFICACION es un alta que nunca se usó: no reserva el email y otro registro
-- o un login con Google con ese mail la reemplazan (CE2-5). Sin esta marca no se la distingue de
-- una cuenta real a la que le cambiaron el email, que queda en el mismo estado.
ALTER TABLE usuario ADD COLUMN primera_verificacion_en TIMESTAMPTZ;

-- Las cuentas que ya existen se dan por usadas, salvo las que tienen toda la pinta de un registro
-- que nunca se verificó: pendiente, sin Google, creado sin nadie logueado y sin haber iniciado sesión.
UPDATE usuario u
SET primera_verificacion_en = u.creado_en
WHERE NOT (u.estado = 'PENDIENTE_VERIFICACION'
           AND NOT u.email_verificado
           AND u.google_sub IS NULL
           AND u.creado_por = 'SISTEMA'
           AND NOT EXISTS (SELECT 1 FROM refresh_token r WHERE r.usuario_id = u.id));
