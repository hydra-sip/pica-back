package com.hydra.pica.plataforma_pica.common.almacenamiento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import com.hydra.pica.plataforma_pica.common.config.AlmacenamientoConfig.AlmacenamientoProperties;
import com.hydra.pica.plataforma_pica.common.error.ApiException;
import com.hydra.pica.plataforma_pica.common.error.CodigoError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Lo que pasa cuando Supabase Storage no contesta lo esperado (CE4-8): 502 en vez de un 500 sin causa. */
class AlmacenSupabaseTest {

    private static final String OBJETO = "https://proyecto.supabase.co/storage/v1/object";

    private MockRestServiceServer supabase;
    private AlmacenSupabase almacen;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        supabase = MockRestServiceServer.bindTo(builder).build();
        almacen = new AlmacenSupabase(new AlmacenamientoProperties("supabase", null,
                new AlmacenamientoProperties.Supabase("https://proyecto.supabase.co/", "clave", "reglamentos")), builder);
    }

    @Test
    void guardaEnElBucketConLaClave() {
        supabase.expect(requestTo(OBJETO + "/reglamentos/torneos/9/reglamento.pdf"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer clave"))
                .andRespond(withSuccess());

        almacen.guardar("torneos/9/reglamento.pdf", new byte[] {1}, "application/pdf");

        supabase.verify();
    }

    @Test
    void siStorageRechazaLaSubidaEs502() {
        supabase.expect(requestTo(OBJETO + "/reglamentos/torneos/9/reglamento.pdf"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Invalid Compact JWS\"}"));

        assertThatThrownBy(() -> almacen.guardar("torneos/9/reglamento.pdf", new byte[] {1}, "application/pdf"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(ex.getCodigo()).isEqualTo(CodigoError.ALMACENAMIENTO_NO_DISPONIBLE);
                });
    }

    @Test
    void sinConexionTambienEs502() {
        supabase.expect(requestTo(OBJETO + "/reglamentos/torneos/9/reglamento.pdf"))
                .andRespond(withException(new IOException("sin red")));

        assertThatThrownBy(() -> almacen.guardar("torneos/9/reglamento.pdf", new byte[] {1}, "application/pdf"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCodigo()).isEqualTo(CodigoError.ALMACENAMIENTO_NO_DISPONIBLE));
    }

    @Test
    void leerUnoQueNoExisteEsVacio() {
        supabase.expect(requestTo(OBJETO + "/authenticated/reglamentos/torneos/9/reglamento.pdf"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"not_found\"}"));

        assertThat(almacen.leer("torneos/9/reglamento.pdf")).isEmpty();
    }

    @Test
    void siStorageFallaAlLeerEs502() {
        supabase.expect(requestTo(OBJETO + "/authenticated/reglamentos/torneos/9/reglamento.pdf"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> almacen.leer("torneos/9/reglamento.pdf"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void borrarUnoQueNoExisteNoFalla() {
        supabase.expect(requestTo(OBJETO + "/reglamentos/torneos/9/reglamento.pdf"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        almacen.borrar("torneos/9/reglamento.pdf");

        supabase.verify();
    }
}
