package com.hydra.pica.plataforma_pica.billetera.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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

    /** Una palabra de la EVM en hex: 0x y 32 bytes. Así vienen los topics y el uint256 del evento. */
    private static final Pattern PALABRA = Pattern.compile("^0x[0-9a-fA-F]{64}$");

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
            cargaRegistrada(evento).ifPresent(cargas::add);
        }
        return new Transaccion.Minada("0x1".equals(recibo.path("status").asText()),
                cantidad(recibo.path("blockNumber").asText()).longValueExact(), List.copyOf(cargas));
    }

    /**
     * Un log con la firma de {@code CargaRegistrada}. Cualquier contrato puede emitir un log con ese topic0 (una smart
     * wallet que agrupa llamadas lo mete en la misma transacción), así que uno que no tenga la forma del evento de la
     * pasarela (dos topics y un uint256 de datos, todos de 32 bytes) se ignora: no puede impedir leer el verdadero.
     */
    private static Optional<CargaRegistrada> cargaRegistrada(JsonNode evento) {
        JsonNode topics = evento.path("topics");
        if (topics.size() != 3 || !TOPIC_CARGA_REGISTRADA.equalsIgnoreCase(topics.get(0).asText())) {
            return Optional.empty();
        }
        String referencia = topics.get(1).asText();
        String pagador = topics.get(2).asText();
        String monto = evento.path("data").asText();
        if (!PALABRA.matcher(referencia).matches() || !PALABRA.matcher(pagador).matches()
                || !PALABRA.matcher(monto).matches()) {
            log.warn("Se ignora un log con la firma de CargaRegistrada que no tiene su forma, de {}",
                    evento.path("address").asText());
            return Optional.empty();
        }
        return Optional.of(new CargaRegistrada(
                minuscula(evento.path("address").asText()),
                minuscula(referencia),
                "0x" + minuscula(pagador).substring(26),
                cantidad(monto)));
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
        return new BigInteger(hex.substring(2), 16);
    }

    private static String minuscula(String hex) {
        return hex.toLowerCase(Locale.ROOT);
    }
}
