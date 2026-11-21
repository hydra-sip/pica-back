-- HU-808 (T808-2): la apertura de la ventana de check-in que ya se notificó a los dos lados del partido. Va aparte de
-- checkin_avisado_para (el aviso al canal de Discord, que solo tienen los torneos con webhook): cada aviso lleva su
-- marca y no se pisan. Guarda la apertura y no cuándo se avisó, así un partido reprogramado se vuelve a notificar.
ALTER TABLE partido ADD COLUMN checkin_notificado_para TIMESTAMPTZ;
