package com.hydra.pica.plataforma_pica.rifa.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** La ronda vigente y la URL de drand (HU-503), sin salir a internet. */
class DrandTest {

    private static final long GENESIS = 1692803367;

    private final Drand drand = new Drand(RestClient.builder(), "https://api.drand.sh/",
            "52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971", GENESIS, 3);

    @Test
    void laRondaEsLaUltimaQueSalioHastaEseInstante() {
        assertThat(drand.ronda(Instant.ofEpochSecond(GENESIS))).isEqualTo(1);
        assertThat(drand.ronda(Instant.ofEpochSecond(GENESIS + 2))).isEqualTo(1);
        assertThat(drand.ronda(Instant.ofEpochSecond(GENESIS + 3))).isEqualTo(2);
        assertThat(drand.ronda(Instant.ofEpochSecond(GENESIS + 999 * 3))).isEqualTo(1000);
    }

    @Test
    void elPulsoEsValidoSiEsDeLaRondaYSuRandomnessEsElSha256DeLaFirma() {
        // la ronda 1000 tal como la devuelve drand
        String firma = "b44679b9a59af2ec876b1a6b1ad52ea9b1615fc3982b19576350f93447cb1125e342b73a8dd2bacbe47e4b6b63ed5e39";
        String randomness = "fe290beca10872ef2fb164d2aa4442de4566183ec51c56ff3cd603d930e54fdd";

        assertThat(Drand.valido(1000, new Drand.Pulso(1000, randomness, firma))).isTrue();
        assertThat(Drand.valido(1001, new Drand.Pulso(1000, randomness, firma))).isFalse();
        assertThat(Drand.valido(1000, new Drand.Pulso(1000, "0".repeat(64), firma))).isFalse();
        assertThat(Drand.valido(1000, null)).isFalse();
    }

    @Test
    void laUrlEsLaDeLaRondaEnLaRed() {
        assertThat(drand.url(1000)).isEqualTo(
                "https://api.drand.sh/52db9ba70e0cc0f6eaf7803dd07447a1f5477735fd3f661792ba94600c84e971/public/1000");
    }
}
