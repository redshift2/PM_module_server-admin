package serveradmin

import crew.User

/** A verified issuer+subject maps to exactly one existing local account. */
class OidcIdentity {
    User user
    OidcProvider provider
    String issuer
    String subject
    Date linkedAt = new Date()
    Date lastLoginAt

    static constraints = {
        issuer blank: false, maxSize: 500
        subject blank: false, maxSize: 255, unique: 'issuer'
        lastLoginAt nullable: true
    }
    static mapping = { table 'server_admin_oidc_identity' }
}
