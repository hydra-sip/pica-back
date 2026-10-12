-- Cuándo empezó la sesión a la que pertenece cada refresh token (CE2-8). Se fija en el login y se
-- copia en cada rotación, así el refresh puede cortar la sesión al llegar al máximo absoluto
-- (app.auth.sesion-maxima) aunque se haya usado todo el tiempo.
ALTER TABLE refresh_token ADD COLUMN sesion_iniciada_en TIMESTAMPTZ;

-- Los tokens que ya existen no saben cuándo empezó su sesión: se toma su propia creación, que es
-- lo más tarde que pudo haber empezado.
UPDATE refresh_token SET sesion_iniciada_en = creado_en WHERE sesion_iniciada_en IS NULL;

ALTER TABLE refresh_token ALTER COLUMN sesion_iniciada_en SET NOT NULL;
