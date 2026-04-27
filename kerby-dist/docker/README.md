# Kerby Docker Image

This module builds the Kerby KDC Docker image from the current repository
checkout. Build from the repository root with `-am` so Maven builds the local
`kdc-dist` distribution first instead of resolving snapshot artifacts remotely.

```bash
mvn -Dmaven.repo.local=/home/coder/.m2/repository -Pdist,docker -DskipTests \
  -Ddocker.image.name=openprojectx/kerby-kdc \
  -Ddocker.image.tag.sha=test \
  -pl kerby-dist/docker -am package
```

## Docker Compose

Use `docker-compose.yml` in this directory as a local Kerby KDC example:

```bash
cd kerby-dist/docker
docker compose up -d
docker compose exec kerby-kdc ls -l /var/lib/kerby/keytabs
docker compose exec kerberos-client cat /kerby/client/krb5.conf
```

The example creates:

`app_user@EXAMPLE.COM` with password `app-user-secret`

`alice@EXAMPLE.COM` with password `alice-secret`

`bob@EXAMPLE.COM` with password `bob-secret`

`HTTP/api.example.com@EXAMPLE.COM` with keytab
`/var/lib/kerby/keytabs/http-api.keytab`

`hive/hiveserver2.example.com@EXAMPLE.COM` with keytab
`/var/lib/kerby/keytabs/hive.keytab`

`kafka/broker1.example.com@EXAMPLE.COM` with keytab
`/var/lib/kerby/keytabs/kafka.keytab`

Copy a generated keytab to the host:

```bash
docker compose cp kerby-kdc:/var/lib/kerby/keytabs/hive.keytab ./hive.keytab
```

The `kerby-data` volume stores the JSON identity backend and generated keytabs.
Remove it when you want a clean realm:

```bash
docker compose down -v
```

The KDC also generates a client Kerberos config in the same volume:

```text
/var/lib/kerby/client/krb5.conf
```

Other containers can mount the `kerby-data` volume read-only and use that file
as `KRB5_CONFIG`. The Compose example includes a MIT krb5 client container:

```yaml
kerberos-client:
  image: ghcr.io/openprojectx/kerberos:latest
  entrypoint: ["/bin/sh", "-c"]
  command: ["sleep infinity"]
  environment:
    KRB5_CONFIG: /kerby/client/krb5.conf
    KRB5_TRACE: /dev/stderr
  volumes:
    - kerby-data:/kerby:ro
```

The client image has its own KDC entrypoint, so the Compose example overrides
`entrypoint` and `command` to keep the container running as a client toolbox
only.

## Principal Configuration

The image supports one default password principal and one default service
principal:

```text
KERBY_CLIENT_PRINCIPAL=app_user@EXAMPLE.COM
KERBY_CLIENT_PASSWORD=app-user-secret
KERBY_SERVICE_PRINCIPAL=HTTP/api.example.com@EXAMPLE.COM
KERBY_SERVICE_KEYTAB=/var/lib/kerby/keytabs/http-api.keytab
```

The shared client config is generated from:

```text
KERBY_CLIENT_KDC_HOST=kerby-kdc
KERBY_CLIENT_KDC_PORT=88
KERBY_CLIENT_DOMAIN=example.com
KERBY_CLIENT_CONF_DIR=/var/lib/kerby/client
```

The Compose example also disables KDC preauthentication for MIT krb5 client
interoperability in local development:

```text
KERBY_PREAUTH_REQUIRED=false
KERBY_PA_ENC_TIMESTAMP_REQUIRED=false
```

Without this, MIT `kinit` can fail with `Generic preauthentication failure`
after the KDC returns `Additional pre-authentication required`.

Add more password principals with `KERBY_EXTRA_PRINCIPALS` as a comma-separated
list of `principal:password` entries:

```text
KERBY_EXTRA_PRINCIPALS=alice@EXAMPLE.COM:alice-secret,bob@EXAMPLE.COM:bob-secret
```

Add more service principals with `KERBY_EXTRA_SERVICE_PRINCIPALS` as a
comma-separated list of `principal:/container/keytab/path` entries:

```text
KERBY_EXTRA_SERVICE_PRINCIPALS=hive/hiveserver2.example.com@EXAMPLE.COM:/var/lib/kerby/keytabs/hive.keytab,kafka/broker1.example.com@EXAMPLE.COM:/var/lib/kerby/keytabs/kafka.keytab
```

## MIT krb5 Client Usage

Install the MIT krb5 client tools on the host. Package names vary by
distribution:

```bash
sudo apt-get install krb5-user
```

Create a host-side client config that points at the Compose service published
on localhost:

```bash
cat > ./krb5.conf <<'EOF'
[libdefaults]
    default_realm = EXAMPLE.COM
    dns_lookup_kdc = false
    dns_lookup_realm = false
    rdns = false
    udp_preference_limit = 1

[realms]
    EXAMPLE.COM = {
        kdc = 127.0.0.1:88
    }

[domain_realm]
    .example.com = EXAMPLE.COM
    example.com = EXAMPLE.COM
EOF
```

Start the KDC and get a user ticket:

```bash
docker compose up -d
KRB5_CONFIG="$PWD/krb5.conf" KRB5_TRACE=/dev/stderr  kinit -V app_user@EXAMPLE.COM
```

Use `app-user-secret` when prompted. Inspect the credential cache:

```bash
KRB5_CONFIG="$PWD/krb5.conf" klist
```

Request service tickets for the service principals created by the Compose
file:

```bash
KRB5_CONFIG="$PWD/krb5.conf" kvno HTTP/api.example.com@EXAMPLE.COM
KRB5_CONFIG="$PWD/krb5.conf" kvno hive/hiveserver2.example.com@EXAMPLE.COM
KRB5_CONFIG="$PWD/krb5.conf" kvno kafka/broker1.example.com@EXAMPLE.COM
KRB5_CONFIG="$PWD/krb5.conf" klist
```

Copy a generated service keytab from the KDC container and inspect it with MIT
krb5:

```bash
docker compose cp kerby-kdc:/var/lib/kerby/keytabs/hive.keytab ./hive.keytab
KRB5_CONFIG="$PWD/krb5.conf" klist -kte ./hive.keytab
```

Validate that a service principal can authenticate from its keytab:

```bash
KRB5_CONFIG="$PWD/krb5.conf" \
  kinit -kt ./hive.keytab hive/hiveserver2.example.com@EXAMPLE.COM
KRB5_CONFIG="$PWD/krb5.conf" klist
```

If the MIT client runs from another Compose service on the same Docker network,
use the service name instead of the published localhost port:

```text
[realms]
    EXAMPLE.COM = {
        kdc = kerby-kdc:88
    }
```

Clean up local credentials when finished:

```bash
KRB5_CONFIG="$PWD/krb5.conf" kdestroy
```

## MIT krb5 Client Container Usage

The Compose file includes `kerberos-client` using
`ghcr.io/openprojectx/kerberos:latest`. It mounts the shared Kerby data volume
read-only at `/kerby`, so it can read:

`/kerby/client/krb5.conf`

`/kerby/keytabs/http-api.keytab`

`/kerby/keytabs/hive.keytab`

`/kerby/keytabs/kafka.keytab`

Start both services:

```bash
docker compose up -d
```

Get a user TGT from the client container:

```bash
printf 'app-user-secret\n' \
  | docker compose exec -T kerberos-client \
      kinit app_user@EXAMPLE.COM
```

List the ticket cache:

```bash
docker compose exec kerberos-client klist
```

Request service tickets:

```bash
docker compose exec kerberos-client kvno HTTP/api.example.com@EXAMPLE.COM
docker compose exec kerberos-client kvno hive/hiveserver2.example.com@EXAMPLE.COM
docker compose exec kerberos-client kvno kafka/broker1.example.com@EXAMPLE.COM
```

Inspect generated keytabs from the client container:

```bash
docker compose exec kerberos-client klist -kte /kerby/keytabs/http-api.keytab
docker compose exec kerberos-client klist -kte /kerby/keytabs/hive.keytab
docker compose exec kerberos-client klist -kte /kerby/keytabs/kafka.keytab
```

Authenticate using a service keytab:

```bash
docker compose exec kerberos-client \
  kinit -kt /kerby/keytabs/hive.keytab hive/hiveserver2.example.com@EXAMPLE.COM
docker compose exec kerberos-client klist
```
