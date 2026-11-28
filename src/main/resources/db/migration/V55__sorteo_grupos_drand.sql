-- CR-02 (PICA-397): el sorteo de los grupos sale de drand, como la rifa. Al sellar la lista de inscriptos se fija la
-- ronda (una que todavía no salió) y se publica el compromiso SHA-256(listaHash:ronda); la semilla es el valor de esa
-- ronda y se guarda en semilla_bracket, con su firma. Solo en torneos con fase de grupos.
ALTER TABLE torneo
    ADD COLUMN semilla_ronda BIGINT,
    ADD COLUMN compromiso_hash CHAR(64),
    ADD COLUMN semilla_firma TEXT;

COMMENT ON COLUMN torneo.semilla_ronda IS
    'Grupos: ronda de drand fijada al sellar la lista; su valor es la semilla del sorteo de grupos';
COMMENT ON COLUMN torneo.compromiso_hash IS 'Grupos: SHA-256(lista_hash:semilla_ronda), publicado al sellar la lista';
COMMENT ON COLUMN torneo.semilla_firma IS 'Grupos: firma de drand de la ronda, para verificar la semilla';
