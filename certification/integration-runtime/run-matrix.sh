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
MTLS_PORT="${CERT_MTLS_PORT:-58443}"
MTLS_PASSWORD="${CERT_MTLS_PASSWORD:-jmix-integration-mtls}"
OAUTH_CLIENT_ID="${CERT_OAUTH_CLIENT_ID:-jvw-runtime-client}"
OAUTH_CLIENT_SECRET="${CERT_OAUTH_CLIENT_SECRET:-jmix-integration-oauth}"
BUILD_JAVA_HOME="${CERT_BUILD_JAVA_HOME:-}"
CELL_FILTER="${CERT_INTEGRATION_CELL:-all}"
TLS_DIR=""

cleanup() {
  if [[ -n "$TLS_DIR" && -d "$TLS_DIR" ]]; then
    docker compose -f "$SCRIPT_DIR/docker-compose.yml" down -v >/dev/null 2>&1 || true
    rm -rf "$TLS_DIR"
  fi
}
trap cleanup EXIT

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
  if [[ -z "$resolved" ]]; then
    local gradle_jdks="${GRADLE_USER_HOME:-$HOME/.gradle}/jdks"
    local candidate
    for candidate in \
      "$gradle_jdks"/eclipse_adoptium-"$version"-*/jdk-"$version".*/Contents/Home \
      "$gradle_jdks"/*-"$version"-*/Contents/Home
    do
      if [[ -x "$candidate/bin/java" ]]; then
        resolved="$candidate"
        break
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
if ! command -v openssl >/dev/null 2>&1; then
  echo "OpenSSL is required to create disposable mTLS certification material." >&2
  exit 1
fi

generate_tls_lab() {
  local keytool="$BUILD_JAVA_HOME/bin/keytool"
  if [[ ! -x "$keytool" ]]; then
    echo "The selected JDK does not provide keytool." >&2
    exit 1
  fi
  TLS_DIR="$(mktemp -d "${TMPDIR:-/tmp}/jvw-integration-tls.XXXXXX")"
  export CERT_TLS_DIR="$TLS_DIR"
  export CERT_MTLS_PASSWORD="$MTLS_PASSWORD"
  export CERT_MTLS_PORT="$MTLS_PORT"

  openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 \
    -subj "/CN=JVW Integration Runtime CA" \
    -keyout "$TLS_DIR/ca.key" -out "$TLS_DIR/ca.crt" >/dev/null 2>&1
  printf '%s\n' \
    'basicConstraints=CA:FALSE' \
    'keyUsage=digitalSignature,keyEncipherment' \
    'extendedKeyUsage=serverAuth' \
    'subjectAltName=DNS:localhost' >"$TLS_DIR/server.ext"
  openssl req -newkey rsa:2048 -sha256 -nodes \
    -subj "/CN=localhost" \
    -keyout "$TLS_DIR/server.key" -out "$TLS_DIR/server.csr" >/dev/null 2>&1
  openssl x509 -req -sha256 -days 2 \
    -in "$TLS_DIR/server.csr" \
    -CA "$TLS_DIR/ca.crt" -CAkey "$TLS_DIR/ca.key" -CAcreateserial \
    -extfile "$TLS_DIR/server.ext" -out "$TLS_DIR/server.crt" >/dev/null 2>&1
  openssl pkcs12 -export -name wiremock-server \
    -inkey "$TLS_DIR/server.key" -in "$TLS_DIR/server.crt" \
    -certfile "$TLS_DIR/ca.crt" -out "$TLS_DIR/server.p12" \
    -passout "pass:$MTLS_PASSWORD" >/dev/null 2>&1

  printf '%s\n' \
    'basicConstraints=CA:FALSE' \
    'keyUsage=digitalSignature,keyEncipherment' \
    'extendedKeyUsage=clientAuth' >"$TLS_DIR/client.ext"
  for client_name in client rotated-client; do
    openssl req -newkey rsa:2048 -sha256 -nodes \
      -subj "/CN=jvw-$client_name" \
      -keyout "$TLS_DIR/$client_name.key" \
      -out "$TLS_DIR/$client_name.csr" >/dev/null 2>&1
    openssl x509 -req -sha256 -days 2 \
      -in "$TLS_DIR/$client_name.csr" \
      -CA "$TLS_DIR/ca.crt" -CAkey "$TLS_DIR/ca.key" -CAcreateserial \
      -extfile "$TLS_DIR/client.ext" \
      -out "$TLS_DIR/$client_name.crt" >/dev/null 2>&1
    openssl pkcs12 -export -name "$client_name" \
      -inkey "$TLS_DIR/$client_name.key" -in "$TLS_DIR/$client_name.crt" \
      -certfile "$TLS_DIR/ca.crt" -out "$TLS_DIR/$client_name.p12" \
      -passout "pass:$MTLS_PASSWORD" >/dev/null 2>&1
  done

  openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 2 \
    -subj "/CN=JVW Untrusted Runtime CA" \
    -keyout "$TLS_DIR/untrusted-ca.key" \
    -out "$TLS_DIR/untrusted-ca.crt" >/dev/null 2>&1
  "$keytool" -importcert -noprompt -storetype PKCS12 \
    -alias client-ca -file "$TLS_DIR/ca.crt" \
    -keystore "$TLS_DIR/server-trust.p12" \
    -storepass "$MTLS_PASSWORD" >/dev/null 2>&1
  "$keytool" -importcert -noprompt -storetype PKCS12 \
    -alias server-ca -file "$TLS_DIR/ca.crt" \
    -keystore "$TLS_DIR/client-trust.p12" \
    -storepass "$MTLS_PASSWORD" >/dev/null 2>&1
  "$keytool" -importcert -noprompt -storetype PKCS12 \
    -alias untrusted-server-ca -file "$TLS_DIR/untrusted-ca.crt" \
    -keystore "$TLS_DIR/untrusted-trust.p12" \
    -storepass "$MTLS_PASSWORD" >/dev/null 2>&1
}

generate_tls_lab
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
  CERT_MTLS_URL="https://localhost:$MTLS_PORT" \
  CERT_MTLS_HOSTNAME_MISMATCH_URL="https://127.0.0.1:$MTLS_PORT" \
  CERT_TLS_DIR="$TLS_DIR" \
  CERT_MTLS_PASSWORD="$MTLS_PASSWORD" \
  CERT_OAUTH_CLIENT_ID="$OAUTH_CLIENT_ID" \
  CERT_OAUTH_CLIENT_SECRET="$OAUTH_CLIENT_SECRET" \
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
  if ! grep -q '"inboundKafkaScenarios":6' "$EVIDENCE_DIR/$cell_id.json"; then
    echo "Kafka inbound scenario evidence failed for $cell_id." >&2
    exit 1
  fi
  if ! grep -q '"inboundRabbitScenarios":6' "$EVIDENCE_DIR/$cell_id.json"; then
    echo "RabbitMQ inbound scenario evidence failed for $cell_id." >&2
    exit 1
  fi
  for required_flag in \
    missingIdentityQuarantined \
    conflictingIdentityRejected \
    transactionalEffectsCertified \
    oauth2RenewalCertified \
    invalidTokenEvictionCertified \
    mtlsClientCertificateCertified \
    mtlsNegativePathsCertified \
    mtlsHotRotationCertified
  do
    if ! grep -q "\"$required_flag\":true" "$EVIDENCE_DIR/$cell_id.json"; then
      echo "$required_flag evidence failed for $cell_id." >&2
      exit 1
    fi
  done
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
