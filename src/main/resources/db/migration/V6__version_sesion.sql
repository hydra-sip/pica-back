-- Sube cada vez que se cierran todas las sesiones del usuario: refresh reutilizado, baja, bloqueo,
-- cambio o reset de clave y cambio de roles. El access token lleva la versión con la que se emitió
-- y el filtro JWT rechaza los que no traen la actual, así que se cortan en el momento y no al vencer.
ALTER TABLE usuario ADD COLUMN version_sesion INTEGER NOT NULL DEFAULT 0;
