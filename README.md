# Plataforma PICA - Backend API

Backend para la plataforma de torneos competitivos y rifas. Construido con arquitectura modular orientada a features (*package-by-feature*), alta cohesión y bajo acoplamiento.

## Stack Tecnológico

- **Java 21** (LTS)
- **Spring Boot 3.5.16**
  - Spring Web (MVC)
  - Spring Data JPA
  - Spring Boot Actuator
  - Spring Boot Docker Compose Support
  - Spring Boot Validation
- **PostgreSQL 16** (Local mediante Docker Compose / Producción en Supabase Postgres gestionado)
- **Maven** con Maven Wrapper (`./mvnw`)
- **Lombok**
- **Testcontainers** (Pruebas de integración con PostgreSQL real)

---

## Requisitos Previos

- **JDK 21** o superior instalado y configurado en `JAVA_HOME`.
- **Docker** y **Docker Compose** en ejecución (para la base de datos local).

---

## Inicio Rápido

Gracias al soporte nativo de **Spring Boot Docker Compose**, levantar el proyecto inicia automáticamente el contenedor de PostgreSQL 16 si no está corriendo:

```bash
# 1. Clonar el repositorio y posicionarse en el proyecto
cd pica-back

# 2. Ejecutar la aplicación con Maven Wrapper
./mvnw spring-boot:run
```

*(Opcional) Si preferís gestionar Docker manualmente:*
```bash
docker compose up -d
./mvnw spring-boot:run
```

### Datos de prueba (perfil `seed`)

`./mvnw spring-boot:run` arranca con los perfiles `dev` (claves JWT efímeras, Google opcional) y `seed`: además de las migraciones, Flyway corre el seed `db/seed/R__seed_desarrollo.sql`. La imagen Docker, el CI y Railway no lo usan. Para arrancar sin datos de prueba: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`.

Todos los usuarios tienen la contraseña `PicaDev2026`, también el `admin` (salvo que se pase `ADMIN_INITIAL_PASSWORD`):

| Usuario | Rol |
|---|---|
| `dev_organizador` | Organizador |
| `dev_arbitro` | Árbitro |
| `dev_jugador1` a `dev_jugador6` | Participante |

- **Equipos:** Los Halcones (capitán `dev_jugador1`, con 2 y 3) y Las Panteras (capitán `dev_jugador4`, con 5 y 6).
- **Copa abierta** (`dev-copa-abierta`): por equipos, con la inscripción abierta, Los Halcones inscriptos y pagados, y la rifa en venta (números 1 a 100; 7, 13 y 42 vendidos a `dev_jugador5`).
- **Torneo cerrado** (`dev-torneo-cerrado`): individual, con `dev_jugador1` a 4 confirmados, el árbitro designado y el bracket generado: dos semifinales con sus lados y la final vacía.

El seed es repetible: si se modifica, Flyway lo vuelve a correr en el próximo arranque y solo agrega lo que falta.

### Probar que levantó

Una vez levantada la aplicación en `http://localhost:8080`:

```bash
curl -i http://localhost:8080/actuator/health
```

Responde 200 con `"status": "UP"` si la app y la base andan, y 503 con `DOWN` si la base no responde. Es el mismo chequeo que usan el `HEALTHCHECK` de la imagen y el deploy a staging.

### Prueba de humo del contrato

`scripts/prueba-de-humo.sh` recorre todas las operaciones de `docs/api/openapi.yaml` contra una API levantada y compara cada código HTTP con el del contrato. Termina con `Contrato: 35/35 operaciones` y falla si alguna operación quedó sin probar, así que un endpoint nuevo en el contrato obliga a sumarlo al script. Los torneos todavía no tienen API: para probar las inscripciones el script carga uno por SQL y lo borra al limpiar.

Crea un rol, una persona y dos usuarios marcados con el id de la corrida (`humo-…`). Al final los borra de la base por SQL, porque la API solo hace bajas lógicas. La limpieza corre aunque un paso falle o se corte con Ctrl+C, y al final verifica que no quedó nada de la corrida. Si no puede llegar a la base, no arranca. Lo único que queda son las líneas de log de la API.

```bash
# Local: la app en localhost:8080 y el Postgres de docker-compose.yml. Pide la clave del admin.
scripts/prueba-de-humo.sh

# Staging: la limpieza va por SSH y la clave del admin se lee del .env de la instancia
PICA_URL=https://pica-hydra.duckdns.org \
PICA_SSH="-i $HOME/.ssh/pica-staging.pem ubuntu@pica-hydra.duckdns.org" \
scripts/prueba-de-humo.sh
```

Necesita `curl`, `jq` y `openssl`. En el CI corre sola contra staging después de cada deploy (job `contrato`).

### Verificación Externa de Tokens JWT (Verificador Público)

Para permitir que servicios externos (como verificadores públicos de sorteos o sistemas de auditoría independientes) validen la autenticidad y firma RS256 de los access tokens emitidos por la plataforma, la API expone públicamente la clave RSA pública en dos formatos estándar:

1. **JWKS (JSON Web Key Set)**: `GET /.well-known/jwks.json`
   - Retorna un conjunto de claves JSON en formato estándar RFC 7517.
   - Ideal para librerías automáticas de validación (Spring Security Resource Server, Auth0 `jwks-rsa`, PyJWT `PyJWKClient`, Go `golang-jwt`, etc.).

2. **Clave Pública PEM**: `GET /api/v1/auth/public-key`
   - Retorna la clave pública RSA en formato PEM (X.509 / PKCS#8).
   - Ideal para verificación manual o servicios con claves estáticas.

#### Cómo validar un Token en jwt.io

1. Obtener un access token emitiendo `POST /api/v1/auth/login` o registrándote en `POST /api/v1/auth/registro`.
2. Abrir el validador interactivo en [jwt.io](https://jwt.io).
3. Pegar el token en el campo **Encoded**.
4. Asegurar que el algoritmo seleccionado en el encabezado sea **RS256**.
5. Consultar la clave pública PEM invocando:
   ```bash
   curl -i http://localhost:8080/api/v1/auth/public-key
   ```
6. Copiar el bloque completo devuelto (incluyendo `-----BEGIN PUBLIC KEY-----` y `-----END PUBLIC KEY-----`) y pegarlo en el cuadro de texto **PUBLIC KEY** ("Verify Signature") en jwt.io.
7. El portal mostrará la confirmación verde **"Signature Verified"**.

Para ejecutar las pruebas unitarias y de integración:
```bash
./mvnw clean test
```

---

## Estructura del Proyecto

El código fuente sigue el patrón **Package by Feature**. Cada módulo de negocio contiene sus propias capas técnicas, favoreciendo el aislamiento, la legibilidad y la escalabilidad del sistema.

```text
src/main/java/com/hydra/pica/plataforma_pica/
├── PlataformaPicaApplication.java
├── common/
│   ├── config/
│   │   └── WebConfig.java
│   ├── dto/
│   ├── exception/
│   └── util/
├── payment/
│   ├── controller/
│   ├── domain/
│   ├── dto/
│   ├── repository/
│   └── service/
├── raffle/
│   ├── controller/
│   ├── domain/
│   ├── dto/
│   ├── repository/
│   └── service/
├── tournament/
│   ├── controller/
│   ├── domain/
│   ├── dto/
│   ├── repository/
│   └── service/
└── user/
    ├── controller/
    ├── domain/
    ├── dto/
    ├── repository/
    └── service/
```

### Descripción de Módulos de Negocio

- **`tournament`**: Gestión de torneos competitivos (creación, fases, brackets, inscripciones, partidas y resultados).
- **`raffle`**: Gestión de sorteos y rifas (publicación, compra y asignación de números/tickets, selección de ganadores).
- **`user`**: Gestión de perfiles de usuario, roles, preferencias y datos de competidores/organizadores.
- **`payment`**: Procesamiento de pagos, transacciones, reembolsos y pasarelas de pago para entradas a torneos y tickets de rifas.
- **`common`**: Componentes transversales compartidos por todos los módulos (configuraciones globales, manejo centralizado de excepciones, DTOs genéricos y utilidades).

### Descripción de Capas Internas (por Módulo)

- **`controller`**: Endpoints REST (`@RestController`). Reciben las peticiones HTTP, validan parámetros de entrada con `@Valid` y delegan la ejecución a los servicios.
- **`service`**: Lógica de negocio pura y orquestación transaccional (`@Service`, `@Transactional`).
- **`repository`**: Acceso a datos y consultas JPA/SQL (`@Repository`, `JpaRepository`).
- **`domain`**: Entidades JPA (`@Entity`), Value Objects, Enums y reglas de dominio.
- **`dto`**: Data Transfer Objects (records o clases inmutables) utilizados para desacoplar las entidades de la capa de presentación (Requests/Responses).

---

## Archivos Principales

- **`docker-compose.yml`**: Define el contenedor PostgreSQL 16 para desarrollo local con volumen persistente (`postgres_data`), base `tournament_db`, credenciales por defecto y un `healthcheck` basado en `pg_isready` para garantizar que la base acepte conexiones antes de inicializar la app.
- **`src/main/resources/application.yml`**: Configuración centralizada de Spring Boot. Define conexión JDBC flexible, hibernate DDL validate, zona horaria UTC, endpoints de Actuator, niveles de log detallados y orígenes CORS.
- **`common/config/WebConfig.java`**: Configura CORS globalmente a nivel Spring MVC (`WebMvcConfigurer`) sobre rutas `/api/**`. Lee dinámicamente los dominios autorizados de `app.cors.allowed-origins` y habilita métodos estándar (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`) con credenciales activadas.

---

## Configuración (`application.yml`)

Las principales directivas de configuración y su justificación técnica:

- **`spring.jpa.hibernate.ddl-auto: validate`**: En entornos serios/producción nunca se debe usar `update` o `create-drop`. `validate` asegura que las entidades JPA coincidan exactamente con el esquema de base de datos existente sin alterar tablas ni datos accidentalmente. (Las migraciones se gestionarán vía Flyway/Liquibase).
- **`spring.jpa.open-in-view: false`**: Desactiva el patrón anti-práctica *Open Session in View* (OSIV). Evita que la sesión de Hibernate permanezca abierta durante la renderización de la respuesta HTTP, previniendo queries N+1 invisibles y agotamiento del pool de conexiones.
- **`hibernate.jdbc.time_zone: UTC`**: Fuerza a Hibernate y al driver JDBC a almacenar y recuperar fechas en formato UTC, evitando desfasajes de zona horaria entre entornos de desarrollo, servidores y la base de datos Supabase.
- **`management.endpoints.web.exposure.include: health,info`**: Principio de menor privilegio para observabilidad. Solo expone las rutas necesarias para chequeos de salud y metadatos básicos, ocultando métricas sensibles o endpoints administrativos.
- **Variables de entorno con valores por defecto (`${VAR:default}`)**: Permite que el proyecto funcione de inmediato en local (`localhost:5432/tournament_db`) sin configurar nada, mientras que en staging/producción (Supabase) las credenciales se sobreescriben fácilmente mediante variables de entorno del sistema (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`).
- **`spring.docker.compose.lifecycle-management: start-only`**: Le indica a Spring Boot que levante el contenedor si no está corriendo al iniciar el proyecto, pero que **no lo destruya ni apague** al detener la aplicación, optimizando los tiempos de reinicio durante el desarrollo.

---

## Convenciones de Código

### 1. Inyección de Dependencias por Constructor (No `@Autowired` en campos)

En este proyecto **está prohibido** el uso de `@Autowired` sobre campos privados (`field injection`):

```java
// ❌ INCORRECTO: Acoplamiento fuerte, dificulta testing, oculta dependencias
@Autowired
private TournamentRepository tournamentRepository;

// ✅ CORRECTO: Dependencias explícitas, inmutables (final), fácil de mockear en tests unitarios
private final TournamentRepository tournamentRepository;

public TournamentService(TournamentRepository tournamentRepository) {
    this.tournamentRepository = tournamentRepository;
}
// (O utilizando @RequiredArgsConstructor de Lombok si la clase es extensa)
```

**Razones:**
1. **Inmutabilidad**: Permite declarar campos `final`.
2. **Testabilidad pura**: Se pueden instanciar las clases en tests unitarios con `new MyService(mockRepo)` sin necesidad de levantar el contexto de Spring ni Reflection.
3. **Detección temprana de dependencias circulares y sobrecargas**: Si un constructor tiene demasiados parámetros, es una señal clara de violación del Principio de Responsabilidad Única (SRP).

### 2. Organización por Feature (*Package by Feature*)

En lugar de agrupar todo el código horizontalmente en carpetas gigantescas (`controllers/`, `services/`, `models/`), se organiza verticalmente por funcionalidad de negocio (`tournament/`, `raffle/`, `user/`, `payment/`):

- **Alta cohesión**: Todo lo relativo a torneos vive en el módulo `tournament`. Modificar una regla de torneos no requiere navegar por 5 extremos del árbol del proyecto.
- **Encapsulamiento**: Permite usar modificadores de acceso de paquete (*package-private*) para componentes internos que no deban ser visibles fuera de la feature.
- **Evolución a microservicios**: Si en el futuro un módulo como `payment` o `tournament` necesita escalarse independientemente, su extracción a un servicio separado es trivial.

---

## Escalado Horizontal sin Session Affinity (CE2-9)

La API es **stateless**: el JWT lleva la sesión y lo demás vive en Postgres (refresh tokens, versión de sesión, códigos de canje de Google). El `state` del login con Google viaja en una cookie en lugar de la `HttpSession`, y fuera del perfil `dev` la API no arranca sin `JWT_PRIVATE_KEY` y `JWT_PUBLIC_KEY`, así todas las réplicas firman con la misma clave. Por eso puede correr con varias réplicas detrás de un balanceador en **Round Robin**, sin afinidad de sesión (*sticky sessions*).

### Demostración con 2 réplicas y Nginx

`docker-compose.replicas.yml` levanta dos instancias del backend (`api1` y `api2`) detrás de Nginx en Round Robin, en `http://localhost:8080`. Cada respuesta trae el header `X-Handled-By` con la réplica que la atendió.

```bash
# 1. Variables de las réplicas, con el par de claves RSA que comparten (en una sola línea cada una)
cp .env.replicas.example .env.replicas
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt.pem
sed -i "s|^JWT_PRIVATE_KEY=.*|JWT_PRIVATE_KEY=$(tr -d '\n' < jwt.pem)|" .env.replicas
sed -i "s|^JWT_PUBLIC_KEY=.*|JWT_PUBLIC_KEY=$(openssl pkey -in jwt.pem -pubout | tr -d '\n')|" .env.replicas
rm jwt.pem

# 2. Levantar el stack
docker compose -f docker-compose.replicas.yml up --build -d

# 3. Probar login local, refresh, revocación, el state de Google en cookie y el canje del código
./scripts/probar-replicas.sh

# 4. Bajar el stack
docker compose -f docker-compose.replicas.yml down -v
```

El script corta con error en el primer chequeo que falle, y cada paso lo tienen que atender las dos réplicas.

**Login con Google (a mano).** El script no puede pasar por Google. Para probarlo completo: poner en `.env.replicas` las credenciales reales (`GOOGLE_CLIENT_ID` y `GOOGLE_CLIENT_SECRET`), con `http://localhost:8080/login/oauth2/code/google` entre las URIs de redirección autorizadas del cliente; levantar el front con la API en `http://localhost:8080` y entrar con Google. La redirección a Google, la vuelta y el canje del código caen en réplicas distintas (se ve con `docker compose -f docker-compose.replicas.yml logs -f api1 api2`).
