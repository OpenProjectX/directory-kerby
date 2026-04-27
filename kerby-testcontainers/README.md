# Kerby Testcontainers

This module provides a typed Testcontainers wrapper for the Kerby KDC Docker
image built from the repository root `Dockerfile`.

Build the image locally:

```bash
docker build -t apache/kerby-kdc:latest .
```

Use it from a test:

```java
try (KerbyKdcContainer kdc = new KerbyKdcContainer()
        .withRealm("EXAMPLE.COM")
        .withClientPrincipal("alice", "alice-secret")
        .withServicePrincipal("HTTP/service.example.com")) {
    kdc.start();
    String krb5Conf = kdc.getKrb5Conf();
}
```

The container supports these environment variables:

`KERBY_REALM`, `KERBY_KDC_HOST`, `KERBY_KDC_BIND_HOST`,
`KERBY_KDC_TCP_PORT`, `KERBY_KDC_UDP_PORT`, `KERBY_CLIENT_PRINCIPAL`,
`KERBY_CLIENT_PASSWORD`, `KERBY_SERVICE_PRINCIPAL`, `KERBY_SERVICE_KEYTAB`,
`KERBY_EXTRA_PRINCIPALS`, and `KERBY_EXTRA_SERVICE_PRINCIPALS`.

`KERBY_EXTRA_PRINCIPALS` is a comma-separated list of `principal:password`
entries. `KERBY_EXTRA_SERVICE_PRINCIPALS` is a comma-separated list of
`principal` or `principal:/path/to/keytab` entries.

