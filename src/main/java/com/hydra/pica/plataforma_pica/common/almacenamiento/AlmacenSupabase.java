package com.hydra.pica.plataforma_pica.common.almacenamiento;

import java.util.Optional;
import java.util.function.Supplier;

import com.hydra.pica.plataforma_pica.common.config.AlmacenamientoConfig.AlmacenamientoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * En un bucket de Supabase Storage, por su API REST. El bucket es privado y se entra con la clave service_role, que
 * no sale del back: los archivos los sirve la API. Para Storage un archivo que no existe es un 400 o un 404.
 * Cualquier otra respuesta o una falla de red es 502 {@code ALMACENAMIENTO_NO_DISPONIBLE}; lo que contestó Storage
 * queda en el log (CE4-8), porque al cliente no le sirve y un 500 genérico no dejaba ver la causa.
 */
@Component
@ConditionalOnProperty(name = "app.almacenamiento.tipo", havingValue = "supabase")
class AlmacenSupabase implements AlmacenArchivos {

    private static final Logger log = LoggerFactory.getLogger(AlmacenSupabase.class);

    private final RestClient http;
    private final String bucket;

    AlmacenSupabase(AlmacenamientoProperties properties, RestClient.Builder builder) {
        AlmacenamientoProperties.Supabase supabase = properties.supabase();
        if (vacio(supabase.url()) || vacio(supabase.clave())) {
            throw new IllegalStateException(
                    "Con app.almacenamiento.tipo=supabase faltan APP_ALMACENAMIENTO_SUPABASE_URL o _CLAVE");
        }
        this.http = builder.baseUrl(supabase.url().replaceAll("/+$", "") + "/storage/v1/object")
                .defaultHeader("apikey", supabase.clave())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + supabase.clave())
                .build();
        this.bucket = supabase.bucket();
    }

    @Override
    public void guardar(String clave, byte[] contenido, String tipoContenido) {
        llamar("guardar", clave, () -> http.post()
                .uri("/" + bucket + "/" + clave)
                .header("x-upsert", "true")
                .contentType(MediaType.parseMediaType(tipoContenido))
                .body(contenido)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public Optional<byte[]> leer(String clave) {
        return llamar("leer", clave, () -> http.get()
                .uri("/authenticated/" + bucket + "/" + clave)
                .exchange((request, response) -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                        return Optional.of(response.getBody().readAllBytes());
                    }
                    if (noExiste(response.getStatusCode())) {
                        return Optional.empty();
                    }
                    log.error("Supabase Storage respondió {} al leer {}/{}: {}", response.getStatusCode().value(),
                            bucket, clave, new String(response.getBody().readAllBytes()));
                    throw noDisponible("leer");
                }));
    }

    @Override
    public void borrar(String clave) {
        llamar("borrar", clave, () -> http.delete()
                .uri("/" + bucket + "/" + clave)
                .retrieve()
                .onStatus(AlmacenSupabase::noExiste, (request, response) -> { })
                .toBodilessEntity());
    }

    /** Lo que no sea la respuesta esperada pasa a 502, con lo que dijo Storage en el log. */
    private <T> T llamar(String accion, String clave, Supplier<T> llamada) {
        try {
            return llamada.get();
        } catch (RestClientResponseException ex) {
            log.error("Supabase Storage respondió {} al {} {}/{}: {}", ex.getStatusCode().value(), accion, bucket,
                    clave, ex.getResponseBodyAsString());
            throw noDisponible(accion);
        } catch (RestClientException ex) {
            log.error("No se pudo conectar con Supabase Storage al {} {}/{}", accion, bucket, clave, ex);
            throw noDisponible(accion);
        }
    }

    private static ApiException noDisponible(String accion) {
        return new ApiException(HttpStatus.BAD_GATEWAY, CodigoError.ALMACENAMIENTO_NO_DISPONIBLE,
                "No se pudo " + accion + " el archivo: el almacenamiento no respondió. Probá de nuevo en un rato");
    }

    private static boolean noExiste(HttpStatusCode status) {
        return status.value() == 400 || status.value() == 404;
    }

    private static boolean vacio(String valor) {
        return valor == null || valor.isBlank();
    }
}
