package com.hydra.pica.plataforma_pica.discord;

import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.torneo.event.WebhookDiscordGuardado;
import com.hydra.pica.plataforma_pica.torneo.repository.TorneoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Los avisos del torneo al canal de Discord (PICA-369). Van después del commit y arman el texto con lo que ya quedó en
 * la base; el envío sale en el hilo de Discord ({@link CanalDiscord}). Sin webhook en el torneo, no hacen nada.
 *
 * Cada listener delega en un {@code avisar} sin transacción propia: los tests lo llaman dentro de la suya, que no se
 * confirma (como {@code AvisoAusencia}).
 */
@Component
public class AvisosDiscord {

    private final TorneoRepository torneoRepository;
    private final CanalDiscord canal;
    private final String webUrl;

    AvisosDiscord(TorneoRepository torneoRepository, CanalDiscord canal,
                  @Value("${app.web-url:http://localhost:5173}") String webUrl) {
        this.torneoRepository = torneoRepository;
        this.canal = canal;
        this.webUrl = webUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alGuardarWebhook(WebhookDiscordGuardado evento) {
        avisar(evento);
    }

    /** El mensaje de prueba: sale cada vez que se guarda, aunque sea el mismo webhook. */
    void avisar(WebhookDiscordGuardado evento) {
        Torneo torneo = torneoRepository.findById(evento.torneoId()).orElseThrow();
        canal.publicar(torneo.getDiscordWebhook(), firmado(torneo,
                "✅ PICA quedó conectado a este canal: acá van a llegar los avisos del torneo."));
    }

    /** El texto con el nombre del torneo y, si ya está publicado, el enlace a su ficha. */
    private String firmado(Torneo torneo, String texto) {
        String firma = "**" + torneo.getNombre() + "**";
        if (torneo.getSlug() != null) {
            firma += " · " + webUrl + "/torneos/" + torneo.getSlug();
        }
        return texto + "\n" + firma;
    }
}
