package serveradmin

import grails.gorm.transactions.Transactional

/** All event-rule writes run inside a Grails-managed transaction. */
@Transactional
class ServerEventRuleManagementService {

    private String validatedDestination(String action, String destination, String jobDestination) {
        if (!(action in ['LOG', 'EMAIL', 'SUBMIT_JOB']))
            throw new IllegalArgumentException('Unknown action')
        String dest = (action == 'SUBMIT_JOB' ? jobDestination : destination)?.trim() ?: ''
        if (action == 'EMAIL' && !(dest ==~ /^[^\s@]+@[^\s@]+\.[^\s@]+$/))
            throw new IllegalArgumentException('Valid email recipient required')
        if (action == 'SUBMIT_JOB' && !WorkerScript.findAllByEnabled(true).any { it.jobType == dest })
            throw new IllegalArgumentException('Job type requires an enabled worker script')
        return dest
    }

    private Long validatedScript(String action, String jobType, Long scriptId) {
        if (action != 'SUBMIT_JOB') return null
        if (scriptId == null) return null // Existing rules remain compatible
        WorkerScript script = WorkerScript.get(scriptId)
        if (!script || !script.enabled || script.jobType != jobType)
            throw new IllegalArgumentException('Select an enabled script matching the job type')
        return script.id
    }

    private void validateEventType(String type) {
        if (!(type in ServerEventService.KNOWN_TYPES))
            throw new IllegalArgumentException('Unknown event type')
    }

    ServerEventRule createRule(String name, String type, String action, String destination, String jobDestination, String routeData = null, Long scriptId = null) {
        validateEventType(type)
        String dest = validatedDestination(action, destination, jobDestination)
        return new ServerEventRule(name: name, eventType: type,
            actionType: action, destination: dest, scriptId: validatedScript(action, dest, scriptId),
            routeData: action == 'SUBMIT_JOB' ? JobResultRoute.validate(routeData ?: JobResultRoute.legacyDefault(dest)) : 'JOB_RESULTS').save(failOnError: true, flush: true)
    }

    ServerEventRule updateRule(Long id, String name, String type, String action,
                               String destination, String jobDestination, boolean enabled, String routeData = null, Long scriptId = null) {
        if (id == null) throw new IllegalArgumentException('Rule ID required')
        ServerEventRule rule = ServerEventRule.get(id)
        if (!rule) throw new IllegalArgumentException('Rule not found')
        validateEventType(type)
        String dest = validatedDestination(action, destination, jobDestination)
        rule.name = name
        rule.eventType = type
        rule.actionType = action
        rule.destination = dest
        rule.scriptId = validatedScript(action, dest, scriptId)
        rule.routeData = action == 'SUBMIT_JOB' ? JobResultRoute.validate(routeData ?: JobResultRoute.legacyDefault(dest)) : 'JOB_RESULTS'
        rule.enabled = enabled
        return rule.save(failOnError: true, flush: true)
    }

    void deleteRule(Long id) {
        if (id == null) throw new IllegalArgumentException('Rule ID required')
        ServerEventRule rule = ServerEventRule.get(id)
        if (!rule) throw new IllegalArgumentException('Rule not found')
        if (ServerEventAction.countByRule(rule) > 0)
            throw new IllegalStateException('Rule has event history. Disable it instead of deleting it.')
        rule.delete(flush: true)
    }
}
