import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.*;

/** Encrypts the fixture payloads into CONCHBAK containers (docs/backup-format.md §1). */
public class GenFixtures {
    static final int ITERATIONS = 600_000;

    static byte[] encrypt(String json, String pass) throws Exception {
        SecureRandom r = new SecureRandom();
        byte[] salt = new byte[16]; r.nextBytes(salt);
        byte[] nonce = new byte[12]; r.nextBytes(nonce);
        byte[] header = ByteBuffer.allocate(44)
            .put("CONCHBAK".getBytes(StandardCharsets.US_ASCII))
            .putShort((short) 1)   // format version
            .put((byte) 1)         // kdf: PBKDF2-HMAC-SHA256
            .put((byte) 1)         // cipher: AES-256-GCM
            .putInt(ITERATIONS)
            .put(salt)
            .put(nonce)
            .array();
        PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, ITERATIONS, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(header);
        byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[header.length + ct.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(ct, 0, out, header.length, ct.length);
        return out;
    }

    public static void main(String[] a) throws Exception {
        String pass = "conch-parity-2026";
        Path outDir = Paths.get(a[0]);
        Files.createDirectories(outDir);
        for (String name : new String[]{"full-v1", "sparse-v1"}) {
            String json = new String(Files.readAllBytes(outDir.resolve(name + ".json")), StandardCharsets.UTF_8).trim();
            Files.write(outDir.resolve(name + ".conchbak"), encrypt(json, pass));
            System.out.println(name + ": " + json.length() + " bytes plaintext");
        }
    }
}
