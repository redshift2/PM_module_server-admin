package serveradmin

import grails.gorm.transactions.Transactional
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.util.Base64

@Transactional
class OidcProviderService {
    private static byte[] encryptionKey() {
        String value = System.getenv('PM_OIDC_SECRET_KEY')
        if (!value) throw new IllegalStateException('Set PM_OIDC_SECRET_KEY to a base64-encoded 32-byte key before storing OIDC client secrets.')
        byte[] key = Base64.decoder.decode(value)
        if (key.length != 32) throw new IllegalStateException('PM_OIDC_SECRET_KEY must decode to exactly 32 bytes.')
        return key
    }
    private static String encrypt(String plaintext) {
        byte[] iv = new byte[12]
        new SecureRandom().nextBytes(iv)
        Cipher cipher = Cipher.getInstance('AES/GCM/NoPadding')
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey(), 'AES'), new GCMParameterSpec(128, iv))
        return Base64.encoder.encodeToString(iv) + ':' + Base64.encoder.encodeToString(cipher.doFinal(plaintext.getBytes('UTF-8')))
    }
    String decryptClientSecret(OidcProvider provider) {
        if (!provider?.encryptedClientSecret) return null
        String[] parts=provider.encryptedClientSecret.split(':',-1)
        if (parts.length!=2) throw new SecurityException('Invalid encrypted client secret')
        byte[] iv=Base64.decoder.decode(parts[0])
        if (iv.length!=12) throw new SecurityException('Invalid client secret IV')
        Cipher cipher=Cipher.getInstance('AES/GCM/NoPadding')
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey(),'AES'),new GCMParameterSpec(128,iv))
        new String(cipher.doFinal(Base64.decoder.decode(parts[1])),'UTF-8')
    }
    OidcProvider saveProvider(Map fields) {
        OidcProvider provider = fields.id ? OidcProvider.get(fields.id as Long) : new OidcProvider()
        if (!provider) throw new IllegalArgumentException('Provider not found')
        String issuer = (fields.issuer ?: '').toString().trim().replaceAll('/+$','')
        URI uri = new URI(issuer)
        if (uri.scheme != 'https' || !uri.host || uri.userInfo || uri.fragment) {
            throw new IllegalArgumentException('Issuer must be an HTTPS URL without credentials or fragment.')
        }
        provider.name = fields.name?.toString()?.trim()
        provider.issuer = issuer
        provider.clientId = fields.clientId?.toString()?.trim()
        provider.scopes = fields.scopes?.toString()?.trim() ?: 'openid profile email'
        provider.enabled = fields.enabled?.toString() == 'true'
        provider.autoCreateUsers = false // Account provisioning is intentionally not enabled.
        if (fields.clientSecret?.toString()?.trim()) provider.encryptedClientSecret = encrypt(fields.clientSecret.toString().trim())
        provider.save(failOnError:true, flush:true)
        return provider
    }
}
