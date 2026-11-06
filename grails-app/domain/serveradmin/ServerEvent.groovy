package serveradmin

/** Persisted cross-module audit event. Do not store credentials or full file contents. */
class ServerEvent {
    String eventType
    String sourceModule
    String entityType
    String entityId
    String actor
    String message
    String payloadJson
    Date dateCreated

    static constraints = {
        eventType blank: false, maxSize: 80
        sourceModule blank: false, maxSize: 80
        entityType nullable: true, maxSize: 80
        entityId nullable: true, maxSize: 120
        actor nullable: true, maxSize: 120
        message nullable: true, maxSize: 2000
        payloadJson nullable: true, maxSize: 8000
    }
    static mapping = {
        table 'server_admin_event'
        eventType index: 'sa_event_type_idx'
        dateCreated index: 'sa_event_created_idx'
        payloadJson type: 'text'
    }
}
