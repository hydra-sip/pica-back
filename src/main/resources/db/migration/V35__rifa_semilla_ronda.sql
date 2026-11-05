-- La semilla del sorteo de una rifa sale de drand (HU-503, PICA-264): se guarda la ronda, así cualquiera la consulta
-- en el beacon. La URL se arma con la ronda y el chain hash de la configuración.
ALTER TABLE rifa ADD COLUMN semilla_ronda BIGINT;

COMMENT ON COLUMN rifa.semilla_ronda IS
    'Ronda de drand de la que salió la semilla: la vigente en sorteada_en; null si la semilla la generó el sistema';
