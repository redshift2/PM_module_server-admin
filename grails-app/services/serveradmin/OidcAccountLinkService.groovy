package serveradmin
import crew.User
import grails.gorm.transactions.Transactional

@Transactional
class OidcAccountLinkService {
    void link(OidcProvider provider,String subject,User user) {
        if (!provider?.enabled || !subject || !user) throw new SecurityException('Invalid account link')
        OidcIdentity existing=OidcIdentity.findByIssuerAndSubject(provider.issuer,subject)
        if (existing && existing.user?.id!=user.id) throw new SecurityException('External identity already linked to another account')
        if (!existing) new OidcIdentity(user:user,provider:provider,issuer:provider.issuer,subject:subject).save(failOnError:true,flush:true)
    }
    void markLogin(Long id) { OidcIdentity link=OidcIdentity.get(id); if(link) {link.lastLoginAt=new Date();link.save(failOnError:true)} }
    void unlink(Long id,User user) {
        OidcIdentity link=OidcIdentity.get(id)
        if (!link || link.user?.id!=user?.id) throw new SecurityException('Identity not owned by current user')
        link.delete(flush:true)
    }
}
