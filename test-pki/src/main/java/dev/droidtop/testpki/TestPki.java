package dev.droidtop.testpki;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Throwaway certificates and JWS signatures for tests: a "Droidtop Core" stand-in, leaves with the droidtop
 * extensions, and signatures in the format docs/SPEC.md 12a describes. Every key is generated inside the test run.
 */
public final class TestPki {
    private static final AtomicLong SERIAL = new AtomicLong(1);

    /** The droidtop OID arc and its purposes, as the verifier names them. */
    public static final String OID_ARC = "2.25.230248497294207031301727532441854392946";
    public static final String PLUGIN = OID_ARC + ".1";
    public static final String CATALOG = OID_ARC + ".2";
    public static final String REVOCATIONS = OID_ARC + ".3";

    private TestPki() {}

    public static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair ec() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A certificate to build: by default a leaf for plugin signing, valid from a year ago for ten years. */
    public static final class Cert {
        private final String cn;
        private final PublicKey key;
        private X509Certificate issuer;
        private PrivateKey issuerKey;
        private String issuerCn;
        private boolean ca;
        private int pathLen = -1;
        private int keyUsage = KeyUsage.digitalSignature;
        private boolean noKeyUsage;
        private final List<String> ekus = new ArrayList<>();
        private final List<String> uris = new ArrayList<>();
        private Date notBefore = new Date(System.currentTimeMillis() - 365L * 86_400_000L);
        private Date notAfter = new Date(System.currentTimeMillis() + 3650L * 86_400_000L);

        private Cert(String cn, PublicKey key) {
            this.cn = cn;
            this.key = key;
        }

        public Cert issuedBy(X509Certificate issuer, PrivateKey issuerKey) {
            this.issuer = issuer;
            this.issuerKey = issuerKey;
            return this;
        }

        /** Signed by [issuerKey] under the issuer name [issuerCn], with no issuer certificate (a key someone trusts). */
        public Cert issuedByKey(String issuerCn, PrivateKey issuerKey) {
            this.issuerCn = issuerCn;
            this.issuerKey = issuerKey;
            return this;
        }

        public Cert selfSigned(PrivateKey own) {
            this.issuerKey = own;
            return this;
        }

        /** A CA certificate (keyCertSign, CA:TRUE); [pathLen] -1 for no limit. */
        public Cert ca(int pathLen) {
            this.ca = true;
            this.pathLen = pathLen;
            this.keyUsage = KeyUsage.keyCertSign | KeyUsage.cRLSign;
            return this;
        }

        public Cert keyUsage(int flags) {
            this.keyUsage = flags;
            return this;
        }

        public Cert noKeyUsage() {
            this.noKeyUsage = true;
            return this;
        }

        public Cert eku(String... oids) {
            ekus.addAll(Arrays.asList(oids));
            return this;
        }

        public Cert uri(String... values) {
            uris.addAll(Arrays.asList(values));
            return this;
        }

        public Cert valid(Date from, Date to) {
            this.notBefore = from;
            this.notAfter = to;
            return this;
        }

        public X509Certificate build() {
            try {
                X500Name subject = new X500Name("CN=" + cn);
                X500Name issuerName = issuer != null
                    ? X500Name.getInstance(issuer.getSubjectX500Principal().getEncoded())
                    : issuerCn != null ? new X500Name("CN=" + issuerCn) : subject;
                JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    issuerName, BigInteger.valueOf(SERIAL.getAndIncrement()), notBefore, notAfter, subject, key);
                builder.addExtension(Extension.basicConstraints, true, ca ? (pathLen >= 0 ? new BasicConstraints(pathLen) : new BasicConstraints(true)) : new BasicConstraints(false));
                if (!noKeyUsage) builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
                if (!ekus.isEmpty()) {
                    KeyPurposeId[] ids = ekus.stream().map(oid -> KeyPurposeId.getInstance(new ASN1ObjectIdentifier(oid))).toArray(KeyPurposeId[]::new);
                    builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(ids));
                }
                if (!uris.isEmpty()) {
                    GeneralName[] names = uris.stream().map(u -> new GeneralName(GeneralName.uniformResourceIdentifier, u)).toArray(GeneralName[]::new);
                    builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names));
                }
                String algorithm = issuerKey instanceof ECPrivateKey ? "SHA256withECDSA" : "SHA256withRSA";
                return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder(algorithm).build(issuerKey)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public static Cert cert(String cn, PublicKey key) {
        return new Cert(cn, key);
    }

    /** A Droidtop Core stand-in: a self-signed RSA CA certificate. */
    public static X509Certificate core(KeyPair pair) {
        return cert("Droidtop Core (test)", pair.getPublic()).ca(0).selfSigned(pair.getPrivate()).build();
    }

    /** A plugin-signing leaf for [origin], issued by [issuer]. */
    public static X509Certificate pluginLeaf(KeyPair leaf, String origin, X509Certificate issuer, PrivateKey issuerKey) {
        return cert("plugin " + origin, leaf.getPublic()).eku(PLUGIN).uri("urn:droidtop:origin:" + origin).issuedBy(issuer, issuerKey).build();
    }

    /** A detached JWS over [content] of type [typ], signed by [key] with the chain [x5c] (leaf first). */
    public static String detached(String typ, List<X509Certificate> x5c, PrivateKey key, byte[] content) {
        String header = header(typ, x5c, key);
        String signature = sign(key, (header + "." + url(content)).getBytes(StandardCharsets.US_ASCII));
        return header + ".." + signature;
    }

    /** A JWS with an attached [payload] of type [typ]. */
    public static String attached(String typ, List<X509Certificate> x5c, PrivateKey key, byte[] payload) {
        String header = header(typ, x5c, key);
        String body = url(payload);
        return header + "." + body + "." + sign(key, (header + "." + body).getBytes(StandardCharsets.US_ASCII));
    }

    /** A JWS header string for hand-built cases. */
    public static String header(String typ, List<X509Certificate> x5c, PrivateKey key) {
        try {
            StringBuilder chain = new StringBuilder();
            for (X509Certificate cert : x5c) {
                if (chain.length() > 0) chain.append(',');
                chain.append('"').append(Base64.getEncoder().encodeToString(cert.getEncoded())).append('"');
            }
            String alg = key instanceof ECPrivateKey ? "ES256" : "RS256";
            String json = "{\"alg\":\"" + alg + "\",\"typ\":\"" + typ + "\",\"x5c\":[" + chain + "]}";
            return url(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JWS signature value of [input] by [key]: RS256, or ES256 as raw R || S. */
    public static String sign(PrivateKey key, byte[] input) {
        try {
            boolean ec = key instanceof ECPrivateKey;
            Signature signer = Signature.getInstance(ec ? "SHA256withECDSA" : "SHA256withRSA");
            signer.initSign(key);
            signer.update(input);
            byte[] signature = signer.sign();
            if (ec) {
                ASN1Sequence sequence = ASN1Sequence.getInstance(signature);
                byte[] raw = new byte[64];
                copyFixed(ASN1Integer.getInstance(sequence.getObjectAt(0)).getPositiveValue(), raw, 0);
                copyFixed(ASN1Integer.getInstance(sequence.getObjectAt(1)).getPositiveValue(), raw, 32);
                signature = raw;
            }
            return url(signature);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copyFixed(BigInteger value, byte[] into, int offset) {
        byte[] bytes = value.toByteArray();
        int start = bytes.length > 32 ? bytes.length - 32 : 0;
        int length = bytes.length - start;
        System.arraycopy(bytes, start, into, offset + 32 - length, length);
    }

    public static String url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String pem(X509Certificate cert) {
        try {
            return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(cert.getEncoded()) + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
