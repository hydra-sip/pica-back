-- Reparto del premio del equipo entre los integrantes de su nómina (CR-05). pct_premio es el % del premio del equipo
-- que le toca a cada integrante en esa inscripción. Null significa la regla de antes: la inscripción se liquida con todo
-- al capitán.
ALTER TABLE nomina ADD COLUMN pct_premio NUMERIC(5, 2);

ALTER TABLE nomina
    ADD CONSTRAINT ck_nomina_pct_premio CHECK (pct_premio IS NULL OR pct_premio BETWEEN 0 AND 100);

-- Los torneos con la lista todavía sin sellar arrancan en partes iguales: 100 / n con dos decimales hacia abajo, y el
-- sobrante del redondeo para el capitán. Los que ya tienen la lista sellada quedan en null, con la regla con la que se
-- inscribieron los equipos.
UPDATE nomina n
SET pct_premio = base.parte + CASE WHEN n.usuario_id = e.capitan_id THEN 100 - base.parte * base.cantidad ELSE 0 END
FROM inscripcion i
JOIN torneo t ON t.id = i.torneo_id
JOIN equipo e ON e.id = i.equipo_id
JOIN (SELECT inscripcion_id, count(*) AS cantidad, trunc(100.0 / count(*), 2) AS parte
      FROM nomina GROUP BY inscripcion_id) base ON base.inscripcion_id = i.id
WHERE n.inscripcion_id = i.id
  AND t.lista_hash IS NULL;
