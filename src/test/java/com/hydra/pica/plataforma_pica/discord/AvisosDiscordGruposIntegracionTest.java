package com.hydra.pica.plataforma_pica.discord;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Arrays;
import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import com.hydra.pica.plataforma_pica.common.email.EmailService;
import com.hydra.pica.plataforma_pica.partido.domain.Lado;
import com.hydra.pica.plataforma_pica.partido.domain.Partido;
import com.hydra.pica.plataforma_pica.partido.dto.ResultadoRequest;
import com.hydra.pica.plataforma_pica.partido.event.GruposSorteados;
import com.hydra.pica.plataforma_pica.partido.event.ResultadoConfirmado;
import com.hydra.pica.plataforma_pica.partido.repository.JuegaRepository;
import com.hydra.pica.plataforma_pica.partido.repository.PartidoRepository;
import com.hydra.pica.plataforma_pica.partido.service.ResultadoService;
import com.hydra.pica.plataforma_pica.partido.service.TorneoConGruposBase;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** Los avisos de la fase de grupos al canal de Discord (CR-02-4, PICA-398). */
@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
@Transactional
class AvisosDiscordGruposIntegracionTest extends TorneoConGruposBase {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/1234567890/aBc-DeF_ghij";

    private static final RestClient.Builder BUILDER = RestClient.builder();
    private static final MockRestServiceServer DISCORD = MockRestServiceServer.bindTo(BUILDER).build();

    @TestBean(name = "discordHttp", methodName = "discordFalso")
    private RestClient discordHttp;

    static RestClient discordFalso() {
        return BUILDER.build();
    }

    @Autowired private AvisosDiscord avisos;
    @Autowired private ResultadoService resultadoService;
    @Autowired private PartidoRepository partidoRepository;
    @Autowired private JuegaRepository juegaRepository;
    @MockitoBean private EmailService emailService;

    @BeforeEach
    void sorteado() {
        DISCORD.reset();
        inscribir(8);
        cerrar();
        bracketService.generar(organizador, torneo);
        // primero el flush: si no, el UPDATE del torneo de Hibernate pisa el webhook
        entityManager.flush();
        jdbc.update("UPDATE torneo SET discord_webhook = ? WHERE id = ?", WEBHOOK, torneo);
        entityManager.clear();
    }

    @AfterEach
    void todoLoEsperadoLlego() {
        DISCORD.verify();
    }

    @Test
    void sortearLosGruposPublicaCadaGrupoConSusIntegrantes() {
        esperar("🎲 Los grupos están sorteados", "**Grupo A**: jugador_grupos_", "**Grupo B**: jugador_grupos_",
                "**Copa con grupos**");

        avisos.avisar(new GruposSorteados(torneo));
    }

    @Test
    void unEmpateDeGruposDiceElGrupoLaFechaYElMarcador() {
        Partido partido = partidoRepository.deLosGrupos(torneo).getFirst();
        resultadoService.cargar(jugador(partido, Lado.LOCAL), partido.getId(), new ResultadoRequest(2, 2, null, null));
        resultadoService.aceptar(jugador(partido, Lado.VISITANTE), partido.getId());
        entityManager.flush();
        entityManager.clear();

        esperar("✅ **Grupo A · Fecha 1**", "empató 2–2 con");
        avisos.avisar(new ResultadoConfirmado(torneo, partido.getId(), null, false));
    }

    @Test
    void unaVictoriaDeGruposNoDiceAQueRondaPasa() {
        Partido partido = partidoRepository.deLosGrupos(torneo).getFirst();
        resultadoService.cargar(jugador(partido, Lado.LOCAL), partido.getId(), new ResultadoRequest(3, 1, null, null));
        resultadoService.aceptar(jugador(partido, Lado.VISITANTE), partido.getId());
        entityManager.flush();
        entityManager.clear();

        esperar("✅ **Grupo A · Fecha 1**", "le ganó 3–1 a");
        avisos.avisar(new ResultadoConfirmado(torneo, partido.getId(), null, false));
    }

    private Long jugador(Partido partido, Lado lado) {
        return juegaRepository.delPartido(partido.getId()).stream()
                .filter(j -> j.getLado() == lado)
                .findFirst().orElseThrow()
                .getInscripcion().getUsuario().getId();
    }

    private void esperar(String... fragmentos) {
        @SuppressWarnings("unchecked")
        Matcher<String>[] todos = Arrays.stream(fragmentos).map(f -> containsString(f)).toArray(Matcher[]::new);
        DISCORD.expect(requestTo(WEBHOOK))
                .andExpect(jsonPath("$.content").value(allOf(todos)))
                .andRespond(withSuccess());
    }
}
