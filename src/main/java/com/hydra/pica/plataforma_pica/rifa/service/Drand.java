package com.hydra.pica.plataforma_pica.rifa.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * El beacon público de drand (HU-503, PICA-264), red quicknet: cada {@code periodo} segundos sale una ronda nueva con
 * un valor aleatorio ({@code randomness}) firmado por la League of Entropy, que cualquiera consulta en
 * {@link #url(long)}. La ronda vigente en un instante se calcula con el {@code genesis} y el {@code periodo} de la
 * red, sin consultar nada. drand se toma como confiable: la firma no se verifica, pero el pulso tiene que ser el de
 * la ronda pedida y su {@code randomness} el SHA-256 de la firma, como lo deriva drand.
 */
@Component
public class Drand {

    private final RestClient http;
    private final String url;
    private final String chainHash;
    private final long genesis;
    private final long periodo;

    Drand(RestClient.Builder builder,
          @Value("${app.rifas.drand.url:https://api.drand.sh}") String url,
          @Value("${app.rifas.drand.chain-hash:52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971}") String chainHash,
          @Value("${app.rifas.drand.genesis:1692803367}") long genesis,
          @Value("${app.rifas.drand.periodo:3}") long periodo) {
        this.url = url.replaceAll("/+$", "");
        this.http = builder.baseUrl(this.url).build();
        this.chainHash = chainHash;
        this.genesis = genesis;
        this.periodo = periodo;
    }

    /** La ronda vigente en ese instante: la última que salió hasta ahí. La 1 sale en el genesis. */
    public long ronda(Instant instante) {
        return (instante.getEpochSecond() - genesis) / periodo + 1;
    }

    public Duration periodo() {
        return Duration.ofSeconds(periodo);
    }

    public Pulso pulso(long ronda) {
        Pulso pulso = http.get()
                .uri("/{chainHash}/public/{ronda}", chainHash, ronda)
                .retrieve()
                .body(Pulso.class);
        if (!valido(ronda, pulso)) {
            throw new IllegalStateException("drand devolvió un pulso que no es el de la ronda " + ronda + ": " + pulso);
        }
        return pulso;
    }

    /** Donde cualquiera consulta la ronda para comparar la semilla y la firma. */
    public String url(long ronda) {
        return url + "/" + chainHash + "/public/" + ronda;
    }

    static boolean valido(long ronda, Pulso pulso) {
        return pulso != null && pulso.round() == ronda && pulso.signature() != null
                && SelloTorneo.sha256(HexFormat.of().parseHex(pulso.signature())).equals(pulso.randomness());
    }

    public record Pulso(long round, String randomness, String signature) {
    }
}
