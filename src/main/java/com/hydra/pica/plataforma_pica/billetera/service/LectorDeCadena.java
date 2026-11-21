package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.hydra.pica.plataforma_pica.common.config.CriptoConfig.CriptoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Lo que el back lee de Base Sepolia por JSON-RPC para confirmar una carga con cripto (HU-609, PICA-333): el recibo
 * de una transacción, con los {@code CargaRegistrada} que emitió, y el bloque actual. Devuelve lo que dice la cadena,
 * sin decidir si alcanza para acreditar: eso es de {@link CargaCriptoService}. Si el nodo no contesta a tiempo,
 * falla o devuelve un error de JSON-RPC, es 503 {@code RED_NO_DISPONIBLE}: el problema no es de la transacción.
 */
@Component
public class LectorDeCadena {

    /** keccak256("CargaRegistrada(bytes32,address,uint256)"), el topic0 del evento de PasarelaCargas. */
    static final String TOPIC_CARGA_REGISTRADA = "0x3197a34ed765fdfa490419a8d5ed536a04869055bd7773ec777f2b99834e2534";

    private static final int TOPICS_CARGA_REGISTRADA = 3;
    private static final int INDICE_TOPIC_EVENTO = 0;
    private static final int INDICE_TOPIC_REFERENCIA = 1;
    private static final int INDICE_TOPIC_PAGADOR = 2;
    /** Un topic EVM es "0x" + 64 dígitos hex; la address de 20 bytes empieza en el índice 26. */
    private static final int OFFSET_DIRECCION_EN_TOPIC = 26;
    private static final String STATUS_TRANSACCION_EXITOSA = "0x1";
    private static final String PREFIJO_HEX = "0x";
    private static final int LONGITUD_PREFIJO_HEX = PREFIJO_HEX.length();
    private static final int BASE_HEXADECIMAL = 16;

    private static final Logger log = LoggerFactory.getLogger(LectorDeCadena.class);

    private final RestClient http;

    @Autowired
    LectorDeCadena(CriptoProperties cripto, RestClient.Builder builder) {
        this(cripto.rpcUrl(), builder.clone().requestFactory(conTimeout(cripto)));
    }

    // los tests le pasan un builder atado a un nodo simulado, sin pisarle la request factory
    LectorDeCadena(String rpcUrl, RestClient.Builder builder) {
        this.http = builder.baseUrl(rpcUrl).build();
    }

    private static SimpleClientHttpRequestFactory conTimeout(CriptoProperties cripto) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(cripto.timeout());
        fabrica.setReadTimeout(cripto.timeout());
        return fabrica;
    }

    /** Una transacción tal como la ve el nodo. */
    public sealed interface Transaccion {

        /** El nodo no la conoce: el hash está mal o nunca se mandó. */
        record Inexistente() implements Transaccion {
        }

        /** El nodo la tiene, pero todavía no entró en un bloque. */
        record Pendiente() implements Transaccion {
        }

        /** Entró en un bloque. {@code exitosa} es falso si revirtió; en ese caso no emitió eventos. */
        record Minada(boolean exitosa, long bloque, List<CargaRegistrada> cargas) implements Transaccion {
        }
    }

    /** Un {@code CargaRegistrada} de un log, con el contrato que lo emitió. Las direcciones y hex, en minúscula. */
    public record CargaRegistrada(String contrato, String referencia, String pagador, BigInteger monto) {
    }

    public Transaccion transaccion(String hash) {
        JsonNode recibo = llamar("eth_getTransactionReceipt", hash);
        if (recibo.isNull()) {
            // sin recibo puede estar en el mempool o no existir: el nodo distingue con la transacción
            return llamar("eth_getTransactionByHash", hash).isNull()
                    ? new Transaccion.Inexistente()
                    : new Transaccion.Pendiente();
        }
        List<CargaRegistrada> cargas = new ArrayList<>();
        for (JsonNode evento : recibo.path("logs")) {
            JsonNode topics = evento.path("topics");
            if (topics.size() == TOPICS_CARGA_REGISTRADA
                    && TOPIC_CARGA_REGISTRADA.equalsIgnoreCase(topics.get(INDICE_TOPIC_EVENTO).asText())) {
                cargas.add(new CargaRegistrada(
                        minuscula(evento.path("address").asText()),
                        minuscula(topics.get(INDICE_TOPIC_REFERENCIA).asText()),
                        direccionDesdeTopic(topics.get(INDICE_TOPIC_PAGADOR).asText()),
                        cantidad(evento.path("data").asText())));
            }
        }
        return new Transaccion.Minada(STATUS_TRANSACCION_EXITOSA.equals(recibo.path("status").asText()),
                cantidad(recibo.path("blockNumber").asText()).longValueExact(), List.copyOf(cargas));
    }

    public long bloqueActual() {
        return cantidad(llamar("eth_blockNumber").asText()).longValueExact();
    }

    private JsonNode llamar(String metodo, Object... params) {
        JsonNode respuesta;
        try {
            respuesta = http.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("jsonrpc", "2.0", "id", 1, "method", metodo, "params", List.of(params)))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("El nodo no respondió {}: {}", metodo, e.getMessage());
            throw redNoDisponible();
        }
        if (respuesta == null || respuesta.has("error") || !respuesta.has("result")) {
            log.warn("El nodo respondió {} con un error: {}", metodo, respuesta);
            throw redNoDisponible();
        }
        return respuesta.get("result");
    }

    private static ApiException redNoDisponible() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, CodigoError.RED_NO_DISPONIBLE,
                "No se pudo consultar la red de Base Sepolia. Probá de nuevo en un rato");
    }

    // los números del JSON-RPC van en hex con 0x
    private static BigInteger cantidad(String hex) {
        return new BigInteger(hex.substring(LONGITUD_PREFIJO_HEX), BASE_HEXADECIMAL);
    }

    private static String direccionDesdeTopic(String topic) {
        return PREFIJO_HEX + minuscula(topic).substring(OFFSET_DIRECCION_EN_TOPIC);
    }

    private static String minuscula(String hex) {
        return hex.toLowerCase(Locale.ROOT);
    }
}
