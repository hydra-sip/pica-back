-- HU-606 (PICA-330): lo que acredita la liquidación de una rifa (los premios, los gastos administrativos y lo que la
-- rifa aporta al pozo de su torneo) lleva la rifa, así Mi billetera muestra de dónde vino. Las compras siguen
-- encontrando su rifa por el ticket.
ALTER TABLE movimiento ADD COLUMN rifa_id BIGINT;
ALTER TABLE movimiento ADD CONSTRAINT fk_movimiento_rifa FOREIGN KEY (rifa_id) REFERENCES rifa (id);
CREATE INDEX idx_movimiento_rifa_id ON movimiento (rifa_id);
