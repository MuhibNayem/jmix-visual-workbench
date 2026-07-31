#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$SCRIPT_DIR/../../plugin"
EVIDENCE_DIR="${CERT_INTEGRATION_EVIDENCE_DIR:-$SCRIPT_DIR/evidence/current}"
POSTGRES_PASSWORD="${CERT_POSTGRES_PASSWORD:-jmix-integration-postgres}"
POSTGRES_PORT="${CERT_POSTGRES_PORT:-56432}"
KAFKA_PROXY_PORT="${CERT_KAFKA_PROXY_PORT:-59092}"
RABBIT_PROXY_PORT="${CERT_RABBIT_PROXY_PORT:-55673}"
TOXIPROXY_PORT="${CERT_TOXIPROXY_PORT:-58474}"
SFTP_PORT="${CERT_SFTP_PORT:-52222}"
WIREMOCK_PORT="${CERT_WIREMOCK_PORT:-58080}"
BUILD_JAVA_HOME="${CERT_BUILD_JAVA_HOME:-}"
CELL_FILTER="${CERT_INTEGRATION_CELL:-all}"

resolve_jdk() {
  local version="$1"
  local resolved=""
  local actual_version=""
  if [[ -x /usr/libexec/java_home ]]; then
    resolved="$(/usr/libexec/java_home -v "$version" 2>/dev/null || true)"
    if [[ -n "$resolved" && -x "$resolved/bin/java" ]]; then
      actual_version="$("$resolved/bin/java" -XshowSettings:properties -version 2>&1 \
        | awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); print $2; exit}')"
      if [[ "$actual_version" != "$version" ]]; then
        resolved=""
      fi
    fi
  fi
  if [[ -z "$resolved" ]]; then
    local candidate
    for candidate in /opt/homebrew/Cellar/openjdk@"$version"/*/libexec/openjdk.jdk/Contents/Home; do
      if [[ -x "$candidate/bin/java" ]]; then
        resolved="$candidate"
      fi
    done
  fi
  printf '%s' "$resolved"
}

if [[ -z "$BUILD_JAVA_HOME" ]]; then
  BUILD_JAVA_HOME="$(resolve_jdk 21)"
fi
if [[ -z "$BUILD_JAVA_HOME" ]] || [[ ! -x "$BUILD_JAVA_HOME/bin/java" ]]; then
  echo "JDK 21 is required to launch the pinned certification build. Set CERT_BUILD_JAVA_HOME." >&2
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required for integration runtime certification." >&2
  exit 1
fi

mkdir -p "$EVIDENCE_DIR"

(
  cd "$PLUGIN_DIR"
  JAVA_HOME="$BUILD_JAVA_HOME" \
  PATH="$BUILD_JAVA_HOME/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    ./gradlew generateCompatibilityFixtures \
      --no-daemon \
      --no-configuration-cache
)

wait_healthy() {
  local service="$1"
  local container_id
  local status
  container_id="$(docker compose -f "$SCRIPT_DIR/docker-compose.yml" ps -q "$service")"
  if [[ -z "$container_id" ]]; then
    echo "Container for $service was not created." >&2
    exit 1
  fi
  for _ in $(seq 1 90); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
    if [[ "$status" == "healthy" || "$status" == "running" && "$service" == "wiremock" ]]; then
      return
    fi
    if [[ "$status" == "exited" || "$status" == "dead" || "$status" == "unhealthy" ]]; then
      docker compose -f "$SCRIPT_DIR/docker-compose.yml" logs "$service"
      echo "$service failed to become healthy: $status" >&2
      exit 1
    fi
    sleep 2
  done
  docker compose -f "$SCRIPT_DIR/docker-compose.yml" logs "$service"
  echo "Timed out waiting for $service to become healthy." >&2
  exit 1
}

create_proxy() {
  local name="$1"
  local listen="$2"
  local upstream="$3"
  curl --fail --silent --show-error \
    -H 'Content-Type: application/json' \
    -X POST \
    -d "{\"name\":\"$name\",\"listen\":\"$listen\",\"upstream\":\"$upstream\",\"enabled\":true}" \
    "http://127.0.0.1:$TOXIPROXY_PORT/proxies" >/dev/null
}

run_cell() {
  local cell_id="$1"
  local jmix_version="$2"
  local jmix_line="$3"
  local java_version="$4"
  local cell_java_home

  cell_java_home="$(resolve_jdk "$java_version")"
  if [[ -z "$cell_java_home" ]] || [[ ! -x "$cell_java_home/bin/java" ]]; then
    echo "JDK $java_version is required for $cell_id." >&2
    exit 1
  fi

  cd "$SCRIPT_DIR"
  docker compose up -d --force-recreate postgres kafka rabbit sftp toxiproxy wiremock
  wait_healthy postgres
  wait_healthy kafka
  wait_healthy rabbit
  wait_healthy sftp
  wait_healthy toxiproxy
  wait_healthy wiremock
  create_proxy kafka "0.0.0.0:19092" "kafka:19092"
  create_proxy rabbit "0.0.0.0:15673" "rabbit:5672"

  CERT_DB_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/jmixintcert" \
  CERT_DB_USERNAME="jmixintcert" \
  CERT_DB_PASSWORD="$POSTGRES_PASSWORD" \
  CERT_KAFKA_BOOTSTRAP="127.0.0.1:$KAFKA_PROXY_PORT" \
  CERT_RABBIT_HOST="127.0.0.1" \
  CERT_RABBIT_PORT="$RABBIT_PROXY_PORT" \
  CERT_TOXIPROXY_URL="http://127.0.0.1:$TOXIPROXY_PORT" \
  CERT_SFTP_HOST="127.0.0.1" \
  CERT_SFTP_PORT="$SFTP_PORT" \
  CERT_SFTP_USERNAME="jmixintcert" \
  CERT_SFTP_PASSWORD="jmix-integration-sftp" \
  CERT_WIREMOCK_URL="http://127.0.0.1:$WIREMOCK_PORT" \
  CERT_EVIDENCE_FILE="$EVIDENCE_DIR/$cell_id.json" \
  CERT_CELL_ID="$cell_id" \
  JAVA_HOME="$cell_java_home" \
  PATH="$cell_java_home/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
    "$SCRIPT_DIR/../database-runtime/gradlew" \
      -p "$SCRIPT_DIR" \
      clean certifyIntegrationRuntime \
      "-PcertJmixVersion=$jmix_version" \
      "-PcertJmixLine=$jmix_line" \
      "-PcertJavaVersion=$java_version" \
      "-PcertGeneratedRoot=$PLUGIN_DIR/build/compatibility/generated-sources" \
      --no-daemon \
      --stacktrace

  if [[ ! -s "$EVIDENCE_DIR/$cell_id.json" ]]; then
    echo "Missing integration evidence for $cell_id." >&2
    exit 1
  fi
  if ! grep -q '"brokerOutageRecovered":true' "$EVIDENCE_DIR/$cell_id.json"; then
    echo "Broker recovery evidence failed for $cell_id." >&2
    exit 1
  fi
}

if [[ "$CELL_FILTER" == "all" || "$CELL_FILTER" == "jmix28-jdk17" ]]; then
  run_cell "jmix28-jdk17" "2.8.2" "jmix28" "17"
fi
if [[ "$CELL_FILTER" == "all" || "$CELL_FILTER" == "jmix30-jdk21" ]]; then
  run_cell "jmix30-jdk21" "3.0.0" "jmix30" "21"
fi
if [[ "$CELL_FILTER" != "all"
      && "$CELL_FILTER" != "jmix28-jdk17"
      && "$CELL_FILTER" != "jmix30-jdk21" ]]; then
  echo "Unsupported CERT_INTEGRATION_CELL: $CELL_FILTER" >&2
  exit 1
fi

echo "Integration runtime matrix passed for $CELL_FILTER. Evidence: $EVIDENCE_DIR"
