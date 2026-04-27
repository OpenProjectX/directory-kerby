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

KERBY_REALM="${KERBY_REALM:-EXAMPLE.COM}"
KERBY_KDC_BIND_HOST="${KERBY_KDC_BIND_HOST:-0.0.0.0}"
KERBY_KDC_HOST="${KERBY_KDC_HOST:-localhost}"
KERBY_KDC_TCP_PORT="${KERBY_KDC_TCP_PORT:-88}"
KERBY_KDC_UDP_PORT="${KERBY_KDC_UDP_PORT:-88}"
KERBY_ADMIN_PORT="${KERBY_ADMIN_PORT:-65417}"
KERBY_ADMIN_HOST="${KERBY_ADMIN_HOST:-localhost}"
KERBY_ADMIN_PROTOCOL="${KERBY_ADMIN_PROTOCOL:-adminprotocol}"
KERBY_CLIENT_PRINCIPAL="${KERBY_CLIENT_PRINCIPAL:-client}"
KERBY_CLIENT_PASSWORD="${KERBY_CLIENT_PASSWORD:-client}"
KERBY_SERVICE_PRINCIPAL="${KERBY_SERVICE_PRINCIPAL:-HTTP/localhost}"
KERBY_SERVICE_KEYTAB="${KERBY_SERVICE_KEYTAB:-${KERBY_KEYTAB_DIR}/service.keytab}"

CLASSPATH="${KERBY_HOME}/lib/*:${KERBY_HOME}"

mkdir -p "${KERBY_CONF_DIR}" "${KERBY_DATA_DIR}" "${KERBY_WORK_DIR}" "${KERBY_KEYTAB_DIR}" "${KERBY_BACKEND_DIR}"

cat > "${KERBY_CONF_DIR}/kdc.conf" <<EOF
[kdcdefaults]
  kdc_host = ${KERBY_KDC_BIND_HOST}
  kdc_udp_port = ${KERBY_KDC_UDP_PORT}
  kdc_tcp_port = ${KERBY_KDC_TCP_PORT}
  kdc_realm = ${KERBY_REALM}
EOF

cat > "${KERBY_CONF_DIR}/krb5.conf" <<EOF
[libdefaults]
    kdc_realm = ${KERBY_REALM}
    default_realm = ${KERBY_REALM}
    udp_preference_limit = 1
    kdc_tcp_port = ${KERBY_KDC_TCP_PORT}
    kdc_udp_port = ${KERBY_KDC_UDP_PORT}

[realms]
    ${KERBY_REALM} = {
        kdc = ${KERBY_KDC_HOST}:${KERBY_KDC_TCP_PORT}
    }
EOF

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

normalize_principal() {
  case "$1" in
    *@*) printf '%s' "$1" ;;
    *) printf '%s@%s' "$1" "${KERBY_REALM}" ;;
  esac
}

kadmin_query() {
  java -cp "${CLASSPATH}" \
    -DKERBY_LOGFILE=kadmin \
    org.apache.kerby.kerberos.tool.kadmin.KadminTool \
    "${KERBY_CONF_DIR}" -k "${KERBY_KEYTAB_DIR}/admin.keytab" -q "$1"
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
}

if [ ! -f "${KERBY_KEYTAB_DIR}/admin.keytab" ]; then
  java -cp "${CLASSPATH}" \
    -DKERBY_LOGFILE=kdcinit \
    org.apache.kerby.kerberos.tool.kdcinit.KdcInitTool \
    "${KERBY_CONF_DIR}" "${KERBY_KEYTAB_DIR}"
fi

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

exec java -cp "${CLASSPATH}" \
  -DKERBY_LOGFILE=kdc \
  org.apache.kerby.kerberos.kdc.KerbyKdcServer \
  -start "${KERBY_CONF_DIR}" "${KERBY_WORK_DIR}"

