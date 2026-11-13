package serveradmin

import crew.User
import grails.plugin.springsecurity.annotation.Secured
import groovy.json.JsonSlurper
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.ECKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.net.HttpURLConnection
import java.net.URLEncoder

/** OIDC Authorization Code + PKCE; external subject maps only to explicitly linked local users. */
@Secured(['permitAll'])
class OidcLoginController {
    def springSecurityService
    def userDetailsService
    serveradmin.OidcProviderService oidcProviderService
    private static final SecureRandom RANDOM = new SecureRandom()
    private static String randomUrl(int size=32) { byte[] b=new byte[size]; RANDOM.nextBytes(b); Base64.urlEncoder.withoutPadding().encodeToString(b) }
    private static String enc(Object s) { URLEncoder.encode(s.toString(), 'UTF-8') }
    private static String query(Map args) { args.collect { k,v -> enc(k)+'='+enc(v) }.join('&') }
    private static Map jsonUrl(String url) {
        URI u = new URI(url)
        if (u.scheme != 'https' || !u.host || u.userInfo || u.fragment) throw new SecurityException('OIDC endpoint must be HTTPS')
        HttpURLConnection c=(HttpURLConnection)u.toURL().openConnection()
        c.connectTimeout=5000; c.readTimeout=8000; c.instanceFollowRedirects=false
        try {
            if (c.responseCode != 200) throw new SecurityException('OIDC endpoint returned '+c.responseCode)
            new JsonSlurper().parse(c.inputStream) as Map
        } finally { c.disconnect() }
    }
    private static Map discovery(OidcProvider p) {
        Map d=jsonUrl(p.issuer+'/.well-known/openid-configuration')
        if (d.issuer != p.issuer) throw new SecurityException('Issuer mismatch')
        URI base=new URI(p.issuer)
        for (String k : ['authorization_endpoint','token_endpoint','jwks_uri']) {
            URI u=new URI(d[k]?.toString() ?: '')
            if (u.scheme!='https' || !u.host || u.userInfo || u.fragment || !u.host.equalsIgnoreCase(base.host)) throw new SecurityException('Invalid provider '+k)
        }
        d
    }
    private static String callbackUrl(def request) {
        // Use the public HTTPS origin configured at the reverse proxy; never trust an arbitrary Host header.
        String base=System.getenv('PM_OIDC_PUBLIC_URL')
        if (!base) throw new IllegalStateException('Set PM_OIDC_PUBLIC_URL to the public HTTPS server origin (for example https://pdm.example.org)')
        URI u=new URI(base)
        if (u.scheme!='https' || !u.host || u.userInfo || u.query || u.fragment) throw new IllegalStateException('Invalid PM_OIDC_PUBLIC_URL')
        base.replaceAll('/+$','')+request.contextPath+'/oidcLogin/callback'
    }
    def start() {
        OidcProvider p=OidcProvider.get(params.long('id'))
        if (!p?.enabled) { response.sendError(404); return }
        boolean linking=params.link=='true'
        if (linking && !springSecurityService.isLoggedIn()) { response.sendError(403); return }
        try {
            Map d=discovery(p)
            String verifier=randomUrl(48), state=randomUrl(), nonce=randomUrl()
            String challenge=Base64.urlEncoder.withoutPadding().encodeToString(MessageDigest.getInstance('SHA-256').digest(verifier.getBytes('US-ASCII')))
            session.oidcFlow=[providerId:p.id, verifier:verifier, state:state, nonce:nonce,
                linking:linking, localUserId:linking ? springSecurityService.currentUser?.id : null,
                created:System.currentTimeMillis()]
            String url=d.authorization_endpoint+'?'+query([response_type:'code', client_id:p.clientId,
                redirect_uri:callbackUrl(request),scope:p.scopes,state:state,nonce:nonce,
                code_challenge:challenge,code_challenge_method:'S256'])
            response.sendRedirect(url)
        } catch (Exception e) { log.warn('OIDC start failed',e); flash.error='Unable to start OIDC login: '+e.message; response.sendRedirect(request.contextPath + '/login/auth') }
    }
    def callback() {
        Map flow=session.oidcFlow as Map
        session.removeAttribute('oidcFlow') // one-time flow, including on failure
        if (!flow || System.currentTimeMillis()-(flow.created as Long)>600000L ||
            !params.state || !MessageDigest.isEqual(params.state.toString().getBytes('UTF-8'),flow.state.toString().getBytes('UTF-8')) ||
            !params.code || params.error) { response.sendError(400,'Invalid or expired OIDC login'); return }
        OidcProvider p=OidcProvider.get(flow.providerId as Long)
        if (!p?.enabled) { response.sendError(403); return }
        try {
            Map d=discovery(p)
            String secret=oidcProviderService.decryptClientSecret(p)
            Map form=[grant_type:'authorization_code',code:params.code,redirect_uri:callbackUrl(request),
                      client_id:p.clientId,code_verifier:flow.verifier]
            if (secret) form.client_secret=secret
            HttpURLConnection c=(HttpURLConnection)new URI(d.token_endpoint.toString()).toURL().openConnection()
            c.requestMethod='POST';c.doOutput=true;c.connectTimeout=5000;c.readTimeout=8000;c.instanceFollowRedirects=false
            c.setRequestProperty('Content-Type','application/x-www-form-urlencoded')
            c.outputStream.withCloseable { it.write(query(form).getBytes('UTF-8')) }
            Map tokens
            try { if(c.responseCode!=200) throw new SecurityException('OIDC token exchange failed');tokens=new JsonSlurper().parse(c.inputStream) as Map }
            finally { c.disconnect() }
            SignedJWT jwt=SignedJWT.parse(tokens.id_token?.toString())
            if (!jwt.header.keyID || jwt.header.algorithm==JWSAlgorithm.NONE) throw new SecurityException('Invalid ID token algorithm')
            JWKSet keys=JWKSet.load(new URI(d.jwks_uri.toString()).toURL())
            def key=keys.keys.find { it.keyID==jwt.header.keyID && (it.keyUse==null || it.keyUse.identifier=='sig') }
            boolean valid=(key instanceof RSAKey && jwt.header.algorithm.name.startsWith('RS') && jwt.verify(new RSASSAVerifier((RSAKey)key))) ||
                          (key instanceof ECKey && jwt.header.algorithm.name.startsWith('ES') && jwt.verify(new ECDSAVerifier((ECKey)key)))
            if (!valid) throw new SecurityException('ID token signature invalid')
            def claims=jwt.JWTClaimsSet
            long now=System.currentTimeMillis()
            if (claims.issuer!=p.issuer || !claims.audience.contains(p.clientId) ||
                (claims.audience.size()>1 && claims.getStringClaim('azp')!=p.clientId) ||
                claims.expirationTime==null || claims.expirationTime.time<=now-60000L ||
                claims.issueTime==null || claims.issueTime.time>now+60000L ||
                claims.getStringClaim('nonce')!=flow.nonce || !claims.subject) throw new SecurityException('ID token claims invalid')
            String subject=claims.subject
            if (flow.linking) {
                if (!springSecurityService.isLoggedIn() || springSecurityService.currentUser?.id!=flow.localUserId) throw new SecurityException('Local session changed during linking')
                oidcAccountLinkService.link(p, subject, springSecurityService.currentUser as User)
                flash.message='External identity connected to your existing account.'
                response.sendRedirect(request.contextPath + '/oidcLogin/account');return
            }
            OidcIdentity identity=OidcIdentity.findByIssuerAndSubject(p.issuer,subject)
            if (!identity || identity.provider?.id!=p.id) {
                flash.error='This external identity is not linked. Sign in with your existing Parts Manager account, then connect the provider from Account Connections.'
                response.sendRedirect(request.contextPath + '/login/auth');return
            }
            User user=identity.user
            if (!user?.enabled || user.accountLocked || user.accountExpired) throw new SecurityException('Local account disabled')
            def details=userDetailsService.loadUserByUsername(user.username)
            if (!details.enabled || !details.accountNonLocked || !details.accountNonExpired || !details.credentialsNonExpired) throw new SecurityException('Local account unavailable')
            def auth=new UsernamePasswordAuthenticationToken(details,null,details.authorities)
            SecurityContextHolder.context.authentication=auth
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,SecurityContextHolder.context)
            oidcAccountLinkService.markLogin(identity.id)
            response.sendRedirect(request.contextPath+'/')
        } catch (Exception e) { log.warn('OIDC callback rejected',e); flash.error='OIDC authentication failed. Please use local login or contact an administrator.'; response.sendRedirect(request.contextPath + '/login/auth') }
    }
    OidcAccountLinkService oidcAccountLinkService
    @Secured(['ROLE_PLM_USER','ROLE_ADMIN'])
    def account() {
        render(view:'/oidcLogin/account',model:[providers:OidcProvider.findAllByEnabled(true,[sort:'name']),
            links:OidcIdentity.findAllByUser(springSecurityService.currentUser as User)])
    }
    @Secured(['ROLE_PLM_USER','ROLE_ADMIN'])
    def unlink() {
        if (request.method!='POST') {response.sendError(405);return}
        oidcAccountLinkService.unlink(params.long('id'),springSecurityService.currentUser as User)
        flash.message='External identity disconnected.'
        response.sendRedirect(request.contextPath + '/oidcLogin/account')
    }
}
