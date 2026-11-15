package com.ccs.sprig.core.http3;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.crypto.KeyAgreement;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import java.io.IOException;
import tech.kwik.agent15.TlsConstants;
import tech.kwik.agent15.env.PlatformMapping;

/** Public JCA-only portability choices; older Android uses P-256 instead of unavailable XDH APIs. */
final class Http3Crypto {
    private static volatile boolean checked;
    private static String unavailable;
    private static List<TlsConstants.NamedGroup> availableGroups;
    private Http3Crypto(){}
    static synchronized void initialize() {
        if(System.getProperty("java.vendor","").contains("Android"))
            PlatformMapping.usePlatformMapping(PlatformMapping.Platform.Android);
    }
    static synchronized String availabilityError() {
        if(!checked) {
            initialize();
            try {
                KeyPairGenerator ec=KeyPairGenerator.getInstance("EC");ec.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));KeyAgreement.getInstance("ECDH");
                Cipher.getInstance("AES/GCM/NoPadding");Cipher.getInstance("AES/ECB/NoPadding");
                Mac.getInstance("HmacSHA256");Mac.getInstance("HmacSHA384");
                java.security.Signature.getInstance(PlatformMapping.algorithmMapping().get("RSASSA-PSS",256));
                java.security.Signature.getInstance("SHA256withECDSA");
                javax.net.ssl.TrustManagerFactory.getInstance("PKIX");
            }catch(java.security.GeneralSecurityException|LinkageError error){unavailable="HTTP/3 cryptography is unavailable: "+error.getClass().getSimpleName();}
            checked=true;
        }
        return unavailable;
    }
    static void requireAvailable() throws IOException {String error=availabilityError();if(error!=null)throw new IOException(error);}
    static synchronized List<TlsConstants.NamedGroup> groups() {
        if(availableGroups!=null)return availableGroups;
        List<TlsConstants.NamedGroup> result=new ArrayList<>();
        for(TlsConstants.NamedGroup group:new TlsConstants.NamedGroup[]{TlsConstants.NamedGroup.secp256r1,TlsConstants.NamedGroup.secp384r1,TlsConstants.NamedGroup.secp521r1}) {
            try {KeyPairGenerator ec=KeyPairGenerator.getInstance("EC");ec.initialize(new java.security.spec.ECGenParameterSpec(group.name()));result.add(group);}
            catch(java.security.GeneralSecurityException unsupported){}
        }
        try {
            // Checking a public class by name does not link old Android runtimes to the newer XEC interfaces.
            Class<?> publicKey=Class.forName("java.security.interfaces.XECPublicKey");
            Class<?> namedSpec=Class.forName("java.security.spec.NamedParameterSpec");
            java.security.spec.AlgorithmParameterSpec spec=(java.security.spec.AlgorithmParameterSpec)namedSpec.getConstructor(String.class).newInstance("X25519");
            KeyPairGenerator xdh=KeyPairGenerator.getInstance("XDH");xdh.initialize(spec);
            KeyAgreement.getInstance("XDH");java.security.KeyFactory.getInstance("XDH");
            if(publicKey.isInstance(xdh.generateKeyPair().getPublic()))result.add(TlsConstants.NamedGroup.x25519);
        } catch(ReflectiveOperationException|java.security.GeneralSecurityException|LinkageError unavailable) { }
        availableGroups=Collections.unmodifiableList(result);return availableGroups;
    }
}
