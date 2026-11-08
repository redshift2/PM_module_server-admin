package serveradmin

import org.springframework.scheduling.annotation.Scheduled

/** Independently recovers expired leases even when no workers poll. */
class ExpiredJobRecoveryService {
    WorkerDispatchService workerDispatchService

    // Runs every minute, with an initial one-minute delay.
    @Scheduled(fixedDelay = 60000L, initialDelay = 60000L)
    void recoverExpiredLeases() {
        try {
            int count = workerDispatchService.recoverExpiredJobs(new Date())
            if (count > 0) log.warn("Scheduled recovery returned ${count} expired job(s) to QUEUED")
        } catch (Exception e) {
            log.error('Scheduled expired-job recovery failed', e)
        }
    }
}
