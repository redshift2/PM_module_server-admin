package serveradmin

/** Administrator-configured reaction to an audit event. */
class ServerEventRule {
    String name
    String eventType
    String actionType // LOG, EMAIL, SUBMIT_JOB
    String routeData = 'JOB_RESULTS'
    Long scriptId // Explicit worker script for SUBMIT_JOB; null for legacy rules
    String destination // Email recipient or job type; never a shell command
    boolean enabled = true
    Date dateCreated
    Date lastUpdated
    static constraints = {
        scriptId nullable: true
        routeData nullable: true, maxSize: 32
        name blank:false, maxSize:120
        eventType blank:false, maxSize:80
        actionType inList:['LOG','EMAIL','SUBMIT_JOB']
        destination nullable:true, maxSize:255
    }
    static mapping = { table 'server_admin_event_rule' }
}
