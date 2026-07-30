#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EVIDENCE_DIR="${CERT_EVIDENCE_DIR:-$SCRIPT_DIR/evidence/current}"
BUILD_JAVA_HOME="${CERT_BUILD_JAVA_HOME:-}"
POSTGRES_PASSWORD="${CERT_POSTGRES_PASSWORD:-jmixcert-postgres}"
MYSQL_PASSWORD="${CERT_MYSQL_PASSWORD:-jmixcert-mysql}"
MARIADB_PASSWORD="${CERT_MARIADB_PASSWORD:-jmixcert-mariadb}"
MSSQL_PASSWORD="${CERT_MSSQL_PASSWORD:-JmixCert-Mssql-2026!}"
ORACLE_PASSWORD="${CERT_ORACLE_PASSWORD:-jmixcert-oracle}"
POSTGRES_PORT="${CERT_POSTGRES_PORT:-55432}"
MYSQL_PORT="${CERT_MYSQL_PORT:-53306}"
MARIADB_PORT="${CERT_MARIADB_PORT:-53307}"
MSSQL_PORT="${CERT_MSSQL_PORT:-51433}"
ORACLE_PORT="${CERT_ORACLE_PORT:-51521}"
DATABASE_SELECTION="${CERT_DATABASES:-postgres,mysql,mariadb,mssql,oracle}"

IFS=',' read -r -a DATABASES <<< "$DATABASE_SELECTION"
if [[ "${#DATABASES[@]}" -eq 0 ]]; then
  echo "CERT_DATABASES must select at least one certification database." >&2
  exit 1
fi
selected_database_ids=","
for database_id in "${DATABASES[@]}"; do
  case "$database_id" in
    postgres|mysql|mariadb|mssql|oracle) ;;
    *)
      echo "Unsupported CERT_DATABASES entry: $database_id" >&2
      exit 1
      ;;
  esac
  if [[ "$selected_database_ids" == *",$database_id,"* ]]; then
    echo "CERT_DATABASES contains duplicate entry: $database_id" >&2
    exit 1
  fi
  selected_database_ids+="$database_id,"
done

if [[ -z "$BUILD_JAVA_HOME" ]] && [[ -x /usr/libexec/java_home ]]; then
  BUILD_JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [[ -z "$BUILD_JAVA_HOME" ]] || [[ ! -x "$BUILD_JAVA_HOME/bin/java" ]]; then
  echo "JDK 21 is required to launch the pinned Gradle wrapper. Set CERT_BUILD_JAVA_HOME." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for the database certification matrix." >&2
  exit 1
fi

mkdir -p "$EVIDENCE_DIR"
cd "$SCRIPT_DIR"

# Certification must not inherit a schema or log history from a previous run.
# These services contain only disposable fixture data and define no persistent
# volumes. Force recreation is scoped to the explicitly selected Compose
# services; unrelated containers and developer databases are never touched.
docker compose up -d --force-recreate "${DATABASES[@]}"

wait_healthy() {
  local service="$1"
  local container_id
  local status
  container_id="$(docker compose ps -q "$service")"
  if [[ -z "$container_id" ]]; then
    echo "Container for $service was not created." >&2
    exit 1
  fi
  for _ in $(seq 1 90); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
    if [[ "$status" == "healthy" ]]; then
      return
    fi
    if [[ "$status" == "exited" || "$status" == "dead" || "$status" == "unhealthy" ]]; then
      docker compose logs "$service"
      echo "$service failed to become healthy: $status" >&2
      exit 1
    fi
    sleep 2
  done
  docker compose logs "$service"
  echo "Timed out waiting for $service to become healthy." >&2
  exit 1
}

for service in "${DATABASES[@]}"; do
  wait_healthy "$service"
done

if [[ "$selected_database_ids" == *",mssql,"* ]]; then
  docker compose exec -T mssql sh -lc \
    '(/opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -C -b -Q "IF DB_ID('\''jmixcert'\'') IS NULL CREATE DATABASE jmixcert" || /opt/mssql-tools/bin/sqlcmd -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -b -Q "IF DB_ID('\''jmixcert'\'') IS NULL CREATE DATABASE jmixcert")'
fi

run_cell() {
  local database_id="$1"
  local jmix_version="$2"
  local compile_java_version="$3"
  local runtime_java_version="$4"
  local url="$5"
  local username="$6"
  local password="$7"
  local driver="$8"
  local schema="$9"
  local cell_id="${database_id}-jmix${jmix_version//./}-jdk${runtime_java_version}"

  JAVA_HOME="$BUILD_JAVA_HOME" \
  PATH="$BUILD_JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    ./gradlew clean certifyRuntime \
      "-PcertJmixVersion=$jmix_version" \
      "-PcertCompileJavaVersion=$compile_java_version" \
      "-PcertJavaVersion=$runtime_java_version" \
      "-PcertDbId=$database_id" \
      "-PcertDbUrl=$url" \
      "-PcertDbUsername=$username" \
      "-PcertDbPassword=$password" \
      "-PcertDbDriver=$driver" \
      "-PcertDbSchema=$schema" \
      "-PcertEvidenceFile=$EVIDENCE_DIR/$cell_id.json" \
      --no-daemon \
      --stacktrace
}

run_database() {
  local database_id="$1"
  local url="$2"
  local username="$3"
  local password="$4"
  local driver="$5"
  local schema="$6"
  local catalog="$7"

  run_cell "$database_id" "2.8.2" "17" "17" "$url" "$username" "$password" "$driver" "$schema"
  run_cell "$database_id" "2.8.2" "21" "21" "$url" "$username" "$password" "$driver" "$schema"
  run_cell "$database_id" "3.0.0" "21" "21" "$url" "$username" "$password" "$driver" "$schema"
  run_cell "$database_id" "3.0.0" "21" "25" "$url" "$username" "$password" "$driver" "$schema"

  run_live_reverse_engineering \
    "$database_id" \
    "$url" \
    "$username" \
    "$password" \
    "$driver" \
    "$schema" \
    "$catalog"
}

run_live_reverse_engineering() {
  local database_id="$1"
  local url="$2"
  local username="$3"
  local password="$4"
  local driver="$5"
  local schema="$6"
  local catalog="$7"
  local driver_jar
  driver_jar="$(
    JAVA_HOME="$BUILD_JAVA_HOME" \
    PATH="$BUILD_JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
      ./gradlew -q printDriverClasspath "-PcertDbId=$database_id" --no-daemon
  )"

  local lane
  IFS=',' read -r -a lanes <<< "${CERT_PLUGIN_LANES:-idea253,idea262}"
  for lane in "${lanes[@]}"; do
    (
      cd "$SCRIPT_DIR/../../plugin"
      JAVA_HOME="$BUILD_JAVA_HOME" \
      PATH="$BUILD_JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
        ./gradlew ":$lane:test" \
          --tests org.jmixworkbench.services.DatabaseReverseEngineeringLiveMatrixTest \
          -Djvw.live.db.enabled=true \
          "-Djvw.live.db.id=$database_id" \
          "-Djvw.live.db.url=$url" \
          "-Djvw.live.db.username=$username" \
          "-Djvw.live.db.password=$password" \
          "-Djvw.live.db.driver=$driver" \
          "-Djvw.live.db.driverClasspath=$driver_jar" \
          "-Djvw.live.db.catalog=$catalog" \
          "-Djvw.live.db.schema=$schema" \
          "-Djvw.live.db.hostLane=$lane" \
          "-Djvw.live.db.evidenceFile=$EVIDENCE_DIR/reverse-$database_id-$lane.json" \
          --no-daemon \
          --no-configuration-cache \
          --rerun-tasks \
          --stacktrace
    )
  done
}

for database_id in "${DATABASES[@]}"; do
  case "$database_id" in
    postgres)
      run_database \
        "postgres" \
        "jdbc:postgresql://localhost:$POSTGRES_PORT/jmixcert" \
        "jmixcert" \
        "$POSTGRES_PASSWORD" \
        "org.postgresql.Driver" \
        "public" \
        "jmixcert"
      ;;
    mysql)
      run_database \
        "mysql" \
        "jdbc:mysql://localhost:$MYSQL_PORT/jmixcert?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
        "jmixcert" \
        "$MYSQL_PASSWORD" \
        "com.mysql.cj.jdbc.Driver" \
        "" \
        "jmixcert"
      ;;
    mariadb)
      run_database \
        "mariadb" \
        "jdbc:mariadb://localhost:$MARIADB_PORT/jmixcert" \
        "jmixcert" \
        "$MARIADB_PASSWORD" \
        "org.mariadb.jdbc.Driver" \
        "" \
        "jmixcert"
      ;;
    mssql)
      run_database \
        "mssql" \
        "jdbc:sqlserver://localhost:$MSSQL_PORT;databaseName=jmixcert;encrypt=true;trustServerCertificate=true" \
        "sa" \
        "$MSSQL_PASSWORD" \
        "com.microsoft.sqlserver.jdbc.SQLServerDriver" \
        "dbo" \
        "jmixcert"
      ;;
    oracle)
      run_database \
        "oracle" \
        "jdbc:oracle:thin:@localhost:$ORACLE_PORT/FREEPDB1" \
        "jmixcert" \
        "$ORACLE_PASSWORD" \
        "oracle.jdbc.OracleDriver" \
        "JMIXCERT" \
        ""
      ;;
  esac
done

for database_id in "${DATABASES[@]}"; do
  IFS=',' read -r -a lanes <<< "${CERT_PLUGIN_LANES:-idea253,idea262}"
  for lane in "${lanes[@]}"; do
    evidence_file="$EVIDENCE_DIR/reverse-$database_id-$lane.json"
    if [[ ! -s "$evidence_file" ]]; then
      echo "Missing reverse-engineering evidence: $evidence_file" >&2
      exit 1
    fi
  done
done

echo "Database runtime matrix passed. Evidence: $EVIDENCE_DIR"
