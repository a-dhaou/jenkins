/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package jenkins.slaves;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Computer;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import jenkins.AgentProtocol;
import jenkins.model.identity.InstanceIdentityProvider;
import org.jenkinsci.Symbol;
import org.jenkinsci.remoting.engine.JnlpConnectionState;
import org.jenkinsci.remoting.engine.JnlpProtocol4Handler;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.cert.PublicKeyMatchingX509ExtendedTrustManager;

/**
 * Master-side implementation for JNLP4-connect protocol.
 *
 * <p>@see {@link org.jenkinsci.remoting.engine.JnlpProtocol4Handler} for more details.
 *
 * @since 2.27 available as experimental protocol
 * @since 2.41 enabled by default
 */
@Extension
@Symbol("jnlp4")
public class JnlpSlaveAgentProtocol4 extends AgentProtocol {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(JnlpSlaveAgentProtocol4.class.getName());

    /**
     * Our keystore.
     */
    private final KeyStore keyStore;
    /**
     * Our trust manager.
     */
    private final TrustManager trustManager;

    /**
     * The provider of our {@link IOHub}
     */
    private IOHubProvider hub;

    /**
     * Our handler.
     */
    private JnlpProtocol4Handler handler;
    /**
     * Our SSL context.
     */
    private SSLContext sslContext;

    /**
     * Constructor.
     *
     * @throws KeyStoreException      if things go wrong.
     * @throws KeyManagementException if things go wrong.
     * @throws IOException            if things go wrong.
     */
    public JnlpSlaveAgentProtocol4() throws KeyStoreException, KeyManagementException, IOException {
        // prepare our local identity and certificate
        X509Certificate identityCertificate = InstanceIdentityProvider.RSA.getCertificate();
        if (identityCertificate == null) {
            throw new KeyStoreException("JENKINS-41987: no X509Certificate found; perhaps instance-identity plugin is not installed");
        }
        RSAPrivateKey privateKey = InstanceIdentityProvider.RSA.getPrivateKey();
        if (privateKey == null) {
            throw new KeyStoreException("JENKINS-41987: no RSAPrivateKey found; perhaps instance-identity plugin is not installed");
        }

        // prepare our keyStore so we can provide our authentication
        keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        char[] password = constructPassword();
        try {
            keyStore.load(null, password);
        } catch (IOException e) {
            throw new IllegalStateException("Specification says this should not happen as we are not doing I/O", e);
        } catch (NoSuchAlgorithmException | CertificateException e) {
            throw new IllegalStateException("Specification says this should not happen as we are not loading keys", e);
        }
        keyStore.setKeyEntry("jenkins", privateKey, password,
                new X509Certificate[]{identityCertificate});

        // prepare our keyManagers to provide to the SSLContext
        KeyManagerFactory kmf;
        try {
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Specification says the default algorithm should exist", e);
        } catch (UnrecoverableKeyException e) {
            throw new IllegalStateException("The key was just inserted with this exact password", e);
        }

        // prepare our trustManagers
        trustManager = new PublicKeyMatchingX509ExtendedTrustManager(false, true);
        TrustManager[] trustManagers = {trustManager};

        // prepare our SSLContext
        try {
            sslContext = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime specification requires support for TLS algorithm", e);
        }
        sslContext.init(kmf.getKeyManagers(), trustManagers, null);
    }

    private char[] constructPassword() {
        return "password".toCharArray();
    }

    /**
     * Inject the {@link IOHubProvider}
     *
     * @param hub the hub provider.
     */
    @Inject
    public void setHub(IOHubProvider hub) {
        this.hub = hub;
        handler = new JnlpProtocol4Handler(JnlpAgentReceiver.DATABASE, Computer.threadPoolForRemoting, hub.getHub(),
                sslContext, false, true);
    }

    @Override
    public boolean isOptIn() {
        return false;
    }

    @Override
    public String getDisplayName() {
        return Messages.JnlpSlaveAgentProtocol4_displayName();
    }

    @Override
    public String getName() {
        return handler.getName();
    }

    @Override
    public void handle(Socket socket) throws IOException, InterruptedException {
        try {
            X509Certificate certificate = (X509Certificate) keyStore.getCertificate("jenkins");
            if (certificate == null
                    || certificate.getNotAfter().getTime() < System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)) {
                LOGGER.log(Level.INFO, "Updating {0} TLS certificate to retain validity", getName());
                X509Certificate identityCertificate = InstanceIdentityProvider.RSA.getCertificate();
                RSAPrivateKey privateKey = InstanceIdentityProvider.RSA.getPrivateKey();
                char[] password = constructPassword();
                keyStore.setKeyEntry("jenkins", privateKey, password, new X509Certificate[]{identityCertificate});
            }
        } catch (KeyStoreException e) {
            LOGGER.log(Level.FINEST, "Ignored", e);
        }
        handler.handle(socket,
                Collections.singletonMap(JnlpConnectionState.COOKIE_KEY, JnlpAgentReceiver.generateCookie()),
                ExtensionList.lookup(JnlpAgentReceiver.class));
    }

}
