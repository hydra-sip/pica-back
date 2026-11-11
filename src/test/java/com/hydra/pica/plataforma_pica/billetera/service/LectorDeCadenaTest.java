package com.hydra.pica.plataforma_pica.billetera.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.CargaRegistrada;
import com.hydra.pica.plataforma_pica.billetera.service.LectorDeCadena.Transaccion;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Lo que el back lee de Base Sepolia por JSON-RPC (HU-609, PICA-333), sin salir a la red: los recibos son los de
 * dos transacciones reales al contrato desplegado, una carga y una que revirtió por pagar dos veces la referencia.
 */
class LectorDeCadenaTest {

    private static final String RPC = "https://rpc.example";
    static final String HASH_CARGA = "0x2f27d3c26ea9b5b2dd0a441a17a70ea3dc2830757e2f2cf564f0cf6d9479e9eb";
    static final String REFERENCIA = "0x6d6f26c23203ae638686312ea1c8b3d6c83c0e4c13c347dff06c98548aefa16c";
    static final String PAGADOR = "0x262dfbfa37436e5b99a53377e633509d1a9b301a";
    static final String CONTRATO = "0x36e633edd1e79ef2b9a3c6a099d873d52e44edf0";

    private MockRestServiceServer nodo;
    private LectorDeCadena lector;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        nodo = MockRestServiceServer.bindTo(builder).build();
        lector = new LectorDeCadena(new CriptoProperties(RPC, CONTRATO, 84532, 3, Duration.ofSeconds(5),
                new BigInteger("10000000000"), "https://sepolia.basescan.org"), builder);
    }

    @Test
    void deUnaCargaMinadaLeeElBloqueYElEventoConLaReferenciaElPagadorYElMonto() {
        nodo.expect(requestTo(RPC))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.method").value("eth_getTransactionReceipt"))
                .andExpect(jsonPath("$.params[0]").value(HASH_CARGA))
                .andRespond(withSuccess(fixture("recibo-carga.json"), MediaType.APPLICATION_JSON));

        Transaccion transaccion = lector.transaccion(HASH_CARGA);

        assertThat(transaccion).isEqualTo(new Transaccion.Minada(true, 57254703,
                java.util.List.of(new CargaRegistrada(CONTRATO, REFERENCIA, PAGADOR,
                        new BigInteger("100000000000000")))));
        nodo.verify();
    }

    @Test
    void unaTransaccionQueRevirtioEsUnaMinadaFallidaYSinEventos() {
        nodo.expect(requestTo(RPC))
                .andExpect(jsonPath("$.method").value("eth_getTransactionReceipt"))
                .andRespond(withSuccess(fixture("recibo-revertido.json"), MediaType.APPLICATION_JSON));

        assertThat(lector.transaccion("0x0ed937d5d4fa02945051caacb80218b40b77548c711a0c9e65259c4159f38944"))
                .isEqualTo(new Transaccion.Minada(false, 57254740, java.util.List.of()));
    }

    @Test
    void elBloqueActualEsElQueDiceElNodo() {
        nodo.expect(requestTo(RPC))
                .andExpect(jsonPath("$.method").value("eth_blockNumber"))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x369a42f\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(lector.bloqueActual()).isEqualTo(57254959L);
    }

    static String fixture(String nombre) {
        try (InputStream in = LectorDeCadenaTest.class.getResourceAsStream("/cripto/" + nombre)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
