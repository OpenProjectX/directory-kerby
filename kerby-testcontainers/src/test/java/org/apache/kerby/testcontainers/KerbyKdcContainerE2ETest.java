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

import org.apache.kerby.kerberos.kerb.client.KrbClient;
import org.apache.kerby.kerberos.kerb.type.ticket.SgtTicket;
import org.apache.kerby.kerberos.kerb.type.ticket.TgtTicket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

import javax.security.auth.Subject;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.security.PrivilegedExceptionAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.MessageProp;
import org.ietf.jgss.Oid;

import static org.assertj.core.api.Assertions.assertThat;

public class KerbyKdcContainerE2ETest {
    private static final Oid KRB5_OID = oid("1.2.840.113554.1.2.2");
    private static final String CLIENT = "alice";
    private static final String CLIENT_PASSWORD = "alice-secret";
    private static final String SERVICE = "HTTP/localhost";
    private static final String API_SERVICE = "HTTP/api.example.com@EXAMPLE.COM";
    private static final String HIVE_SERVICE = "hive/hiveserver2.example.com@EXAMPLE.COM";
    private static final String KAFKA_SERVICE = "kafka/broker1.example.com@EXAMPLE.COM";
    private static final String APP_USER = "app_user@EXAMPLE.COM";
    private static final String APP_USER_PASSWORD = "app-user-secret";
    private static final String MESSAGE = "kerby-testcontainers-e2e";

    @TempDir
    private Path testDir;

    private String previousKrb5Conf;
    private String previousUseSubjectCredsOnly;

    @AfterEach
    public void restoreSystemProperties() {
        restore("java.security.krb5.conf", previousKrb5Conf);
        restore("javax.security.auth.useSubjectCredsOnly", previousUseSubjectCredsOnly);
        Configuration.setConfiguration(null);
    }

    @Test
    public void clientObtainsServiceTicketAndAuthenticatesToService() throws Exception {
        assumeDockerAvailable();

        String imageName = System.getProperty("kerby.testcontainers.image",
            KerbyKdcContainer.DEFAULT_IMAGE_NAME.asCanonicalNameString());

        try (KerbyKdcContainer kdc = new KerbyKdcContainer(imageName)
                .withRealm("EXAMPLE.COM")
                .withClientPrincipal(CLIENT, CLIENT_PASSWORD)
                .withServicePrincipal(SERVICE)
                .withServicePrincipals(API_SERVICE, HIVE_SERVICE, KAFKA_SERVICE)
                .withPrincipal(APP_USER, APP_USER_PASSWORD)) {
            kdc.start();

            configureJavaKerberos(kdc);

            Path serviceKeytab = testDir.resolve("service.keytab");
            Path hiveKeytab = testDir.resolve("hive.keytab");
            Path kafkaKeytab = testDir.resolve("kafka.keytab");
            Path readyFile = testDir.resolve("ready");
            Path ticketCache = testDir.resolve("alice.ccache");
            kdc.copyFileFromContainer(kdc.getReadyFile(), readyFile.toAbsolutePath().toString());
            kdc.copyServiceKeytabTo(serviceKeytab);
            kdc.copyServiceKeytabTo(HIVE_SERVICE, hiveKeytab);
            kdc.copyServiceKeytabTo(KAFKA_SERVICE, kafkaKeytab);
            assertThat(readyFile).exists().isNotEmptyFile();
            assertThat(serviceKeytab).exists().isNotEmptyFile();
            assertThat(hiveKeytab).exists().isNotEmptyFile();
            assertThat(kafkaKeytab).exists().isNotEmptyFile();

            KrbClient client = createKerbyClient(kdc);
            TgtTicket tgt = client.requestTgt(kdc.getClientPrincipal(), kdc.getClientPassword());
            assertThat(tgt).isNotNull();

            SgtTicket sgt = client.requestSgt(tgt, kdc.getServicePrincipal());
            assertThat(sgt).isNotNull();
            assertServiceTicket(client, tgt, API_SERVICE);
            assertServiceTicket(client, tgt, HIVE_SERVICE);
            assertServiceTicket(client, tgt, KAFKA_SERVICE);

            TgtTicket appUserTgt = client.requestTgt(APP_USER, APP_USER_PASSWORD);
            assertThat(appUserTgt).isNotNull();

            client.storeTicket(tgt, ticketCache.toFile());

            Subject serviceSubject = loginWithKeytab(kdc.getServicePrincipal(), serviceKeytab.toFile(), false);
            Subject clientSubject = loginWithTicketCache(kdc.getClientPrincipal(), ticketCache.toFile());

            String authenticatedClient = completeGssExchange(clientSubject, serviceSubject, kdc.getServicePrincipal());
            assertThat(authenticatedClient).contains(CLIENT);
        }
    }

    private void assertServiceTicket(KrbClient client, TgtTicket tgt, String servicePrincipal)
            throws Exception {
        SgtTicket sgt = client.requestSgt(tgt, servicePrincipal);
        assertThat(sgt).isNotNull();
    }

    private void assumeDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false,
                "Docker is required for the Kerby KDC container E2E test: " + e.getMessage()
                    + ". Diagnostics: " + dockerDiagnostics());
        }
    }

    private String dockerDiagnostics() {
        return "user=" + System.getProperty("user.name")
            + ", docker.host.property=" + System.getProperty("docker.host")
            + ", DOCKER_HOST=" + System.getenv("DOCKER_HOST")
            + ", TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="
            + System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE")
            + ", /var/run/docker.sock=" + socketStatus(Paths.get("/var/run/docker.sock"))
            + ", /run/docker.sock=" + socketStatus(Paths.get("/run/docker.sock"));
    }

    private String socketStatus(Path socket) {
        try {
            return "{exists=" + Files.exists(socket)
                + ", readable=" + Files.isReadable(socket)
                + ", writable=" + Files.isWritable(socket)
                + ", realPath=" + (Files.exists(socket) ? socket.toRealPath().toString() : "missing")
                + "}";
        } catch (IOException e) {
            return "{error=" + e.getMessage() + "}";
        }
    }

    private KrbClient createKerbyClient(KerbyKdcContainer kdc) throws Exception {
        KrbClient client = new KrbClient();
        client.setKdcRealm(kdc.getRealm());
        client.setKdcHost(kdc.getKdcHost());
        client.setKdcTcpPort(kdc.getKdcPort());
        client.setAllowUdp(false);
        client.setTimeout(10);
        client.init();
        return client;
    }

    private void configureJavaKerberos(KerbyKdcContainer kdc) throws Exception {
        Path krb5Conf = testDir.resolve("krb5.conf");
        Files.write(krb5Conf, kdc.getKrb5Conf().getBytes(StandardCharsets.UTF_8));

        previousKrb5Conf = System.getProperty("java.security.krb5.conf");
        previousUseSubjectCredsOnly = System.getProperty("javax.security.auth.useSubjectCredsOnly");
        System.setProperty("java.security.krb5.conf", krb5Conf.toAbsolutePath().toString());
        System.setProperty("javax.security.auth.useSubjectCredsOnly", "true");
    }

    private String completeGssExchange(Subject clientSubject, Subject serviceSubject,
                                       String servicePrincipal) throws Exception {
        ServerSocket serverSocket = new ServerSocket(0);
        FutureTask<String> serverTask = new FutureTask<>(() -> Subject.doAs(serviceSubject,
            (PrivilegedExceptionAction<String>) () -> runService(serverSocket, servicePrincipal)));
        Thread serverThread = new Thread(serverTask, "kerby-gss-e2e-server");
        serverThread.start();

        Subject.doAs(clientSubject, (PrivilegedExceptionAction<Void>) () -> {
            runClient("localhost", serverSocket.getLocalPort(), servicePrincipal);
            return null;
        });

        return serverTask.get(30, TimeUnit.SECONDS);
    }

    private String runService(ServerSocket serverSocket, String servicePrincipal) throws Exception {
        try (ServerSocket ignored = serverSocket;
             Socket socket = serverSocket.accept()) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            GSSManager manager = GSSManager.getInstance();
            GSSName serviceName = manager.createName(servicePrincipal, GSSName.NT_USER_NAME);
            GSSCredential credential = manager.createCredential(serviceName,
                GSSCredential.DEFAULT_LIFETIME, KRB5_OID, GSSCredential.ACCEPT_ONLY);
            GSSContext context = manager.createContext(credential);

            byte[] token;
            while (!context.isEstablished()) {
                token = readToken(in);
                token = context.acceptSecContext(token, 0, token.length);
                if (token != null) {
                    writeToken(out, token);
                }
            }

            MessageProp prop = new MessageProp(0, false);
            byte[] wrapped = readToken(in);
            byte[] unwrapped = context.unwrap(wrapped, 0, wrapped.length, prop);
            assertThat(new String(unwrapped, StandardCharsets.UTF_8)).isEqualTo(MESSAGE);

            byte[] mic = context.getMIC(unwrapped, 0, unwrapped.length, prop);
            writeToken(out, mic);
            String clientName = context.getSrcName().toString();
            context.dispose();
            return clientName;
        }
    }

    private void runClient(String host, int port, String servicePrincipal) throws Exception {
        try (Socket socket = new Socket(host, port)) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            GSSManager manager = GSSManager.getInstance();
            GSSName serviceName = manager.createName(servicePrincipal, GSSName.NT_USER_NAME);
            GSSContext context = manager.createContext(serviceName, KRB5_OID, null, GSSContext.DEFAULT_LIFETIME);
            context.requestMutualAuth(true);
            context.requestConf(true);
            context.requestInteg(true);

            byte[] token = new byte[0];
            while (!context.isEstablished()) {
                token = context.initSecContext(token, 0, token.length);
                if (token != null) {
                    writeToken(out, token);
                }
                if (!context.isEstablished()) {
                    token = readToken(in);
                }
            }

            byte[] message = MESSAGE.getBytes(StandardCharsets.UTF_8);
            MessageProp prop = new MessageProp(0, true);
            byte[] wrapped = context.wrap(message, 0, message.length, prop);
            writeToken(out, wrapped);

            byte[] mic = readToken(in);
            context.verifyMIC(mic, 0, mic.length, message, 0, message.length, prop);
            context.dispose();
        }
    }

    private static Subject loginWithTicketCache(String principal, File ticketCache) throws Exception {
        return login(principal, jaasOptions(principal, null, ticketCache, true));
    }

    private static Subject loginWithKeytab(String principal, File keytab, boolean initiator) throws Exception {
        return login(principal, jaasOptions(principal, keytab, null, initiator));
    }

    private static Subject login(String principal, Map<String, String> options) throws Exception {
        Subject subject = new Subject(false,
            new HashSet<Principal>(Collections.<Principal>singleton(new KerberosPrincipal(principal))),
            new HashSet<Object>(),
            new HashSet<Object>());
        Configuration configuration = new StaticJaasConfiguration(options);
        LoginContext loginContext = new LoginContext("KerbyTest", subject, null, configuration);
        loginContext.login();
        return loginContext.getSubject();
    }

    private static Map<String, String> jaasOptions(String principal, File keytab,
                                                   File ticketCache, boolean initiator) {
        Map<String, String> options = new HashMap<>();
        options.put("principal", principal);
        options.put("refreshKrb5Config", "true");
        options.put("debug", "false");
        options.put("isInitiator", Boolean.toString(initiator));
        if (keytab != null) {
            options.put("keyTab", keytab.getAbsolutePath());
            options.put("useKeyTab", "true");
            options.put("storeKey", "true");
            options.put("doNotPrompt", "true");
        }
        if (ticketCache != null) {
            options.put("ticketCache", ticketCache.getAbsolutePath());
            options.put("useTicketCache", "true");
            options.put("renewTGT", "false");
            options.put("doNotPrompt", "true");
            options.put("storeKey", "false");
        }
        return options;
    }

    private static byte[] readToken(DataInputStream in) throws Exception {
        int length = in.readInt();
        byte[] token = new byte[length];
        in.readFully(token);
        return token;
    }

    private static void writeToken(DataOutputStream out, byte[] token) throws Exception {
        out.writeInt(token.length);
        out.write(token);
        out.flush();
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    private static Oid oid(String value) {
        try {
            return new Oid(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid OID " + value, e);
        }
    }

    private static final class StaticJaasConfiguration extends Configuration {
        private final Map<String, String> options;

        private StaticJaasConfiguration(Map<String, String> options) {
            this.options = options;
        }

        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
            return new AppConfigurationEntry[] {
                new AppConfigurationEntry("com.sun.security.auth.module.Krb5LoginModule",
                    AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, options)
            };
        }
    }
}
