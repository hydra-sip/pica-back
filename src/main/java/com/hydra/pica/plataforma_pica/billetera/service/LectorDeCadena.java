package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Lo que el back lee de Base Sepolia por JSON-RPC para confirmar una carga con cripto (HU-609, PICA-333): el recibo
 * de una transacción, con los {@code CargaRegistrada} que emitió, y el bloque actual. Devuelve lo que dice la cadena,
 * sin decidir si alcanza para acreditar: eso es de {@link CargaCriptoService}.
 */
@Component
public class LectorDeCadena {

    /** keccak256("CargaRegistrada(bytes32,address,uint256)"), el topic0 del evento de PasarelaCargas. */
    static final String TOPIC_CARGA_REGISTRADA = "0x3197a34ed765fdfa490419a8d5ed536a04869055bd7773ec777f2b99834e2534";

    private final RestClient http;

    LectorDeCadena(CriptoProperties cripto, RestClient.Builder builder) {
        this.http = builder.baseUrl(cripto.rpcUrl()).build();
    }

    /** Una transacción tal como la ve el nodo. */
    public sealed interface Transaccion {

        /** Entró en un bloque. {@code exitosa} es falso si revirtió; en ese caso no emitió eventos. */
        record Minada(boolean exitosa, long bloque, List<CargaRegistrada> cargas) implements Transaccion {
        }
    }

    /** Un {@code CargaRegistrada} de un log, con el contrato que lo emitió. Las direcciones y hex, en minúscula. */
    public record CargaRegistrada(String contrato, String referencia, String pagador, BigInteger monto) {
    }

    public Transaccion transaccion(String hash) {
        JsonNode recibo = llamar("eth_getTransactionReceipt", hash);
        List<CargaRegistrada> cargas = new ArrayList<>();
        for (JsonNode log : recibo.path("logs")) {
            JsonNode topics = log.path("topics");
            if (topics.size() == 3 && TOPIC_CARGA_REGISTRADA.equalsIgnoreCase(topics.get(0).asText())) {
                cargas.add(new CargaRegistrada(
                        minuscula(log.path("address").asText()),
                        minuscula(topics.get(1).asText()),
                        "0x" + minuscula(topics.get(2).asText()).substring(26),
                        cantidad(log.path("data").asText())));
            }
        }
        return new Transaccion.Minada("0x1".equals(recibo.path("status").asText()),
                cantidad(recibo.path("blockNumber").asText()).longValueExact(), List.copyOf(cargas));
    }

    public long bloqueActual() {
        return cantidad(llamar("eth_blockNumber").asText()).longValueExact();
    }

    private JsonNode llamar(String metodo, Object... params) {
        JsonNode respuesta = http.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", metodo, "params", List.of(params)))
                .retrieve()
                .body(JsonNode.class);
        return respuesta.path("result");
    }

    // los números del JSON-RPC van en hex con 0x
    private static BigInteger cantidad(String hex) {
        return new BigInteger(hex.substring(2), 16);
    }

    private static String minuscula(String hex) {
        return hex.toLowerCase(Locale.ROOT);
    }
}
