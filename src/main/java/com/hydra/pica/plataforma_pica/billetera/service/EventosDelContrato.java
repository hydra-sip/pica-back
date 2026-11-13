package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigInteger;
import java.util.LinkedHashMap;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Todos los {@code CargaRegistrada} que emitió el contrato desde su deploy, para la conciliación (HU-608, PICA-343).
 * Van por la API de Etherscan v2 y no por el nodo: el nodo público de Base Sepolia da los eventos de a 200 bloques, y
 * desde el deploy hay millones. La API devuelve hasta 1000 por pedido: si llega uno lleno, se sigue desde su último
 * bloque, y los repetidos se descartan por transacción y posición. Si la API no contesta, falla o no hay API key, es
 * 503 {@code RED_NO_DISPONIBLE}.
 */
@Component
public class EventosDelContrato {

    static final int POR_PEDIDO = 1000;
    private static final Logger log = LoggerFactory.getLogger(EventosDelContrato.class);

    private final RestClient http;
    private final CriptoProperties cripto;

    @Autowired
    EventosDelContrato(CriptoProperties cripto, RestClient.Builder builder) {
        this(builder.clone().requestFactory(conTimeout(cripto)), cripto);
    }

    // los tests le pasan un builder atado a una API simulada, sin pisarle la request factory
    EventosDelContrato(RestClient.Builder builder, CriptoProperties cripto) {
        this.http = builder.build();
        this.cripto = cripto;
    }

    private static SimpleClientHttpRequestFactory conTimeout(CriptoProperties cripto) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(cripto.timeout());
        fabrica.setReadTimeout(cripto.timeout());
        return fabrica;
    }

    /** Un {@code CargaRegistrada} con la transacción y el bloque donde se emitió. Hex en minúscula. */
    public record Evento(String referencia, String pagador, BigInteger monto, String hash, long bloque) {
    }

    /** Del más viejo al más nuevo. */
    public List<Evento> cargasRegistradas() {
        if (cripto.exploradorApiKey() == null || cripto.exploradorApiKey().isBlank()) {
            log.warn("Sin app.cripto.explorador-api-key no se pueden leer los eventos del contrato");
            throw noDisponible();
        }
        Map<String, Evento> eventos = new LinkedHashMap<>();
        long desde = cripto.bloqueDeploy();
        while (true) {
            JsonNode pagina = pedir(desde);
            for (JsonNode fila : pagina) {
                eventos.putIfAbsent(fila.path("transactionHash").asText() + "#" + fila.path("logIndex").asText(),
                        evento(fila));
            }
            if (pagina.size() < POR_PEDIDO) {
                return List.copyOf(eventos.values());
            }
            long ultimo = cantidad(pagina.get(pagina.size() - 1).path("blockNumber").asText()).longValueExact();
            // un bloque con más de 1000 eventos no se puede partir: no pasa con este contrato
            desde = ultimo > desde ? ultimo : ultimo + 1;
        }
    }

    private JsonNode pedir(long desde) {
        JsonNode respuesta;
        try {
            respuesta = http.get()
                    .uri(cripto.exploradorApiUrl() + "?chainid={chain}&module=logs&action=getLogs&address={contrato}"
                                    + "&topic0={topic}&fromBlock={desde}&toBlock=latest&page=1&offset={offset}"
                                    + "&apikey={key}",
                            cripto.chainId(), cripto.contrato(), LectorDeCadena.TOPIC_CARGA_REGISTRADA, desde,
                            POR_PEDIDO, cripto.exploradorApiKey())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            log.warn("La API del explorador no respondió: {}", e.getMessage());
            throw noDisponible();
        }
        if (respuesta == null) {
            throw noDisponible();
        }
        JsonNode resultado = respuesta.path("result");
        // sin eventos responde status 0 y "No records found" con la lista vacía; un error trae un texto en result
        if (resultado.isArray() && ("1".equals(respuesta.path("status").asText()) || resultado.isEmpty())) {
            return resultado;
        }
        log.warn("La API del explorador respondió un error: {} {}", respuesta.path("message").asText(), resultado);
        throw noDisponible();
    }

    private static Evento evento(JsonNode fila) {
        JsonNode topics = fila.path("topics");
        return new Evento(
                minuscula(topics.get(1).asText()),
                "0x" + minuscula(topics.get(2).asText()).substring(26),
                cantidad(fila.path("data").asText()),
                minuscula(fila.path("transactionHash").asText()),
                cantidad(fila.path("blockNumber").asText()).longValueExact());
    }

    private static ApiException noDisponible() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, CodigoError.RED_NO_DISPONIBLE,
                "No se pudieron leer los eventos del contrato en Base Sepolia. Probá de nuevo en un rato");
    }

    // la API devuelve los números en hex con 0x, como el nodo
    private static BigInteger cantidad(String hex) {
        return new BigInteger(hex.substring(2), 16);
    }

    private static String minuscula(String hex) {
        return hex.toLowerCase(Locale.ROOT);
    }
}
