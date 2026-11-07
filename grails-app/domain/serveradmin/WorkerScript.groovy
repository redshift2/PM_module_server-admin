package serveradmin

class WorkerScript {
    String name
    String scriptVersion
    String jobType
    String sha256
    String storageFile
    Boolean enabled = false
    String uploadedBy
    Date dateCreated
    Date lastUpdated
    static constraints = {
        name blank: false, maxSize: 120
        scriptVersion blank: false, maxSize: 50
        jobType blank: false, maxSize: 80
        sha256 blank: false, maxSize: 64
        storageFile blank: false, maxSize: 255
        uploadedBy nullable: true, maxSize: 120
    }
    static mapping = { table 'server_admin_worker_script' }
}
