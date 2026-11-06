package serveradmin

import grails.gorm.transactions.Transactional

/** Admin-triggered dispatch for email actions. Remote jobs stay queued for future worker. */
class ServerEventActionService {
    ServerAdminEmailService serverAdminEmailService

    @Transactional(readOnly = true)
    List<ServerEventAction> recent() { ServerEventAction.list(max:100, sort:'dateCreated', order:'desc') }

    void dispatchEmail(Long id) {
        ServerEventAction action = ServerEventAction.get(id)
        if (!action || action.actionType != 'EMAIL' || action.status != 'QUEUED')
            throw new IllegalArgumentException('Email action not queued')
        try {
            serverAdminEmailService.send(action.destination,
                'Server event: ' + action.event.eventType,
                "Event: ${action.event.eventType}\nModule: ${action.event.sourceModule}\nObject: ${action.event.entityType ?: ''} ${action.event.entityId ?: ''}\nUser: ${action.event.actor ?: 'unknown'}\nTime: ${action.event.dateCreated}\nDetails: ${action.event.message ?: ''}")
            action.status = 'COMPLETED'
            action.completedAt = new Date()
            action.resultMessage = 'Email sent'
        } catch (Exception ex) {
            action.status = 'FAILED'
            action.resultMessage = ex.message?.take(1800)
            action.save(failOnError:true, flush:true)
            throw ex
        }
        action.save(failOnError:true, flush:true)
    }
}
