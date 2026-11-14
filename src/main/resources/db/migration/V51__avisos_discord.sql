-- PICA-369 (PICA-370, PICA-371): avisos del torneo a un canal de Discord por webhook.
-- El webhook del canal, que carga el organizador; es un secreto y solo él lo ve, enmascarado.
ALTER TABLE torneo
    ADD COLUMN discord_webhook VARCHAR(255);

-- La apertura de la ventana de check-in que ya se avisó al canal: si el partido se reprograma, la ventana cambia y
-- se vuelve a avisar.
ALTER TABLE partido
    ADD COLUMN checkin_avisado_para TIMESTAMPTZ;
