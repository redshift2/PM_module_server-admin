package serveradmin

import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput

/** Inject into other modules and call record(...). Events are committed with the caller transaction. */
@Transactional
class ServerEventService {
    static final Set<String> KNOWN_TYPES = [
        'PART_CHECK_IN', 'PART_CHECK_OUT', 'PART_RELEASE', 'PART_METADATA_EDIT',
        'MATERIAL_ADD', 'MATERIAL_EDIT', 'MATERIAL_DELETE',
        'DCN_CREATE', 'DCN_FREEZE', 'DCN_RELEASE'
    ].asImmutable()

    ServerEvent record(String type, String sourceModule, String entityType = null,
                       Object entityId = null, String actor = null, String message = null,
                       Map payload = [:]) {
        if (!(type in KNOWN_TYPES)) throw new IllegalArgumentException('Unsupported event type: ' + type)
        if (!sourceModule?.trim()) throw new IllegalArgumentException('sourceModule is required')
        // Whitelist non-sensitive fields at the calling module. Avoid passwords, tokens, document data.
        String json = payload ? JsonOutput.toJson(payload) : null
        if (json?.length() > 8000) throw new IllegalArgumentException('Event payload exceeds 8000 characters')
        ServerEvent event = new ServerEvent(eventType: type, sourceModule: sourceModule,
            entityType: entityType, entityId: entityId?.toString(), actor: actor,
            message: message, payloadJson: json)
        event.save(failOnError: true)
        ServerEventRule.findAllByEventTypeAndEnabled(type, true).each { ServerEventRule rule ->
            new ServerEventAction(event: event, rule: rule, actionType: rule.actionType,
                destination: rule.destination, status: rule.actionType == 'LOG' ? 'COMPLETED' : 'QUEUED',
                completedAt: rule.actionType == 'LOG' ? new Date() : null).save(failOnError: true)
        }
        return event
    }

    @Transactional(readOnly = true)
    List<ServerEvent> recent(int limit = 100) {
        ServerEvent.list(max: Math.min(Math.max(limit, 1), 200), sort: 'dateCreated', order: 'desc')
    }
}
