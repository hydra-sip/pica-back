-- Venta de números (HU-403, PICA-222): la compra cobra los gastos administrativos que la rifa copió al publicar (V27).
-- Las rifas publicadas antes de V27 no los tienen: se les pone el % vigente, como si se hubieran publicado hoy.
-- Es V29 porque V26 y V27 son las de los parámetros y los gastos administrativos, y V28 la del check-in (PICA-252).
UPDATE rifa
SET pct_gastos_administrativos = (SELECT valor FROM parametro_plataforma WHERE clave = 'GASTOS_ADMINISTRATIVOS')
WHERE estado <> 'BORRADOR' AND pct_gastos_administrativos IS NULL;
