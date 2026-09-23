package com.hydra.pica.plataforma_pica.common.error;

import com.hydra.pica.plataforma_pica.common.config.SecurityConfig;
import com.hydra.pica.plataforma_pica.common.config.WebConfig;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que cualquier error salga con la forma del contrato: problem+json, {@code codigo} siempre
 * y {@code errores} por campo en los 400. Usa un controller de prueba que solo existe acá.
 */
// El controller de prueba va en @Import además de en controllers=: al estar anidado en una clase de
// test, el escaneo de componentes lo saltea y sin el import responde 404.
@WebMvcTest(controllers = ApiExceptionHandlerTest.PruebaController.class)
@Import({SecurityConfig.class, WebConfig.class, ApiExceptionHandlerTest.PruebaController.class})
@WithMockUser
class ApiExceptionHandlerTest {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private final MockMvc mockMvc;

    @Autowired
    ApiExceptionHandlerTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    @DisplayName("Una ApiException sale con su status y su código")
    void apiExceptionConStatusYCodigo() throws Exception {
        mockMvc.perform(get("/prueba/conflicto"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.codigo").value("EMAIL_DUPLICADO"))
                .andExpect(jsonPath("$.detail").value("ya existe"))
                .andExpect(jsonPath("$.instance").value("/prueba/conflicto"));
    }

    @Test
    @DisplayName("Un @RequestParam que no cumple su anotación: 400 VALIDACION con el campo")
    void requestParamInvalido() throws Exception {
        mockMvc.perform(get("/prueba/tamano").param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("size"))
                .andExpect(jsonPath("$.errores[0].codigo").value("LONGITUD"));
    }

    @Test
    @DisplayName("Un parámetro que no se puede convertir: 400 VALIDACION con el campo")
    void parametroQueNoConvierte() throws Exception {
        mockMvc.perform(get("/prueba/numero/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("id"))
                .andExpect(jsonPath("$.errores[0].codigo").value("VALOR_INVALIDO"));
    }

    @Test
    @DisplayName("Las propiedades extra de la excepción salen como campos del ProblemDetail")
    void propiedadesExtra() throws Exception {
        mockMvc.perform(get("/prueba/con-propiedad"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("PERMISO_NO_ENCONTRADO"))
                .andExpect(jsonPath("$.invalidos[0]").value("PROYECTO_VER"));
    }

    @Test
    @DisplayName("Body inválido: 400 VALIDACION con un ítem por campo y el código según la anotación")
    void bodyInvalidoListaErroresPorCampo() throws Exception {
        mockMvc.perform(post("/prueba/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"\",\"email\":\"no-es-mail\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.length()").value(2))
                .andExpect(jsonPath("$.errores[?(@.campo=='nombre')].codigo").value("REQUERIDO"))
                .andExpect(jsonPath("$.errores[?(@.campo=='email')].codigo").value("FORMATO_INVALIDO"));
    }

    @Test
    @DisplayName("JSON roto: 400 VALIDACION sin lista de campos")
    void jsonRoto() throws Exception {
        mockMvc.perform(post("/prueba/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{esto no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores").doesNotExist());
    }

    @Test
    @DisplayName("Falta un query param obligatorio: 400 con REQUERIDO en ese campo")
    void faltaQueryParam() throws Exception {
        mockMvc.perform(get("/prueba/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores[0].campo").value("token"))
                .andExpect(jsonPath("$.errores[0].codigo").value("REQUERIDO"));
    }

    @Test
    @DisplayName("AccessDeniedException (el caso de @PreAuthorize) sale como 403 SIN_PERMISO, no como 500")
    void accesoDenegadoEs403() throws Exception {
        mockMvc.perform(get("/prueba/denegado"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("SIN_PERMISO"));
    }

    @Test
    @DisplayName("Una excepción no prevista es 500 ERROR_INTERNO y no filtra el mensaje")
    void inesperadaEs500SinDetalles() throws Exception {
        mockMvc.perform(get("/prueba/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.codigo").value("ERROR_INTERNO"))
                .andExpect(jsonPath("$.detail").value("Error interno"));
    }

    /** Controller mínimo para provocar cada tipo de error. No existe fuera de este test. */
    @RestController
    @RequestMapping("/prueba")
    static class PruebaController {

        record Body(@NotBlank String nombre, @Email String email) {}

        @PostMapping("/body")
        void body(@Valid @RequestBody Body body) {}

        @GetMapping("/conflicto")
        void conflicto() {
            throw new ConflictoException(CodigoError.EMAIL_DUPLICADO, "ya existe");
        }

        @GetMapping("/tamano")
        void tamano(@RequestParam @Max(100) int size) {}

        @GetMapping("/numero/{id}")
        void numero(@PathVariable Long id) {}

        @GetMapping("/con-propiedad")
        void conPropiedad() {
            throw new NoEncontradoException(CodigoError.PERMISO_NO_ENCONTRADO, "no existen")
                    .con("invalidos", List.of("PROYECTO_VER"));
        }

        @GetMapping("/param")
        void param(@RequestParam String token) {}

        @GetMapping("/denegado")
        void denegado() {
            throw new AccessDeniedException("sin permiso");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("detalle interno que no debe salir");
        }
    }
}
