package serveradmin

import grails.gorm.transactions.Transactional

@Transactional
class RemoteWorkerDeletionService {
    void deleteWorker(Long id) {
        if (id == null) throw new IllegalArgumentException('Worker ID required')
        RemoteWorker worker = RemoteWorker.get(id)
        if (!worker) throw new IllegalArgumentException('Worker not found')
        if (ServerJob.countByAssignedWorkerIdAndStatusInList(worker.workerId,
                ['ASSIGNED', 'RUNNING', 'IN_PROGRESS']) > 0)
            throw new IllegalStateException('Worker has active jobs. Revoke and wait for jobs to finish.')
        worker.delete(flush: true)
    }
}
