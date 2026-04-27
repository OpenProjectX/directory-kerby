/**
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements. See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership. The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied. See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.kerby.testcontainers;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Testcontainers wrapper for the Kerby KDC Docker image.
 */
public class KerbyKdcContainer extends GenericContainer<KerbyKdcContainer> {
    public static final DockerImageName DEFAULT_IMAGE_NAME =
        DockerImageName.parse("apache/kerby-kdc:latest");
    public static final int KDC_PORT = 88;
    public static final String DEFAULT_REALM = "EXAMPLE.COM";
    public static final String DEFAULT_CLIENT_PRINCIPAL = "client";
    public static final String DEFAULT_CLIENT_PASSWORD = "client";
    public static final String DEFAULT_SERVICE_PRINCIPAL = "HTTP/localhost";
    public static final String DEFAULT_KEYTAB_DIR = "/var/lib/kerby/keytabs";
    public static final String DEFAULT_SERVICE_KEYTAB = "/var/lib/kerby/keytabs/service.keytab";

    private String realm = DEFAULT_REALM;
    private String clientPrincipal = DEFAULT_CLIENT_PRINCIPAL;
    private String clientPassword = DEFAULT_CLIENT_PASSWORD;
    private String servicePrincipal = DEFAULT_SERVICE_PRINCIPAL;
    private String serviceKeytab = DEFAULT_SERVICE_KEYTAB;
    private final Map<String, String> extraPrincipals = new LinkedHashMap<>();
    private final Map<String, String> extraServicePrincipals = new LinkedHashMap<>();

    public KerbyKdcContainer() {
        this(DEFAULT_IMAGE_NAME);
    }

    public KerbyKdcContainer(String dockerImageName) {
        this(DockerImageName.parse(dockerImageName));
    }

    public KerbyKdcContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        withExposedPorts(KDC_PORT);
        waitingFor(Wait.forLogMessage(".*Kerby KDC container ready\\..*", 1)
            .withStartupTimeout(Duration.ofSeconds(60)));
        applyConfiguration();
    }

    public KerbyKdcContainer withRealm(String realm) {
        this.realm = realm;
        return applyConfiguration();
    }

    public KerbyKdcContainer withClientPrincipal(String principal, String password) {
        this.clientPrincipal = principal;
        this.clientPassword = password;
        return applyConfiguration();
    }

    public KerbyKdcContainer withServicePrincipal(String principal) {
        this.servicePrincipal = principal;
        return applyConfiguration();
    }

    public KerbyKdcContainer withServicePrincipal(String principal, String keytabPath) {
        this.servicePrincipal = principal;
        this.serviceKeytab = keytabPath;
        return applyConfiguration();
    }

    public KerbyKdcContainer withPrincipal(String principal, String password) {
        extraPrincipals.put(principal, password);
        return applyConfiguration();
    }

    public KerbyKdcContainer withPrincipals(Map<String, String> principals) {
        extraPrincipals.putAll(principals);
        return applyConfiguration();
    }

    public KerbyKdcContainer withServicePrincipals(String... principals) {
        for (String principal : principals) {
            extraServicePrincipals.put(principal, null);
        }
        return applyConfiguration();
    }

    public KerbyKdcContainer withAdditionalServicePrincipal(String principal) {
        extraServicePrincipals.put(principal, null);
        return applyConfiguration();
    }

    public KerbyKdcContainer withAdditionalServicePrincipal(String principal, String keytabPath) {
        extraServicePrincipals.put(principal, keytabPath);
        return applyConfiguration();
    }

    public KerbyKdcContainer withExtraPrincipals(String principals) {
        withEnv("KERBY_EXTRA_PRINCIPALS", principals);
        return this;
    }

    public KerbyKdcContainer withExtraServicePrincipals(String principals) {
        withEnv("KERBY_EXTRA_SERVICE_PRINCIPALS", principals);
        return this;
    }

    public String getRealm() {
        return realm;
    }

    public String getClientPrincipal() {
        return qualifyPrincipal(clientPrincipal);
    }

    public String getClientPassword() {
        return clientPassword;
    }

    public String getServicePrincipal() {
        return qualifyPrincipal(servicePrincipal);
    }

    public String getServiceKeytab() {
        return serviceKeytab;
    }

    public String getServiceKeytab(String principal) {
        String keytab = extraServicePrincipals.get(principal);
        if (keytab != null) {
            return keytab;
        }
        return defaultKeytabPath(principal);
    }

    public void copyServiceKeytabTo(Path target) {
        copyFileFromContainer(serviceKeytab, target.toAbsolutePath().toString());
    }

    public void copyServiceKeytabTo(String principal, Path target) {
        copyFileFromContainer(getServiceKeytab(principal), target.toAbsolutePath().toString());
    }

    public String getKdcHost() {
        return getHost();
    }

    public int getKdcPort() {
        return getMappedPort(KDC_PORT);
    }

    public String getKrb5Conf() {
        return "[libdefaults]\n"
            + "    default_realm = " + realm + "\n"
            + "    kdc_realm = " + realm + "\n"
            + "    udp_preference_limit = 1\n"
            + "    kdc_tcp_port = " + getKdcPort() + "\n"
            + "\n"
            + "[realms]\n"
            + "    " + realm + " = {\n"
            + "        kdc = " + getKdcHost() + ":" + getKdcPort() + "\n"
            + "    }\n";
    }

    private KerbyKdcContainer applyConfiguration() {
        withEnv("KERBY_REALM", realm);
        withEnv("KERBY_KDC_HOST", "localhost");
        withEnv("KERBY_KDC_TCP_PORT", Integer.toString(KDC_PORT));
        withEnv("KERBY_CLIENT_PRINCIPAL", clientPrincipal);
        withEnv("KERBY_CLIENT_PASSWORD", clientPassword);
        withEnv("KERBY_SERVICE_PRINCIPAL", servicePrincipal);
        withEnv("KERBY_SERVICE_KEYTAB", serviceKeytab);
        withEnv("KERBY_EXTRA_PRINCIPALS", joinPasswordPrincipals());
        withEnv("KERBY_EXTRA_SERVICE_PRINCIPALS", joinServicePrincipals());
        return this;
    }

    private String qualifyPrincipal(String principal) {
        if (principal.indexOf('@') >= 0) {
            return principal;
        }
        return principal + "@" + realm;
    }

    private String joinPasswordPrincipals() {
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> entry : extraPrincipals.entrySet()) {
            appendSeparator(value);
            value.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return value.toString();
    }

    private String joinServicePrincipals() {
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> entry : extraServicePrincipals.entrySet()) {
            appendSeparator(value);
            value.append(entry.getKey());
            if (entry.getValue() != null) {
                value.append(':').append(entry.getValue());
            }
        }
        return value.toString();
    }

    private void appendSeparator(StringBuilder value) {
        if (value.length() > 0) {
            value.append(',');
        }
    }

    private String defaultKeytabPath(String principal) {
        return DEFAULT_KEYTAB_DIR + "/" + qualifyPrincipal(principal)
            .replace('/', '_').replace('@', '_') + ".keytab";
    }
}
