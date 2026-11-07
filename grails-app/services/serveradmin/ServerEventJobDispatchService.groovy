package serveradmin

import grails.gorm.transactions.Transactional
import org.springframework.transaction.annotation.Propagation

/** Owns the event-rule -> remote-job decision. Triggered on authenticated worker polls. */
class ServerEventJobDispatchService {
    def grailsApplication

    // Claim one action in a separate transaction. Never hold a transaction open
    // while packaging CAD data or writing a job ZIP.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    synchronized Long claimOne() {
        for (ServerEventAction action : ServerEventAction.findAllByActionTypeAndStatus(
                'SUBMIT_JOB', 'QUEUED', [sort:'dateCreated', order:'asc', max:20])) {
            if (action.destination != 'CAD_TO_GLB') {
                action.status = 'FAILED'
                action.resultMessage = 'No event job handler registered for: ' + action.destination
                action.completedAt = new Date()
                action.save(failOnError:true, flush:true)
                continue
            }
            action.status = 'PROCESSING'
            action.save(failOnError:true, flush:true)
            return action.id
        }
        null
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void finish(Long id, boolean success, String message) {
        ServerEventAction action = ServerEventAction.get(id)
        if (!action || action.status != 'PROCESSING') return
        action.status = success ? 'COMPLETED' : 'FAILED'
        action.resultMessage = (message ?: '').take(2000)
        action.completedAt = new Date()
        action.save(failOnError:true, flush:true)
    }

    void processPending(int limit = 3) {
        int count = Math.min(Math.max(limit, 1), 10)
        for (int i=0; i<count; i++) {
            Long id = claimOne()
            if (id == null) break
            try {
                ServerEventAction action = ServerEventAction.get(id)
                ServerEvent event = action.event
                if (event.eventType != 'PART_RELEASE' || event.sourceModule?.toLowerCase() != 'plm')
                    throw new IllegalArgumentException('CAD_TO_GLB requires a PLM PART_RELEASE event')
                if (!(event.entityId ==~ /[0-9]+/))
                    throw new IllegalArgumentException('Invalid PLM part id')
                Long partId = Long.valueOf(event.entityId)
                // Resolve PLM dynamically to avoid a compile-time cross-module dependency.
                def partClass = grailsApplication.classLoader.loadClass('plm.PlmFreeCadPart')
                def part = partClass.get(partId)
                if (!part) throw new IllegalStateException('Released part no longer exists')
                String version = "${part.majorVersion ?: 1}.${part.minorVersion ?: 0}"
                String sha = part.plmContentShaOne
                if (!sha) throw new IllegalStateException('Part has no CAD SHA-1')
                grailsApplication.mainContext.getBean('plmCadConversionService')
                    .enqueueReleasedPart(partId, version, sha, event.actor ?: 'plm')
                finish(id, true, 'CAD_TO_GLB job created')
            } catch (Exception ex) {
                log.error("Event action ${id} failed", ex)
                finish(id, false, ex.message ?: ex.class.simpleName)
            }
        }
    }
}
