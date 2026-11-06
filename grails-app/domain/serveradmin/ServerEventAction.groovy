package serveradmin

/** Durable action outbox. SMTP and remote execution are dispatched separately. */
class ServerEventAction {
    ServerEvent event
    ServerEventRule rule
    String actionType
    String status = 'QUEUED'
    String destination
    String resultMessage
    Date dateCreated
    Date lastUpdated
    Date completedAt
    static constraints = {
        event nullable:false
        rule nullable:false
        actionType blank:false, maxSize:32
        status blank:false, maxSize:24
        destination nullable:true, maxSize:255
        resultMessage nullable:true, maxSize:2000
        completedAt nullable:true
    }
    static mapping = { table 'server_admin_event_action' }
}
