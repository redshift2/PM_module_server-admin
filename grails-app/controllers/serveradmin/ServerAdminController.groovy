package serveradmin

import grails.plugin.springsecurity.annotation.Secured
import org.springframework.web.multipart.MultipartFile
import org.springframework.security.core.context.SecurityContextHolder

@Secured(['ROLE_PLM_USER', 'ROLE_ADMIN'])
class ServerAdminController {
    def grailsPluginManager
    def springSecurityService
    ServerAdminMaintenanceService serverAdminMaintenanceService
    ServerAdminModuleInfoService serverAdminModuleInfoService
    ServerAdminMaterialsService serverAdminMaterialsService
    ServerEventService serverEventService
    ServerJobService serverJobService
    ServerJobPackageService serverJobPackageService
    ServerEventActionService serverEventActionService
    ServerAdminEmailService serverAdminEmailService

    private boolean admin() {
        SecurityContextHolder.context?.authentication?.authorities?.any { it.authority == 'ROLE_ADMIN' } ?: false
    }

    def index() { renderServerInfo() }

    def serverInfo() { renderServerInfo() }

    private void renderServerInfo() {
        List<Map> modules = serverAdminModuleInfoService.installedModules()
        render(view: 'index', model: [tab: 'info', modules: modules, isAdmin: admin()])
    }

    def jobQueue() {
        render(view: 'index', model: [tab: 'jobs', isAdmin: admin(), jobs: serverJobService.recent()])
    }

    @Secured(['ROLE_ADMIN'])
    def submitJob() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            serverJobService.submit(params.jobType as String,
                springSecurityService.currentUser?.username as String)
            flash.message = 'Job submitted to queue. Execution worker is not configured yet.'
        } catch (Exception ex) {
            flash.error = ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/jobQueue')
    }

    @Secured(['ROLE_ADMIN'])
    def events() {
        render(view: 'index', model: [tab: 'events', isAdmin: true, events: serverEventService.recent(), rules: ServerEventRule.list(sort:'id', order:'desc'), actions: serverEventActionService.recent()])
    }

    @Secured(['ROLE_ADMIN'])
    def createEventRule() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            String type = params.eventType as String
            String action = params.actionType as String
            String dest = (params.destination ?: '') as String
            if (!(type in ServerEventService.KNOWN_TYPES)) throw new IllegalArgumentException('Unknown event type')
            if (!(action in ['LOG','EMAIL','SUBMIT_JOB'])) throw new IllegalArgumentException('Unknown action')
            if (action == 'EMAIL' && !dest.matches('^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$')) throw new IllegalArgumentException('Valid email recipient required')
            if (action == 'SUBMIT_JOB' && !(dest in ServerJobService.SUPPORTED_TYPES)) throw new IllegalArgumentException('Unsupported job type')
            new ServerEventRule(name: params.name, eventType:type, actionType:action, destination:dest).save(failOnError:true, flush:true)
            flash.message = 'Event rule saved.'
        } catch (Exception ex) { flash.error = ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/events')
    }

    @Secured(['ROLE_ADMIN'])
    def sendEventEmail() {
        if (request.method != 'POST') { response.sendError(405); return }
        try { serverEventActionService.dispatchEmail(params.long('id')); flash.message = 'Email sent.' }
        catch (Exception ex) { flash.error = 'Email delivery failed: ' + ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/events')
    }

    @Secured(['ROLE_ADMIN'])
    def testEmail() {
        if (request.method != 'POST') { response.sendError(405); return }
        try {
            serverAdminEmailService.send(params.to as String, 'Server Administration SMTP test', 'SMTP connection test from Server Administration.')
            flash.message = 'Test email sent.'
        } catch (Exception ex) { flash.error = 'SMTP test failed: ' + ex.message }
        response.sendRedirect(request.contextPath + '/serverAdmin/emailServer')
    }

    @Secured(['ROLE_ADMIN'])
    def submitPackageJob() {
        if (request.method != 'POST') { response.sendError(405); return }
        ServerJob job
        try {
            MultipartFile upload = request.getFile('file')
            job = serverJobService.submit('REMOTE_PACKAGE', springSecurityService.currentUser?.username as String)
            job.packageFileName = serverJobPackageService.store(upload, job.id)
            job.save(failOnError:true, flush:true)
            flash.message = 'ZIP job queued. Remote worker dispatch is not yet implemented.'
        } catch (Exception ex) {
            if (job) { job.delete(flush:true) }
            flash.error = 'Job submission failed: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/jobQueue')
    }

    @Secured(['ROLE_ADMIN'])
    def emailServer() {
        // SMTP secrets must come from environment / external secret store, not from a GSP or DB.
        render(view: 'index', model: [tab: 'email', isAdmin: true,
            smtpHost: System.getenv('SERVER_ADMIN_SMTP_HOST') ?: '',
            smtpPort: System.getenv('SERVER_ADMIN_SMTP_PORT') ?: '',
            smtpFrom: System.getenv('SERVER_ADMIN_SMTP_FROM') ?: '',
            smtpConfigured: !!System.getenv('SERVER_ADMIN_SMTP_HOST')])
    }


    @Secured(['ROLE_ADMIN'])
    def authorization() { render(view: 'index', model: [tab: 'auth', isAdmin: true]) }

    @Secured(['ROLE_ADMIN'])
    def maintenance() { render(view: 'index', model: [tab: 'maintenance', isAdmin: true]) }

    @Secured(['ROLE_ADMIN'])
    def exportParts() {
        byte[] content = serverAdminMaintenanceService.exportPartsCsv()
        response.contentType = 'text/csv; charset=UTF-8'
        response.setHeader('Content-Disposition', 'attachment; filename="plm-parts-metadata.csv"')
        response.outputStream.write(content)
        response.outputStream.flush()
    }

    @Secured(['ROLE_ADMIN'])
    def backup() {
        byte[] content = serverAdminMaintenanceService.metadataBackupZip()
        response.contentType = 'application/zip'
        response.setHeader('Content-Disposition', 'attachment; filename="plm-metadata-backup.zip"')
        response.outputStream.write(content)
        response.outputStream.flush()
    }

    @Secured(['ROLE_ADMIN'])
    def importParts() {
        if (request.method != 'POST') { response.sendError(405); return }
        MultipartFile csv = request.getFile('file')
        if (!csv || csv.empty) { flash.error = 'Select a CSV file.'; response.sendRedirect(request.contextPath + '/serverAdmin/maintenance'); return }
        if (csv.size > 10L * 1024 * 1024) { response.sendError(413, 'CSV exceeds 10 MB'); return }
        try {
            Map result = serverAdminMaintenanceService.importMetadataCsv(csv.inputStream)
            flash.message = "Updated ${result.updated} existing parts; skipped ${result.skipped} rows."
            if (result.errors) flash.error = result.errors.take(8).join(' | ')
        } catch (Exception ex) {
            log.warn('Bulk metadata import failed', ex)
            flash.error = 'Import failed: ' + ex.message
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/maintenance')
    }
    @Secured(['ROLE_ADMIN'])
    def exportMaterials() {
        byte[] content = serverAdminMaterialsService.exportJson()
        response.contentType = 'application/json; charset=UTF-8'
        response.setHeader('Content-Disposition', 'attachment; filename="materials-library.json"')
        response.outputStream.write(content)
        response.outputStream.flush()
    }

    @Secured(['ROLE_ADMIN'])
    def importMaterials() {
        if (request.method != 'POST') { response.sendError(405); return }
        MultipartFile upload = request.getFile('file')
        if (!upload || upload.empty) {
            flash.error = 'Select a Materials Library JSON file.'
        } else if (upload.size > 20L * 1024 * 1024) {
            response.sendError(413, 'Materials JSON exceeds 20 MB'); return
        } else {
            try {
                Map result = serverAdminMaterialsService.importJson(upload.inputStream)
                flash.message = "Materials imported: ${result.categories} categories, ${result.materials} materials."
            } catch (Exception ex) {
                log.warn('Materials import failed', ex)
                flash.error = 'Materials import failed: ' + ex.message
            }
        }
        response.sendRedirect(request.contextPath + '/serverAdmin/maintenance')
    }

}
