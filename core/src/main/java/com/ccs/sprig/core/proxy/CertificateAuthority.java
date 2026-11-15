package com.ccs.sprig.core.proxy;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.IPAddress;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.IDN;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Device-local CA. Store the bundle in Android's non-backed-up private app directory. */
public final class CertificateAuthority {
    private static final long DAY = 86_400_000L;
    private static final int MAGIC = 0x43415031;
    private final Provider provider = new BouncyCastleProvider();
    private final SecureRandom random = new SecureRandom();
    private final X509Certificate root;
    private final PrivateKey rootKey;
    private KeyPair leafKeys;
    private final Map<String, SSLContext> contexts = new LinkedHashMap<String, SSLContext>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SSLContext> entry) { return size() > 64; }
    };
    private final Map<String, KeyStore> keyStores = new LinkedHashMap<String, KeyStore>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, KeyStore> entry) { return size() > 64; }
    };

    public CertificateAuthority(File directory) throws IOException {
        try {
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create CA directory");
            File bundle = new File(directory, "authority-v1.bin");
            if (bundle.exists()) {
                try (DataInputStream input = new DataInputStream(new FileInputStream(bundle))) {
                    if (input.readInt() != MAGIC) throw new IOException("Invalid CA bundle; remove it explicitly to rotate the CA");
                    byte[] privateBytes = readField(input, 16 * 1024);
                    byte[] certificateBytes = readField(input, 16 * 1024);
                    if (input.read() != -1) throw new IOException("Invalid CA bundle length");
                    rootKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
                    root = (X509Certificate) CertificateFactory.getInstance("X.509")
                            .generateCertificate(new java.io.ByteArrayInputStream(certificateBytes));
                    root.verify(root.getPublicKey());
                    root.checkValidity();
                    byte[] challenge = new byte[32];
                    random.nextBytes(challenge);
                    Signature signature = Signature.getInstance("SHA256withRSA");
                    signature.initSign(rootKey, random);
                    signature.update(challenge);
                    byte[] proof = signature.sign();
                    signature.initVerify(root.getPublicKey());
                    signature.update(challenge);
                    if (!signature.verify(proof)) throw new IOException("CA private key does not match certificate");
                }
            } else {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(3072, random);
                KeyPair keys = generator.generateKeyPair();
                rootKey = keys.getPrivate();
                X500Name name = new X500NameBuilder(BCStyle.INSTANCE)
                        .addRDN(BCStyle.O, "Sprig Local Inspection")
                        .addRDN(BCStyle.CN, "Sprig Device CA").build();
                long now = System.currentTimeMillis();
                JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, serial(),
                        new Date(now - DAY), new Date(now + 3650L * DAY), name, keys.getPublic());
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
                builder.addExtension(Extension.subjectKeyIdentifier, false,
                        new JcaX509ExtensionUtils().createSubjectKeyIdentifier(keys.getPublic()));
                root = new JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(
                        new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(rootKey)));
                File temporary = new File(directory, "authority-v1.tmp");
                try (FileOutputStream file = new FileOutputStream(temporary);
                     DataOutputStream output = new DataOutputStream(file)) {
                    makePrivate(temporary);
                    output.writeInt(MAGIC);
                    writeField(output, rootKey.getEncoded());
                    writeField(output, root.getEncoded());
                    output.flush();
                    file.getFD().sync();
                }
                if (!temporary.renameTo(bundle)) throw new IOException("Cannot commit local CA bundle");
                makePrivate(bundle);
            }
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Cannot initialize local certificate authority", e);
        }
    }

    public byte[] certificateDer() {
        try { return root.getEncoded(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public String fingerprint() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(root.getEncoded());
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) {
                if (hex.length() > 0) hex.append(':');
                hex.append(String.format(Locale.ROOT, "%02X", value & 255));
            }
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public synchronized SSLContext serverContext(String host) throws Exception {
        host = normalizeHost(host);
        SSLContext cached = contexts.get(host);
        if (cached != null) return cached;
        KeyStore store = serverKeyStore(host);
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, random);
        contexts.put(host, context);
        return context;
    }

    /** Device-private leaf identity for a local QUIC endpoint; alias "leaf", empty key password. */
    public synchronized KeyStore serverKeyStore(String host) throws Exception {
        host = normalizeHost(host);
        KeyStore cached = keyStores.get(host);
        if (cached != null) return cached;
        if (leafKeys == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, random);
            leafKeys = generator.generateKeyPair();
        }
        long now = System.currentTimeMillis();
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, host).build();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(root, serial(),
                new Date(now - DAY), new Date(Math.min(now + 90L * DAY, root.getNotAfter().getTime())),
                subject, leafKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(isIp(host) ? GeneralName.iPAddress : GeneralName.dNSName, host)));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
                new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(root));
        X509Certificate leaf = new JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(rootKey)));
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        char[] password = new char[0];
        store.setKeyEntry("leaf", leafKeys.getPrivate(), password, new java.security.cert.Certificate[]{leaf, root});
        keyStores.put(host, store);
        return store;
    }

    public static String normalizeHost(String host) throws IOException {
        if (host == null || host.isEmpty() || host.length() > 253) throw new IOException("Invalid TLS hostname");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (isIp(host)) {
            if (!IPAddress.isValid(host)) throw new IOException("Invalid IP address");
            return host.toLowerCase(Locale.ROOT);
        }
        try {
            String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (ascii.endsWith(".")) ascii = ascii.substring(0, ascii.length() - 1);
            if (ascii.isEmpty() || ascii.contains("..")) throw new IOException("Invalid TLS hostname");
            return ascii;
        } catch (IllegalArgumentException e) { throw new IOException("Invalid TLS hostname", e); }
    }

    static boolean isIp(String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    }

    private BigInteger serial() { return new BigInteger(159, random).add(BigInteger.ONE); }
    private static void writeField(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }
    private static byte[] readField(DataInputStream input, int maximum) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > maximum) throw new IOException("Invalid CA field size");
        byte[] result = new byte[length];
        input.readFully(result);
        return result;
    }
    private static void makePrivate(File file) throws IOException {
        if (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setReadable(true, true) || !file.setWritable(true, true))
            throw new IOException("Cannot restrict CA private key permissions");
    }
}
