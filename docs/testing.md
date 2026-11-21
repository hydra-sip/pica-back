# Estrategia de testing de PICA

Cómo se prueba PICA, qué herramienta se usa en cada nivel y por qué, qué corre en el CI y cómo correr cada cosa en local. Abarca los tres repos: `pica-back`, `pica-web` y `pica-contracts`.

## La pirámide

Arriba de todo hay pocos E2E, solo de los flujos donde se mueve plata o se decide un resultado: son los más lentos y los más frágiles, así que no se usan para probar lo que se puede probar más abajo (el «cono de helado» al revés). Abajo están los tests rápidos y aislados: unitarios, controllers con MockMvc y los de Vitest.

En el back, la integración pesa más que en la pirámide de manual: casi lo mismo que los unitarios y los controllers juntos. Es a propósito. La lógica que importa (custodia, liquidación, cupos, sorteos) vive en transacciones, bloqueos y SQL nativo, y probarla con la base mockeada daría tests verdes que no prueban nada. Igual cada test de integración tarda unos milisegundos, porque comparten el contenedor de Postgres y se deshacen con un rollback.

| Nivel | Repo | Herramienta | Tests | Tiempo en el CI |
|---|---|---|---|---|
| E2E (sistema levantado) | pica-web | Playwright | 17 | ~3 min, en paralelo con Vitest |
| Integración (con base real) | pica-back | Spring Boot Test + Testcontainers (Postgres 16) | 1.287 en 104 clases | ~7 min, los tres niveles del back juntos |
| Controllers (HTTP, sin base) | pica-back | `@WebMvcTest` + MockMvc | 393 en 45 clases | |
| Unitarios | pica-back | JUnit 5 + Mockito + AssertJ | 370 en 57 clases | |
| Componentes y páginas | pica-web | Vitest + Testing Library + MSW | 500 en 65 archivos | ~3 min, con el lint y el build |
| Contrato inteligente | pica-contracts | Foundry (unitarios, fuzz, invariantes e integración) | 31 | ~2 min, con cobertura y Slither |

Cantidades al 20/11/2026, contadas como las corre cada herramienta (un `@ParameterizedTest` cuenta un caso por fila): back 2.050 casos en 206 clases (`./mvnw verify`), front 500 en 65 archivos (`npm test`) y 17 E2E (`npm run e2e`). En el back, las 104 clases de integración son la mitad: es donde está la plata y el SQL nativo, que no tiene sentido probar sin la base.

## Qué se usa en cada nivel y por qué

### pica-back
- **Unitarios (JUnit 5, Mockito, AssertJ)**: la lógica que no necesita base, por ejemplo cálculos, validaciones, el armado de un mail o qué hace un listener cuando falla el SMTP. Mockito solo reemplaza lo que está afuera de la unidad (el `EmailService`, un repositorio en un listener), nunca lo que se prueba.
- **Controllers (`@WebMvcTest` + MockMvc)**: el contrato HTTP sin levantar la base. Cubre el código de estado, la forma del JSON, el `ProblemDetail` con su `codigo`, y el 401 y el 403 por permiso (`@WithMockUser(authorities = ...)`). Un controller se prueba siempre por HTTP, nunca llamando al método.
- **Integración (`@SpringBootTest` o `@DataJpaTest` + Testcontainers)**: el servicio con la base real (Postgres 16 en Docker, el mismo motor que en producción) y las migraciones de Flyway. Hay SQL nativo, CHECKs, índices parciales, bloqueos (`FOR UPDATE`) y baja lógica, y eso solo se prueba en serio contra Postgres: no se usa H2 ni repositorios mockeados. Cada test corre en una transacción que se deshace al final. Los flujos de plata además verifican al final que el balance cierre (`BalanceCero`).
- **Concurrencia**: veinte pagos por el último lugar, cargas con la misma referencia y retiros simultáneos, con hilos reales contra la base.
- **Cobertura**: JaCoCo, con un mínimo de 70 % de líneas que corta el build. Hoy: 94,8 % de líneas y 82,6 % de ramas.

### pica-web
- **Vitest + Testing Library + MSW**: se renderiza la ruta real (`renderRuta`) y la API se contesta con los handlers de MSW, que siguen el contrato (`openapi.yaml`). Se busca por rol y texto accesible, como lo usa una persona, y se prueba lo que se ve y lo que se manda al back, no el estado interno de los componentes.
- **Playwright (E2E)**: el front compilado sin mocks contra el back real (la imagen de `dev` con el seed). Cubre:
  - el login de cada rol;
  - la inscripción pagando con saldo;
  - un torneo de punta a punta hasta el premio;
  - la rifa con el sorteo verificado en el portal público;
  - la cancelación con gastos aprobada por soporte;
  - el acceso al backoffice por permisos.

  Usa Page Objects (`e2e/pages/`), y los datos de cada test se arman por la API (`e2e/support/datos.ts`), no con clics. Cada test crea lo suyo con un sufijo único, así no dependen del orden.

### pica-contracts
- **Foundry**: tests unitarios del contrato `PasarelaCargas` y fuzzing de los montos y las referencias. Además hay invariantes: el saldo del contrato cubre lo cargado menos lo retirado, y solo figuran pagadas las referencias que se pagaron. Y tests de integración (varios pagadores con retiros parciales, una devolución que intenta reentrar) y del script de deploy. La pasarela pide más del 90 % de cobertura; hoy es del 100 % de líneas, sentencias, ramas y funciones (`forge coverage`). El análisis estático corre con Slither.

## Qué corre en el CI

| Repo | Job | Cuándo | Qué hace | ¿Obligatorio para mergear? |
|---|---|---|---|---|
| pica-back | `verify` | cada PR y cada push a dev y main | `./mvnw verify`: todos los tests y el mínimo de JaCoCo | sí, a dev y a main |
| pica-back | `imagen` | después de `verify` | construye la imagen Docker; en dev además la publica en GHCR | sí, a dev y a main |
| pica-back | `migraciones` | cada PR | que las migraciones nuevas sigan a la última de dev, sin números repetidos | sí, a dev |
| pica-back | `contrato` | cada push a dev, después del deploy | la prueba de humo del contrato contra staging (`scripts/prueba-de-humo.sh`) | no: corre después del merge |
| pica-web | `verify` | cada PR y cada push a dev y main | lint (sin warnings), Vitest y build | sí, a dev y a main |
| pica-web | `imagen` | después de `verify` | construye la imagen; en dev además la publica | sí, a dev y a main |
| pica-web | `e2e` | cada PR y cada push a dev y main, en paralelo con `verify` | Playwright contra el back de dev; si falla, sube el reporte, las trazas y el log del back | todavía no: deja el PR en rojo, pero falta sumarlo a la protección de la rama |
| pica-contracts | `contratos` | cada PR y cada push | `forge fmt --check`, build, tests, cobertura de al menos 90 % y Slither | sí, a dev |

Los reportes quedan como artifacts del run: `jacoco` y `surefire-reports` en el back, y `playwright-report` en el front.

## Cómo correr cada nivel en local

```bash
# pica-back (necesita Docker para Testcontainers)
./mvnw verify                          # todo, con la cobertura en target/site/jacoco/index.html
./mvnw test -Dtest=CargaIntegracionTest
./mvnw test -Dtest='CargaIntegracionTest#cargaExitosaViaEndpointAcreditaSaldoYRegistraMovimiento'

# pica-web
npm test                               # Vitest
npx vitest src/torneos                 # una carpeta, en modo watch
npx playwright install chromium        # una vez
npm run e2e:back && npm run e2e        # E2E: el back en Docker y los tests headless
npx playwright show-report             # el reporte, con capturas y trazas de lo que falló

# pica-contracts
forge test -vvv
forge coverage --report summary --no-match-coverage '^(test|script)/'
```

## Cómo se arma un test

Cada test tiene tres partes separadas por una línea en blanco: **Arrange** (los datos), **Act** (una sola acción, la que se prueba) y **Assert** (lo que tiene que pasar, con valores concretos). El nombre dice qué se prueba, en castellano y como una regla de negocio. Por ejemplo, `ParametrosPlataformaIntegracionTest`:

```java
@ParameterizedTest(name = "{0} = {1} no entra")
@CsvSource({
        "MARGEN_CIERRE_INICIO_MIN, 1.5",
        "MARGEN_CIERRE_INICIO_MIN, -1",
        "GASTOS_ADMINISTRATIVOS, 101",
        "GASTOS_ADMINISTRATIVOS, -0.01"
})
void unValorFueraDeRangoLoRechazaLaBase(String clave, BigDecimal valor) {
    assertThatThrownBy(() -> jdbc.update("UPDATE parametro_plataforma SET valor = ? WHERE clave = ?", valor, clave))
            .isInstanceOf(DataIntegrityViolationException.class);
}
```

Y un E2E (`e2e/tests/inscripcion.spec.ts`):

```ts
test('inscribirse a un torneo pagando con saldo deja el total retenido en custodia', async ({ admin, organizador, request, sesionDe }) => {
  const torneo = await torneoPublicado(organizador);                         // Arrange, por la API
  const jugador = await jugadorConSaldo(admin, request, 'inscripto', 5000);
  const page = await sesionDe(jugador.username);
  const ficha = new FichaTorneoPage(page);
  const billetera = new BilleteraPage(page);

  await ficha.abrir(torneo.slug);                                            // Act, por la UI
  await ficha.inscribirmePagandoConSaldo();

  await expect(ficha.confirmada).toBeVisible();                              // Assert, con montos exactos
  await billetera.abrir();
  await billetera.esperarSaldo('Disponible', 3900);
  await billetera.esperarSaldo('Retenido (en custodia)', 1100);
});
```

## Antipatrones que revisamos (PICA-440)

La suite se revisó contra estos antipatrones; el detalle de lo que se cambió está en el PR de PICA-440.

| Antipatrón | Qué hacemos en cambio |
|---|---|
| Test sin assert | Siempre un assert o un `verify`; si lo que se prueba es que no falla, `assertThatCode(...).doesNotThrowAnyException()` |
| Assert genérico (`isNotEmpty`, `isPositive`, `toBeTruthy`, `>= 0`) | El valor esperado exacto |
| Calcular el esperado repitiendo la lógica del método | El número escrito a mano, con un comentario de dónde sale |
| Probar el comportamiento de un mock | Mockito solo para lo externo; se prueba el resultado de la unidad |
| `InOrder` cuando importa el resultado | Se verifica el estado final |
| `try/catch` para esperar una excepción | `assertThatThrownBy` / `assertThrows` (el `try/catch` queda solo para juntar resultados de hilos) |
| Nombres como `testCaminoFeliz` | Un nombre que diga la regla: `unTorneoDadoDeBajaNoSeConfirma` |
| Controller llamado como un método | MockMvc, por HTTP |
| SQL nativo contra H2 o un mock | Testcontainers con Postgres |
| El mismo test copiado con otro valor | `@ParameterizedTest` en el back, `test.each` en Vitest |
| `waitForTimeout` fijo en un E2E | Las esperas de Playwright (`expect(...).toBeVisible()`, `toPass`, `expect.poll`) |
