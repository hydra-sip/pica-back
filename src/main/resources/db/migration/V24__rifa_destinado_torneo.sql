-- Rifa asociada a un torneo (HU-401, PICA-218): la rifa recauda para el torneo. Es V24 porque V21 a V23 son de
-- inscripciones y partidos. pct_destinado_torneo es el % de lo que vende la rifa que va al pozo del torneo; no es un
-- premio y no se sortea. Lo que queda se reparte en los premios: para publicar, pct_destinado_torneo más los premios
-- tienen que sumar 100 (lo valida la aplicación). Entero, como los premios (INTEGER, como premio_rifa.pct en V12).
-- Sin torneo es 0.
ALTER TABLE rifa
    ADD COLUMN pct_destinado_torneo INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_rifa_pct_destinado_torneo CHECK (
        pct_destinado_torneo BETWEEN 0 AND 99
        AND (torneo_id IS NOT NULL OR pct_destinado_torneo = 0));
