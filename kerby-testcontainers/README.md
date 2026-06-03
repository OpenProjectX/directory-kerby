# Kerby Testcontainers

This module provides a typed Testcontainers wrapper for the Kerby KDC Docker
image built by `kerby-dist/docker`.

Build the image locally:

```bash
mvn -Dmaven.repo.local=/home/coder/.m2/repository -Pdist,docker -DskipTests \
  -Ddocker.image.name=openprojectx/kerby-kdc \
  -Ddocker.image.tag.sha=test \
  -pl kerby-dist/docker -am package
```

Use `-am` when building from the repository root. It makes Maven build the
current checkout's `kdc-dist` reactor module first, then the Docker module
unpacks that local distribution into the image. Running the Docker module in
isolation can make Maven try to resolve `*-SNAPSHOT` artifacts from remote
repositories.

Run the end-to-end Testcontainers test:

```bash
mvn -Dmaven.repo.local=/home/coder/.m2/repository -pl kerby-testcontainers -am \
  -Dtest=KerbyKdcContainerE2ETest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkerby.testcontainers.image=openprojectx/kerby-kdc:latest \
  test
```

If the test is launched from IntelliJ IDEA, reimport Maven after dependency
changes and verify the test classpath uses Testcontainers `1.21.4` or newer.
Older Testcontainers versions may use Docker API `1.32`; Docker `29.x` rejects
that client API because it requires API `1.40` or newer. If Docker is running
but Testcontainers still cannot discover it from IDEA, set these run
configuration environment variables:

```text
DOCKER_HOST=unix:///var/run/docker.sock
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

Use it from a test:

```java
try (KerbyKdcContainer kdc = new KerbyKdcContainer()
        .withRealm("EXAMPLE.COM")
        .withClientPrincipal("alice", "alice-secret")
        .withServicePrincipal("HTTP/service.example.com")
        .withServicePrincipals(
            "HTTP/api.example.com@EXAMPLE.COM",
            "hive/hiveserver2.example.com@EXAMPLE.COM",
            "kafka/broker1.example.com@EXAMPLE.COM")
        .withPrincipal("app_user@EXAMPLE.COM", "app-user-secret")) {
    kdc.start();
    String krb5Conf = kdc.getKrb5Conf();
}
```

The container waits for the image readiness log. The image now emits that log
only after requested service keytabs exist and `/var/lib/kerby/ready` has been
written, so a failed Kerby admin bootstrap fails container startup instead of
appearing ready.

`withServicePrincipals(...)` adds service principals and exports a keytab for
each principal under `/var/lib/kerby/keytabs`. Use
`copyServiceKeytabTo(principal, targetPath)` to copy one of those generated
keytabs to the host test filesystem. Use
`withAdditionalServicePrincipal(principal, keytabPath)` when a specific
container-side keytab path is required.

The container supports these environment variables:

`KERBY_REALM`, `KERBY_KDC_HOST`, `KERBY_KDC_BIND_HOST`,
`KERBY_KDC_TCP_PORT`, `KERBY_KDC_UDP_PORT`, `KERBY_CLIENT_PRINCIPAL`,
`KERBY_CLIENT_PASSWORD`, `KERBY_SERVICE_PRINCIPAL`, `KERBY_SERVICE_KEYTAB`,
`KERBY_EXTRA_PRINCIPALS`, `KERBY_EXTRA_SERVICE_PRINCIPALS`, and
`KERBY_READY_FILE`.

`KERBY_KDC_HOST` defaults to `127.0.0.1` for KDC-internal Kerby Java bootstrap.
Use `KERBY_CLIENT_KDC_HOST` when the generated client `krb5.conf` should point
at a Docker network alias or another host name.

`KERBY_EXTRA_PRINCIPALS` is a comma-separated list of `principal:password`
entries. `KERBY_EXTRA_SERVICE_PRINCIPALS` is a comma-separated list of
`principal` or `principal:/path/to/keytab` entries.
