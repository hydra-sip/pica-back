package com.hydra.pica.plataforma_pica.torneo.dto;

import com.hydra.pica.plataforma_pica.discord.WebhookDiscord;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** El webhook del canal de Discord del torneo (PICA-370), como lo copia el organizador desde Discord. */
public record DiscordTorneoRequest(
        @NotBlank
        @Pattern(regexp = WebhookDiscord.PATRON, message = "Tiene que ser un webhook de Discord: https://discord.com/api/webhooks/…")
        String url) {
}
