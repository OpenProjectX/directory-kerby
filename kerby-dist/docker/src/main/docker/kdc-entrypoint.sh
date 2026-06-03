#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.

set -eu

KERBY_HOME="${KERBY_HOME:-/opt/kerby}"
KERBY_CONF_DIR="${KERBY_CONF_DIR:-${KERBY_HOME}/conf}"
KERBY_DATA_DIR="${KERBY_DATA_DIR:-/var/lib/kerby}"
KERBY_WORK_DIR="${KERBY_WORK_DIR:-/var/run/kerby}"
KERBY_KEYTAB_DIR="${KERBY_KEYTAB_DIR:-${KERBY_DATA_DIR}/keytabs}"
KERBY_BACKEND_DIR="${KERBY_BACKEND_DIR:-${KERBY_DATA_DIR}/jsonbackend}"
KERBY_CLIENT_CONF_DIR="${KERBY_CLIENT_CONF_DIR:-${KERBY_DATA_DIR}/client}"

KERBY_REALM="${KERBY_REALM:-EXAMPLE.COM}"
KERBY_KDC_BIND_HOST="${KERBY_KDC_BIND_HOST:-0.0.0.0}"
KERBY_KDC_HOST="${KERBY_KDC_HOST:-127.0.0.1}"
KERBY_KDC_TCP_PORT="${KERBY_KDC_TCP_PORT:-88}"
KERBY_KDC_UDP_PORT="${KERBY_KDC_UDP_PORT:-88}"
KERBY_CLIENT_KDC_HOST="${KERBY_CLIENT_KDC_HOST:-${KERBY_KDC_HOST}}"
KERBY_CLIENT_KDC_PORT="${KERBY_CLIENT_KDC_PORT:-${KERBY_KDC_TCP_PORT}}"
KERBY_CLIENT_DOMAIN="${KERBY_CLIENT_DOMAIN:-example.com}"
KERBY_PREAUTH_REQUIRED="${KERBY_PREAUTH_REQUIRED:-false}"
KERBY_PA_ENC_TIMESTAMP_REQUIRED="${KERBY_PA_ENC_TIMESTAMP_REQUIRED:-false}"
KERBY_ADMIN_PORT="${KERBY_ADMIN_PORT:-65417}"
KERBY_ADMIN_HOST="${KERBY_ADMIN_HOST:-localhost}"
KERBY_ADMIN_PROTOCOL="${KERBY_ADMIN_PROTOCOL:-adminprotocol}"
KERBY_CLIENT_PRINCIPAL="${KERBY_CLIENT_PRINCIPAL:-client}"
KERBY_CLIENT_PASSWORD="${KERBY_CLIENT_PASSWORD:-client}"
KERBY_SERVICE_PRINCIPAL="${KERBY_SERVICE_PRINCIPAL:-HTTP/localhost}"
KERBY_SERVICE_KEYTAB="${KERBY_SERVICE_KEYTAB:-${KERBY_KEYTAB_DIR}/service.keytab}"
KERBY_READY_FILE="${KERBY_READY_FILE:-${KERBY_DATA_DIR}/ready}"

CLASSPATH="${KERBY_HOME}/lib/*:${KERBY_HOME}"
REQUIRED_KEYTABS=""

mkdir -p "${KERBY_CONF_DIR}" "${KERBY_DATA_DIR}" "${KERBY_WORK_DIR}" \
  "${KERBY_KEYTAB_DIR}" "${KERBY_BACKEND_DIR}" "${KERBY_CLIENT_CONF_DIR}"

write_krb5_conf() {
  file="$1"
  host="$2"
  port="$3"
  cat > "${file}" <<EOF
[libdefaults]
    kdc_realm = ${KERBY_REALM}
    default_realm = ${KERBY_REALM}
    dns_lookup_kdc = false
    dns_lookup_realm = false
    rdns = false
    udp_preference_limit = 1
    kdc_tcp_port = ${port}
    kdc_udp_port = ${port}

[realms]
    ${KERBY_REALM} = {
        kdc = ${host}:${port}
    }

[domain_realm]
    .${KERBY_CLIENT_DOMAIN} = ${KERBY_REALM}
    ${KERBY_CLIENT_DOMAIN} = ${KERBY_REALM}
EOF
}

cat > "${KERBY_CONF_DIR}/kdc.conf" <<EOF
[kdcdefaults]
  kdc_host = ${KERBY_KDC_BIND_HOST}
  kdc_udp_port = ${KERBY_KDC_UDP_PORT}
  kdc_tcp_port = ${KERBY_KDC_TCP_PORT}
  kdc_realm = ${KERBY_REALM}
  preauth_required = ${KERBY_PREAUTH_REQUIRED}
  pa_enc_timestamp_required = ${KERBY_PA_ENC_TIMESTAMP_REQUIRED}
EOF

write_krb5_conf "${KERBY_CONF_DIR}/krb5.conf" "${KERBY_KDC_HOST}" "${KERBY_KDC_TCP_PORT}"
write_krb5_conf "${KERBY_CLIENT_CONF_DIR}/krb5.conf" \
  "${KERBY_CLIENT_KDC_HOST}" "${KERBY_CLIENT_KDC_PORT}"

cat > "${KERBY_CONF_DIR}/backend.conf" <<EOF
kdc_identity_backend = org.apache.kerby.kerberos.kdc.identitybackend.JsonIdentityBackend
backend.json.dir = ${KERBY_BACKEND_DIR}
EOF

cat > "${KERBY_CONF_DIR}/adminServer.conf" <<EOF
[libdefaults]
default_realm = ${KERBY_REALM}
admin_realm = ${KERBY_REALM}
admin_port = ${KERBY_ADMIN_PORT}
keytab_file = protocol.keytab
protocol = ${KERBY_ADMIN_PROTOCOL}
server_name = ${KERBY_ADMIN_HOST}
EOF

append_java_tool_option() {
  option="$1"
  case " ${JAVA_TOOL_OPTIONS:-} " in
    *" ${option} "*) ;;
    *) JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }${option}" ;;
  esac
  export JAVA_TOOL_OPTIONS
}

debug_enabled() {
  case "${KERBY_DEBUG:-false}" in
    true|TRUE|1|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

dump_file() {
  label="$1"
  file="$2"
  if [ -f "${file}" ]; then
    echo "----- ${label}: ${file} -----"
    sed 's/^/| /' "${file}"
    echo "----- end ${label} -----"
  else
    echo "----- ${label}: ${file} is missing -----"
  fi
}

dump_debug_configuration() {
  if ! debug_enabled; then
    return 0
  fi

  echo "Kerby debug is enabled."
  echo "KERBY_REALM=${KERBY_REALM}"
  echo "KERBY_KDC_BIND_HOST=${KERBY_KDC_BIND_HOST}"
  echo "KERBY_KDC_HOST=${KERBY_KDC_HOST}"
  echo "KERBY_KDC_TCP_PORT=${KERBY_KDC_TCP_PORT}"
  echo "KERBY_KDC_UDP_PORT=${KERBY_KDC_UDP_PORT}"
  echo "KERBY_CLIENT_KDC_HOST=${KERBY_CLIENT_KDC_HOST}"
  echo "KERBY_CLIENT_KDC_PORT=${KERBY_CLIENT_KDC_PORT}"
  echo "KERBY_CLIENT_DOMAIN=${KERBY_CLIENT_DOMAIN}"
  echo "KERBY_ADMIN_HOST=${KERBY_ADMIN_HOST}"
  echo "KERBY_ADMIN_PORT=${KERBY_ADMIN_PORT}"
  echo "KERBY_ADMIN_PROTOCOL=${KERBY_ADMIN_PROTOCOL}"
  echo "KERBY_KADMIN_ATTEMPTS=${KERBY_KADMIN_ATTEMPTS:-30}"
  echo "KERBY_KADMIN_RETRY_DELAY_SECONDS=${KERBY_KADMIN_RETRY_DELAY_SECONDS:-1}"
  echo "JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:-}"
  dump_file "kdc.conf" "${KERBY_CONF_DIR}/kdc.conf"
  dump_file "server krb5.conf" "${KERBY_CONF_DIR}/krb5.conf"
  dump_file "client krb5.conf" "${KERBY_CLIENT_CONF_DIR}/krb5.conf"
  dump_file "backend.conf" "${KERBY_CONF_DIR}/backend.conf"
  dump_file "adminServer.conf" "${KERBY_CONF_DIR}/adminServer.conf"
}

normalize_principal() {
  case "$1" in
    *@*) printf '%s' "$1" ;;
    *) printf '%s@%s' "$1" "${KERBY_REALM}" ;;
  esac
}

kadmin_query_once() {
  java -cp "${CLASSPATH}" \
    -DKERBY_LOGFILE=kadmin \
    org.apache.kerby.kerberos.tool.kadmin.KadminTool \
    "${KERBY_CONF_DIR}" -k "${KERBY_KEYTAB_DIR}/admin.keytab" -q "$1"
}

kadmin_query() {
  query="$1"
  attempts="${KERBY_KADMIN_ATTEMPTS:-30}"
  delay_seconds="${KERBY_KADMIN_RETRY_DELAY_SECONDS:-1}"
  output_file="${KERBY_WORK_DIR}/kadmin-query.out"
  attempt=1

  while [ "${attempt}" -le "${attempts}" ]; do
    set +e
    kadmin_query_once "${query}" > "${output_file}" 2>&1
    status="$?"
    set -e

    if [ "${status}" -eq 0 ] \
      && ! grep -Eq 'Could not login with:|Cannot locate KDC|authentication failed' "${output_file}"; then
      cat "${output_file}"
      return 0
    fi

    if ! grep -Eq 'Could not login with:|Cannot locate KDC|authentication failed' "${output_file}"; then
      cat "${output_file}"
      return "${status}"
    fi

    if [ "${attempt}" -lt "${attempts}" ]; then
      echo "Kerby KDC admin is not ready for query '${query}', retry ${attempt}/${attempts}." >&2
      sleep "${delay_seconds}"
    fi

    attempt=$((attempt + 1))
  done

  cat "${output_file}" >&2
  if debug_enabled; then
    dump_file "server krb5.conf after failed kadmin query" "${KERBY_CONF_DIR}/krb5.conf" >&2
  fi
  echo "Timed out waiting for Kerby KDC admin to accept query '${query}'." >&2
  return 1
}

remember_keytab() {
  REQUIRED_KEYTABS="${REQUIRED_KEYTABS}${REQUIRED_KEYTABS:+ }$1"
}

require_keytabs() {
  missing=""
  for keytab_file in ${REQUIRED_KEYTABS}; do
    if [ ! -s "${keytab_file}" ]; then
      missing="${missing}${missing:+ }${keytab_file}"
    fi
  done

  if [ -n "${missing}" ]; then
    echo "Kerby KDC did not generate expected keytab files: ${missing}" >&2
    return 1
  fi
}

add_password_principal() {
  principal="$(normalize_principal "$1")"
  password="$2"
  kadmin_query "addprinc -pw ${password} ${principal}"
}

add_service_principal() {
  principal="$(normalize_principal "$1")"
  keytab_file="$2"
  kadmin_query "addprinc -randkey ${principal}"
  kadmin_query "ktadd -k ${keytab_file} ${principal}"
  remember_keytab "${keytab_file}"
}

wait_for_kdc() {
  for _ in $(seq 1 30); do
    if (echo > "/dev/tcp/127.0.0.1/${KERBY_KDC_TCP_PORT}") >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for KDC on port ${KERBY_KDC_TCP_PORT}" >&2
  return 1
}

append_java_tool_option "-Djava.security.krb5.conf=${KERBY_CONF_DIR}/krb5.conf"
if debug_enabled; then
  append_java_tool_option "-Dlog4j.configuration=file:${KERBY_HOME}/log4j-debug.properties"
  append_java_tool_option "-Dsun.security.krb5.debug=true"
  append_java_tool_option "-Dsun.security.spnego.debug=true"
  append_java_tool_option "-Dsun.security.jgss.debug=true"
fi
dump_debug_configuration

if [ ! -f "${KERBY_KEYTAB_DIR}/admin.keytab" ]; then
  java -cp "${CLASSPATH}" \
    -DKERBY_LOGFILE=kdcinit \
    org.apache.kerby.kerberos.tool.kdcinit.KdcInitTool \
    "${KERBY_CONF_DIR}" "${KERBY_KEYTAB_DIR}"
fi

java -cp "${CLASSPATH}" \
  -DKERBY_LOGFILE=kdc \
  org.apache.kerby.kerberos.kdc.KerbyKdcServer \
  -start "${KERBY_CONF_DIR}" "${KERBY_WORK_DIR}" &
KDC_PID="$!"

trap 'kill "${KDC_PID}" >/dev/null 2>&1 || true' INT TERM

wait_for_kdc

add_password_principal "${KERBY_CLIENT_PRINCIPAL}" "${KERBY_CLIENT_PASSWORD}"
add_service_principal "${KERBY_SERVICE_PRINCIPAL}" "${KERBY_SERVICE_KEYTAB}"

if [ -n "${KERBY_EXTRA_PRINCIPALS:-}" ]; then
  OLD_IFS="${IFS}"
  IFS=','
  for entry in ${KERBY_EXTRA_PRINCIPALS}; do
    principal="${entry%%:*}"
    password="${entry#*:}"
    if [ -n "${principal}" ] && [ "${principal}" != "${password}" ]; then
      add_password_principal "${principal}" "${password}"
    fi
  done
  IFS="${OLD_IFS}"
fi

if [ -n "${KERBY_EXTRA_SERVICE_PRINCIPALS:-}" ]; then
  OLD_IFS="${IFS}"
  IFS=','
  for entry in ${KERBY_EXTRA_SERVICE_PRINCIPALS}; do
    principal="${entry%%:*}"
    keytab_file="${entry#*:}"
    if [ "${principal}" = "${keytab_file}" ]; then
      safe_name="$(printf '%s' "${principal}" | tr '/@' '__')"
      keytab_file="${KERBY_KEYTAB_DIR}/${safe_name}.keytab"
    fi
    if [ -n "${principal}" ]; then
      add_service_principal "${principal}" "${keytab_file}"
    fi
  done
  IFS="${OLD_IFS}"
fi

require_keytabs
printf 'ready realm=%s\n' "${KERBY_REALM}" > "${KERBY_READY_FILE}"
echo "Kerby KDC container ready."
wait "${KDC_PID}"
