package serveradmin

import jakarta.annotation.PostConstruct
import org.codehaus.groovy.runtime.MethodClosure
import taack.app.TaackApp
import taack.app.TaackAppRegisterService

class ServerAdminRegistrationService {
    static lazyInit = false
    @PostConstruct
    void init() {
        def stream = this.class.getResourceAsStream('/serveradmin/server-admin.svg')
        if (!stream) { log.error('SERVER ADMIN: icon missing'); return }
        String svg = stream.withCloseable { new String(it.readAllBytes(), 'UTF-8') }
        TaackAppRegisterService.register(new TaackApp(ServerAdminController.&index as MethodClosure, svg))
        log.info('SERVER ADMIN: registered in application menu')
    }
}
