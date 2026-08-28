import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.*;

public class GenFixtures {
    static byte[] encrypt(String json, String pass) throws Exception {
        SecureRandom r = new SecureRandom();
        byte[] salt = new byte[16]; r.nextBytes(salt);
        byte[] iv = new byte[12]; r.nextBytes(iv);
        PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, 600_000, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[8 + 16 + 12 + ct.length];
        System.arraycopy("TILDBAK1".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 8);
        System.arraycopy(salt, 0, out, 8, 16);
        System.arraycopy(iv, 0, out, 24, 12);
        System.arraycopy(ct, 0, out, 36, ct.length);
        return out;
    }
    public static void main(String[] a) throws Exception {
        String pass = "conch-parity-2026";
        Path outDir = Paths.get(a[0]);
        Files.createDirectories(outDir);
        for (String name : new String[]{"android-v1", "ios-v1"}) {
            String json = new String(Files.readAllBytes(outDir.resolve(name + ".json")), StandardCharsets.UTF_8).trim();
            Files.write(outDir.resolve(name + ".til"), encrypt(json, pass));
            System.out.println(name + ": " + json.length() + " bytes plaintext");
        }
    }
}
