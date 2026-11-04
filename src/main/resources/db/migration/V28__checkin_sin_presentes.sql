-- Qué pasa si al vencer la ventana de check-in no se presentó ninguno de los dos (HU-309): REPROGRAMAR deja el
-- partido sin fecha y DESCALIFICAR_AMBOS lo cierra sin ganador. Lo elige el organizador al configurar el check-in.
ALTER TABLE torneo
    ADD COLUMN checkin_sin_presentes VARCHAR(20) NOT NULL DEFAULT 'REPROGRAMAR',
    ADD CONSTRAINT ck_torneo_checkin_sin_presentes
        CHECK (checkin_sin_presentes IN ('REPROGRAMAR', 'DESCALIFICAR_AMBOS'));
