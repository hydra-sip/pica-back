-- PICA-318 (T602-2): Unicidad de referencia externa para garantizar idempotencia en cargas
ALTER TABLE movimiento
    ADD CONSTRAINT uq_movimiento_referencia_externa UNIQUE (referencia_externa);

