package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.hydra.pica.plataforma_pica.billetera.service.EventosDelContrato.Evento;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Los eventos del contrato por la API de Etherscan v2 (HU-608, PICA-343), sin salir a la red. El evento es el de la
 * carga real de {@link LectorDeCadenaTest}.
 */
class EventosDelContratoTest {

    private static final String API = "https://api.example/v2/api";
    private static final long DEPLOY = 55324950;
    private static final String CONTRATO = "0x36e633eDD1E79Ef2b9A3c6A099D873D52e44EDF0";
    private static final String TOPIC_PAGADOR = "0x000000000000000000000000262dfbfa37436e5b99a53377e633509d1a9b301a";

    private MockRestServiceServer api;

    private EventosDelContrato eventos(String apiKey) {
        RestClient.Builder builder = RestClient.builder();
        api = MockRestServiceServer.bindTo(builder).build();
        return new EventosDelContrato(builder, new CriptoProperties("https://rpc.example", CONTRATO, 84532, 3,
                Duration.ofSeconds(5), new BigInteger("10000000000"), "https://sepolia.basescan.org", DEPLOY, API,
                apiKey));
    }

    @Test
    @DisplayName("Pide los CargaRegistrada del contrato desde el deploy y lee la referencia, el pagador y el monto")
    void leeLosEventos() {
        EventosDelContrato lector = eventos("clave");
        api.expect(requestTo(startsWith(API)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("chainid", "84532"))
                .andExpect(queryParam("module", "logs"))
                .andExpect(queryParam("action", "getLogs"))
                .andExpect(queryParam("address", CONTRATO))
                .andExpect(queryParam("topic0", LectorDeCadena.TOPIC_CARGA_REGISTRADA))
                .andExpect(queryParam("fromBlock", String.valueOf(DEPLOY)))
                .andExpect(queryParam("apikey", "clave"))
                .andRespond(withSuccess(respuesta("1", "OK", log(LectorDeCadenaTest.REFERENCIA,
                        LectorDeCadenaTest.HASH_CARGA, 57254703, 0)), MediaType.APPLICATION_JSON));

        assertThat(lector.cargasRegistradas()).containsExactly(new Evento(LectorDeCadenaTest.REFERENCIA,
                LectorDeCadenaTest.PAGADOR, new BigInteger("100000000000000"), LectorDeCadenaTest.HASH_CARGA,
                57254703));
        api.verify();
    }

    @Test
    @DisplayName("Sin eventos, la API responde 'No records found' y es la lista vacía")
    void sinEventos() {
        EventosDelContrato lector = eventos("clave");
        api.expect(requestTo(startsWith(API)))
                .andRespond(withSuccess(respuesta("0", "No records found", ""), MediaType.APPLICATION_JSON));

        assertThat(lector.cargasRegistradas()).isEmpty();
    }

    @Test
    @DisplayName("Con un pedido lleno sigue desde su último bloque y descarta los repetidos")
    void pagina() {
        EventosDelContrato lector = eventos("clave");
        String llena = IntStream.range(0, EventosDelContrato.POR_PEDIDO)
                .mapToObj(i -> log(referencia(i), hash(i), DEPLOY + i, 0))
                .collect(Collectors.joining(","));
        long ultimo = DEPLOY + EventosDelContrato.POR_PEDIDO - 1;
        api.expect(requestTo(startsWith(API)))
                .andExpect(queryParam("fromBlock", String.valueOf(DEPLOY)))
                .andRespond(withSuccess(respuesta("1", "OK", llena), MediaType.APPLICATION_JSON));
        // el último del pedido anterior vuelve a venir: es el mismo evento
        api.expect(requestTo(startsWith(API)))
                .andExpect(queryParam("fromBlock", String.valueOf(ultimo)))
                .andRespond(withSuccess(respuesta("1", "OK",
                        log(referencia(999), hash(999), ultimo, 0) + "," + log(referencia(1000), hash(1000), ultimo + 5, 0)),
                        MediaType.APPLICATION_JSON));

        List<Evento> leidos = lector.cargasRegistradas();

        assertThat(leidos).hasSize(EventosDelContrato.POR_PEDIDO + 1);
        assertThat(leidos.get(leidos.size() - 1).bloque()).isEqualTo(ultimo + 5);
        api.verify();
    }

    @Test
    @DisplayName("Si la API responde un error, no contesta o no hay API key, es 503 RED_NO_DISPONIBLE")
    void errores() {
        EventosDelContrato lector = eventos("clave");
        api.expect(requestTo(startsWith(API)))
                .andRespond(withSuccess(respuesta("0", "NOTOK", "\"Invalid API Key\""), MediaType.APPLICATION_JSON));
        assertRedNoDisponible(lector);

        lector = eventos("clave");
        api.expect(requestTo(startsWith(API))).andRespond(withServerError());
        assertRedNoDisponible(lector);

        lector = eventos("");
        api.expect(ExpectedCount.never(), requestTo(startsWith(API)));
        assertRedNoDisponible(lector);
        api.verify();
    }

    private static void assertRedNoDisponible(EventosDelContrato lector) {
        assertThatThrownBy(lector::cargasRegistradas).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getCodigo()).isEqualTo(CodigoError.RED_NO_DISPONIBLE);
        });
    }

    /** {@code resultado}: los logs separados por coma, o un texto JSON ya entre comillas. */
    private static String respuesta(String status, String message, String resultado) {
        String result = resultado.startsWith("\"") ? resultado : "[" + resultado + "]";
        return "{\"status\":\"" + status + "\",\"message\":\"" + message + "\",\"result\":" + result + "}";
    }

    private static String log(String referencia, String hash, long bloque, int posicion) {
        return """
                {"address":"%s","topics":["%s","%s","%s"],"data":"0x00000000000000000000000000000000000000000000000000005af3107a4000",
                 "blockNumber":"0x%x","timeStamp":"0x6730c0a0","transactionHash":"%s","logIndex":"0x%x"}"""
                .formatted(CONTRATO.toLowerCase(), LectorDeCadena.TOPIC_CARGA_REGISTRADA, referencia, TOPIC_PAGADOR,
                        bloque, hash, posicion);
    }

    private static String referencia(int i) {
        return "0x%064x".formatted(i + 1);
    }

    private static String hash(int i) {
        return "0x%064x".formatted(1_000_000 + i);
    }
}
