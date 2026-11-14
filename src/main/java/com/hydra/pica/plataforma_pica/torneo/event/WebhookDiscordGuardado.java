package com.hydra.pica.plataforma_pica.torneo.event;

/** El organizador guardó el webhook de Discord del torneo (PICA-370): va un mensaje de prueba al canal. */
public record WebhookDiscordGuardado(Long torneoId) {
}
