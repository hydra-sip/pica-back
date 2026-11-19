-- Versión del bracket (HU-809, T809-1): un número por torneo que sube en la misma transacción de cada cambio de la
-- llave. Viaja dentro del bracket (GET, tópico del torneo y sincronización) para que el front descarte un mensaje más
-- viejo que el que ya muestra y detecte si se perdió alguno. No se mapea en la entidad Torneo: la sube un UPDATE
-- atómico, que además ordena los cambios concurrentes del mismo torneo con el lock de la fila.
ALTER TABLE torneo ADD COLUMN bracket_version BIGINT NOT NULL DEFAULT 0;
