package serveradmin

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Owns the event-rule -> remote-job decision. Triggered on authenticated worker polls. */
class ServerEventJobDispatchService {
    def grailsApplication
    PlatformTransactionManager transactionManager

    private TransactionTemplate newTransaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager)
        template.propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        return template
    }

    // Claim one action in a separate transaction. Never hold a transaction open
    // while packaging CAD data or writing a job ZIP.
    synchronized Long claimOne() {
        return newTransaction().execute { tx ->
        for (ServerEventAction action : ServerEventAction.findAllByActionTypeAndStatus(
                'SUBMIT_JOB', 'QUEUED', [sort:'dateCreated', order:'asc', max:20])) {
            if (!WorkerScript.findByJobTypeAndEnabled(action.destination, true)) {
                action.status = 'FAILED'
                action.resultMessage = 'No enabled worker script for: ' + action.destination
                action.completedAt = new Date()
                action.save(failOnError:true, flush:true)
                continue
            }
            action.status = 'PROCESSING'
            action.save(failOnError:true, flush:true)
            return action.id
        }
        return null
        }
    }

    void finish(Long id, boolean success, String message) {
        newTransaction().execute { tx ->
        ServerEventAction action = ServerEventAction.get(id)
        if (!action || action.status != 'PROCESSING') return null
        action.status = success ? 'COMPLETED' : 'FAILED'
        action.resultMessage = (message ?: '').take(2000)
        action.completedAt = new Date()
        action.save(failOnError:true, flush:true)
        return null
        }
    }

    void processPending(int limit = 3) {
        int count = Math.min(Math.max(limit, 1), 10)
        for (int i = 0; i < count; i++) {
            Long id = null
            try {
                id = claimOne()
                if (id == null) break
                ServerEventAction action = ServerEventAction.get(id)
                if (action == null || action.event == null)
                    throw new IllegalStateException("Missing event for action ${id}")
                ServerEvent event = action.event
                String jobType = action.destination
                grailsApplication.mainContext.getBean('serverEventJobPackageService')
                    .enqueueEventJob(jobType, event.id, event.actor ?: 'event-manager',
                        action.routeData ?: JobResultRoute.legacyDefault(jobType))
                finish(id, true, jobType + ' job created')
            } catch (Exception ex) {
                log.error("Event action ${id ?: 'claim'} failed", ex)
                if (id != null) {
                    try {
                        finish(id, false, ex.message ?: ex.class.simpleName)
                    } catch (Exception finishError) {
                        log.error("Could not mark event action ${id} FAILED", finishError)
                    }
                }
                break
            }
        }
    }
}
