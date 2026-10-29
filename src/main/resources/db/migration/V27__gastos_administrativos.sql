-- La comisión de la plataforma pasa a ser gastos administrativos que paga aparte quien se inscribe o compra un número
-- (HU-601, PICA-283): la inscripción va entera a la bolsa del torneo y lo que la rifa aporta al pozo lo fija la rifa
-- (pct_destinado_torneo, V25). El % se copia de parametro_plataforma (V26) al publicar y queda en el sello; en
-- BORRADOR es null. Torneo y rifa guardan el suyo, así un cambio del parámetro no toca lo publicado. El de la rifa lo
-- usa la venta de números (HU-403).
ALTER TABLE torneo
    ADD COLUMN pct_gastos_administrativos NUMERIC(5, 2),
    ADD CONSTRAINT ck_torneo_pct_gastos_administrativos CHECK (pct_gastos_administrativos BETWEEN 0 AND 100);

ALTER TABLE rifa
    ADD COLUMN pct_gastos_administrativos NUMERIC(5, 2),
    ADD CONSTRAINT ck_rifa_pct_gastos_administrativos CHECK (pct_gastos_administrativos BETWEEN 0 AND 100);

-- Los torneos ya publicados se quedaban la comisión de la bolsa: ahora es su % de gastos administrativos. Su sello se
-- calculó con el reparto viejo y no se toca.
UPDATE torneo
SET pct_gastos_administrativos = 100 - pct_pozo_competidores - pct_premio_rifa
WHERE estado <> 'BORRADOR' AND pct_pozo_competidores IS NOT NULL AND pct_premio_rifa IS NOT NULL;

-- El reparto de la bolsa deja de existir: todo va al pozo de competidores y nada al premio de la rifa. Las columnas
-- quedan fijas en 100 y 0 porque las sigue pidiendo ck_torneo_completo_fuera_de_borrador (V18).
UPDATE torneo SET pct_pozo_competidores = 100, pct_premio_rifa = 0;
ALTER TABLE torneo
    ALTER COLUMN pct_pozo_competidores SET DEFAULT 100,
    ALTER COLUMN pct_premio_rifa SET DEFAULT 0;
