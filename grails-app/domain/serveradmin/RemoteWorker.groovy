package serveradmin

class RemoteWorker {
    String workerId
    String workerName
    String publicKeyBase64
    String publicKeyFingerprint
    String status = 'PENDING_APPROVAL'
    String capabilities = '[]'
    Integer maxConcurrentJobs = 1
    String lastNonce
    Date lastSeen
    Date dateCreated
    Date lastUpdated
    static constraints = {
        workerId unique: true, blank: false, maxSize: 60
        workerName blank: false, maxSize: 120
        publicKeyBase64 blank: false, maxSize: 256
        publicKeyFingerprint blank: false, maxSize: 64
        status inList: ['PENDING_APPROVAL','APPROVED','REJECTED','REVOKED']
        capabilities maxSize: 2000
        maxConcurrentJobs min: 1, max: 16
        lastNonce nullable: true, maxSize: 100
        lastSeen nullable: true
    }
    static mapping = { table 'server_admin_remote_worker' }
}
