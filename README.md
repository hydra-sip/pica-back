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

### Probar el Endpoint de Health

Una vez levantada la aplicación en `http://localhost:8080`:

```bash
curl -i http://localhost:8080/api/v1/health
```

**Respuesta esperada (HTTP 200 OK):**
```json
{
  "status": "UP",
  "service": "plataforma-pica",
  "timestamp": "2026-09-19T18:30:00.123456Z"
}
```

También podés verificar los endpoints de Spring Actuator:
- `http://localhost:8080/actuator/health`
- `http://localhost:8080/actuator/info`

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
├── health/
│   └── HealthController.java
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
- **`health/HealthController.java`**: Expone `GET /api/v1/health`. Retorna un objeto JSON con el estado de la aplicación (`status: UP`), el nombre del servicio obtenido de `spring.application.name` y la marca de tiempo actual en formato ISO-8601 (`Instant.now()`).
- **`src/test/.../health/HealthControllerTest.java`**: Prueba unitaria de capa web usando `@WebMvcTest`. Valida de forma aislada y rápida que `/api/v1/health` responda HTTP 200 OK con el cuerpo JSON esperado mediante `MockMvc` inyectado por constructor.

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
