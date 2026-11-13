package serveradmin

/** OIDC provider configuration. Secret is encrypted at rest, never rendered back to a form. */
class OidcProvider {
    String name
    String issuer
    String clientId
    String encryptedClientSecret
    String scopes = 'openid profile email'
    boolean enabled = false
    boolean autoCreateUsers = false
    Date dateCreated
    Date lastUpdated

    static constraints = {
        name blank: false, maxSize: 100, unique: true
        issuer blank: false, maxSize: 500, unique: true
        clientId blank: false, maxSize: 255
        encryptedClientSecret nullable: true, maxSize: 2048
        scopes blank: false, maxSize: 255
    }
    static mapping = { table 'server_admin_oidc_provider' }
}
