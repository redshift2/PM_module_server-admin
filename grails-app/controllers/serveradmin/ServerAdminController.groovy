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

    private boolean admin() {
        SecurityContextHolder.context?.authentication?.authorities?.any { it.authority == 'ROLE_ADMIN' } ?: false
    }

    def index() { renderServerInfo() }

    def serverInfo() { renderServerInfo() }

    private void renderServerInfo() {
        List<Map> modules = serverAdminModuleInfoService.installedModules()
        render(view: 'index', model: [tab: 'info', modules: modules, isAdmin: admin()])
    }

    def jobQueue() { render(view: 'index', model: [tab: 'jobs', isAdmin: admin()]) }

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
