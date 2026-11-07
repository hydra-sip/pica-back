package com.hydra.pica.plataforma_pica.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.hydra.pica.plataforma_pica.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * El id de cada request (RNF-OBS-01) con la cadena de filtros real: el header de ida y vuelta, el MDC limpio al
 * terminar y el {@code requestId} de los errores, tanto los que corta Spring Security como los del
 * {@code ApiExceptionHandler}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class RequestIdIntegracionTest {

    private static final String HEADER = "X-Request-Id";

    @Autowired private MockMvc mockMvc;

    @Test
    void siElPedidoMandaElHeaderVuelveElMismo() throws Exception {
        mockMvc.perform(get("/actuator/health").header(HEADER, "front-1234_abc.9"))
                .andExpect(status().isOk())
                .andExpect(header().string(HEADER, "front-1234_abc.9"));
    }

    @Test
    void sinHeaderSeGeneraUnUuid() throws Exception {
        MvcResult resultado = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn();

        String requestId = resultado.getResponse().getHeader(HEADER);
        assertThat(requestId).isNotNull();
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
    }

    @Test
    void unHeaderInvalidoSeReemplazaPorUnUuid() throws Exception {
        // un salto de línea no llega acá: lo rechaza antes el StrictHttpFirewall de Spring Security
        for (String invalido : new String[] {"con espacio", "a;b=c", "<script>", "x".repeat(65), ""}) {
            MvcResult resultado = mockMvc.perform(get("/actuator/health").header(HEADER, invalido))
                    .andExpect(status().isOk())
                    .andReturn();

            String requestId = resultado.getResponse().getHeader(HEADER);
            assertThat(requestId).as("para %s", invalido).isNotEqualTo(invalido);
            assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        }
    }

    @Test
    void elMdcQuedaLimpioAlTerminar() throws Exception {
        // MockMvc corre el request en este mismo hilo, como Tomcat en uno de su pool
        mockMvc.perform(get("/actuator/health").header(HEADER, "id-del-anterior"))
                .andExpect(status().isOk());

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void elDeUnErrorDeSpringSecurityVieneEnElBody() throws Exception {
        mockMvc.perform(get("/api/v1/usuarios/me").header(HEADER, "id-del-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HEADER, "id-del-401"))
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"))
                .andExpect(jsonPath("$.requestId").value("id-del-401"));
    }

    @Test
    void elDeUnErrorDelApiExceptionHandlerVieneEnElBody() throws Exception {
        MvcResult resultado = mockMvc.perform(get("/api/v1/torneos/slug/no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("TORNEO_NO_ENCONTRADO"))
                .andReturn();

        String requestId = resultado.getResponse().getHeader(HEADER);
        assertThat(requestId).isNotNull();
        assertThat(resultado.getResponse().getContentAsString()).contains("\"requestId\":\"" + requestId + "\"");
    }
}
