package com.hydra.pica.plataforma_pica.discord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.hydra.pica.plataforma_pica.partido.domain.Juega;
import com.hydra.pica.plataforma_pica.partido.dto.RondaBracket;
import com.hydra.pica.plataforma_pica.partido.event.LlaveSorteada;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.service.Partidos;
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
    private final PartidoRepository partidoRepository;
    private final JuegaRepository juegaRepository;
    private final CanalDiscord canal;
    private final String webUrl;

    AvisosDiscord(TorneoRepository torneoRepository, PartidoRepository partidoRepository,
                  JuegaRepository juegaRepository, CanalDiscord canal,
                  @Value("${app.web-url:http://localhost:5173}") String webUrl) {
        this.torneoRepository = torneoRepository;
        this.partidoRepository = partidoRepository;
        this.juegaRepository = juegaRepository;
        this.canal = canal;
        this.webUrl = webUrl;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alGuardarWebhook(WebhookDiscordGuardado evento) {
        avisar(evento);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void alSortearLlave(LlaveSorteada evento) {
        avisar(evento);
    }

    /** El mensaje de prueba: sale cada vez que se guarda, aunque sea el mismo webhook. */
    void avisar(WebhookDiscordGuardado evento) {
        Torneo torneo = torneoRepository.findById(evento.torneoId()).orElseThrow();
        canal.publicar(torneo.getDiscordWebhook(), firmado(torneo,
                "✅ PICA quedó conectado a este canal: acá van a llegar los avisos del torneo."));
    }

    /**
     * Los cruces de la primera ronda, en el orden de la llave; el que no tiene rival pasa directo. Si no entran en un
     * mensaje, los últimos se resumen en «y N cruces más».
     */
    void avisar(LlaveSorteada evento) {
        Torneo torneo = torneoRepository.findById(evento.torneoId()).orElseThrow();
        if (torneo.getDiscordWebhook() == null) {
            return;
        }
        Map<Integer, List<Juega>> primeraRonda = juegaRepository.delTorneo(torneo.getId()).stream()
                .filter(j -> j.getPartido().getRonda() == 1)
                .collect(Collectors.groupingBy(j -> j.getPartido().getPosicion()));
        List<String> cruces = primeraRonda.keySet().stream().sorted()
                .map(posicion -> cruce(primeraRonda.get(posicion)))
                .toList();
        String titulo = evento.regenerada()
                ? "🔁 La llave se volvió a sortear: los cruces de antes ya no valen."
                : "🎲 La llave está armada.";
        int rondas = partidoRepository.rondasDelTorneo(torneo.getId());
        String encabezado = titulo + "\n**" + RondaBracket.nombre(1, rondas)
                + "**";
        canal.publicar(torneo.getDiscordWebhook(), firmado(torneo, encabezado + "\n" + entrando(cruces,
                CanalDiscord.LARGO_MAXIMO - encabezado.length() - firma(torneo).length() - 2)));
    }

    /** «A vs B», o «A pasa directo» si es un BYE. */
    private static String cruce(List<Juega> lados) {
        List<Juega> ordenados = new ArrayList<>(lados);
        ordenados.sort(Comparator.comparing(Juega::getLado));
        String local = Partidos.nombre(ordenados.get(0).getInscripcion());
        if (ordenados.size() == 1) {
            return "• " + local + " pasa directo";
        }
        return "• " + local + " vs " + Partidos.nombre(ordenados.get(1).getInscripcion());
    }

    /** Las líneas que entran en {@code largo}; las que no, en «y N cruces más». */
    static String entrando(List<String> lineas, int largo) {
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < lineas.size(); i++) {
            String resto = "\n…y " + (lineas.size() - i) + " cruces más";
            boolean ultima = i == lineas.size() - 1;
            int conEsta = texto.length() + (texto.isEmpty() ? 0 : 1) + lineas.get(i).length();
            if (conEsta + (ultima ? 0 : resto.length()) > largo) {
                return texto.append(resto).toString();
            }
            if (!texto.isEmpty()) {
                texto.append('\n');
            }
            texto.append(lineas.get(i));
        }
        return texto.toString();
    }

    /** El texto con el nombre del torneo y, si ya está publicado, el enlace a su ficha. */
    private String firmado(Torneo torneo, String texto) {
        return texto + "\n" + firma(torneo);
    }

    private String firma(Torneo torneo) {
        String firma = "**" + torneo.getNombre() + "**";
        if (torneo.getSlug() != null) {
            firma += " · " + webUrl + "/torneos/" + torneo.getSlug();
        }
        return firma;
    }
}
