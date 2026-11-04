-- Los premios de la rifa son porcentajes enteros del pozo (PICA-226, decisión del equipo): de 1 a
-- 100, sin decimales. Si hubiera alguno con decimales se redondea; ck_premio_rifa_pct sigue valiendo.
ALTER TABLE premio_rifa
    ALTER COLUMN pct TYPE INTEGER USING round(pct)::INTEGER;
