package com.hydra.pica.plataforma_pica.discord;

/**
 * La URL del webhook de un canal de Discord (PICA-369): {@code https://discord.com/api/webhooks/{id}/{token}}. Quien
 * tiene la URL puede publicar en el canal, así que es un secreto: fuera del organizador, y en los logs, va
 * enmascarada.
 */
public final class WebhookDiscord {

    /** Solo el dominio actual: el viejo, discordapp.com, queda afuera. */
    public static final String PATRON = "^https://discord\\.com/api/webhooks/\\d+/[\\w-]+$";

    private static final String PREFIJO = "https://discord.com/api/webhooks/";

    private WebhookDiscord() {
    }

    /** {@code https://discord.com/api/webhooks/…/••••abcd}: los últimos 4 caracteres del token; null sin webhook. */
    public static String enmascarar(String url) {
        if (url == null) {
            return null;
        }
        String token = url.substring(url.lastIndexOf('/') + 1);
        return PREFIJO + "…/••••" + token.substring(Math.max(0, token.length() - 4));
    }
}
