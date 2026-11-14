# Backup y restore de la base (RNF-DIS-03)

La base de producción está en Supabase, en el proyecto **PICA** de la organización hydra (`sa-east-1`). El plan Free no hace backups, así que los hace un workflow de este repo.

## El backup

`.github/workflows/backup.yml` corre todos los días a las 03:17 (hora de Argentina). También se puede correr a mano desde Actions → Backup → Run workflow, y corre en cada PR que lo cambie.

1. Hace `pg_dump` del esquema `public` (`scripts/backup-base.sh`).
   - Ahí están todas las tablas de PICA y `flyway_schema_history`.
   - No entran `auth` ni `storage`, que son de Supabase, ni los archivos de los buckets (los reglamentos).
2. Lo cifra con `age` y la clave pública `BACKUP_AGE_RECIPIENT` (variable del repo).
3. Lo guarda como artefacto de la corrida, `pica-<fecha UTC>.dump.age`, por **14 días**.

El repo es público, por eso el dump nunca sale sin cifrar. La URL de la base está en el secret `SUPABASE_DB_URL`.

| Qué | Dónde |
|---|---|
| `SUPABASE_DB_URL` (secret) | La URI del **session pooler**, puerto 5432. La conexión directa es solo IPv6 y los runners de GitHub no la alcanzan. En Supabase: Connect → Direct → Session pooler. |
| `BACKUP_AGE_RECIPIENT` (variable) | La clave pública (`age1…`). |
| La clave privada | Fuera de GitHub, la guarda el equipo. Sin ella no se abre ningún backup. |

El cron solo corre desde `main`, la rama por defecto: el backup automático empieza con el pase de `dev` a `main`.

Si el workflow falla, GitHub le manda un mail a quien lo activó. Las causas probables:
- la contraseña de la base cambió y hay que actualizar el secret;
- Supabase subió de versión mayor de Postgres y hay que subir `PG_MAYOR` en el workflow (el script lo avisa).

## El restore

Hace falta `gh` con sesión, `age`, `docker` y la clave privada.

1. **Una base vacía.** En Supabase, un proyecto nuevo con su contraseña: New project, en la misma región. Copiar su URI del session pooler.
2. **Restaurar el último backup:**

   ```bash
   RESTORE_DB_URL='postgresql://postgres.<ref>:<clave>@aws-0-sa-east-1.pooler.supabase.com:5432/postgres' \
   BACKUP_AGE_KEY=~/pica-backup.key \
     scripts/restaurar-backup.sh
   ```

   - Sin argumento baja el último backup exitoso.
   - Se le puede pasar el id de una corrida (`gh run list --workflow backup.yml`) o un archivo `.dump.age` ya bajado.
   - Al final muestra la última migración, cuántos usuarios, torneos y movimientos hay, y cuánto tardó.
   - No borra nada: si la base ya tiene las tablas, falla en la primera.
3. **Apuntar la API a la base restaurada.**
   - En Railway: cambiar `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD` por los del proyecto nuevo.
   - Para probar en local:

     ```bash
     SPRING_DOCKER_COMPOSE_ENABLED=false \
     SPRING_DATASOURCE_URL='jdbc:postgresql://aws-0-sa-east-1.pooler.supabase.com:5432/postgres' \
     SPRING_DATASOURCE_USERNAME='postgres.<ref>' SPRING_DATASOURCE_PASSWORD='<clave>' \
       ./mvnw spring-boot:run
     ```

   Flyway encuentra su historial y no migra nada.
4. **Verificar:**
   - `/actuator/health` en `UP`;
   - iniciar sesión con un usuario real;
   - abrir la ficha de un torneo.

## La prueba

| Fecha | Backup | Destino | Restore | API | Tiempo |
|---|---|---|---|---|---|
| 14/11/2026 | Local, Postgres 17 con las migraciones V1 a V52 | Postgres 17 vacío | OK, V52 | — | 2 s |
