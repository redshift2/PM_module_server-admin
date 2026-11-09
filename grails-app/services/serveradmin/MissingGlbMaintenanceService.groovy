package serveradmin

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import taack.ui.TaackUiConfiguration
import java.nio.file.*
import java.util.zip.*

/** Enqueue missing latest-version GLB previews; never alters the CAD document. */
class MissingGlbMaintenanceService {
    def grailsApplication
    ServerJobService serverJobService

    Map enqueue(Long scriptId, String actor, boolean forceRecreate = false) {
        WorkerScript script = WorkerScript.get(scriptId)
        if (!script || !script.enabled || script.jobType != 'CAD_TO_GLB')
            throw new IllegalArgumentException('Choose an enabled CAD_TO_GLB worker script')
        String storage = System.getenv('SERVER_ADMIN_JOB_STORAGE')
        if (!storage) throw new IllegalStateException('SERVER_ADMIN_JOB_STORAGE is not configured')
        Path dir = Paths.get(storage).toAbsolutePath().normalize()
        Files.createDirectories(dir)
        def previewService = grailsApplication.mainContext.getBean('plmPreviewStorageService')
        Class partClass = grailsApplication.classLoader.loadClass('plm.PlmFreeCadPart')
        int scanned = 0, existing = 0, queued = 0, skipped = 0, errors = 0
        // Check the latest revision only (nextVersion == null), not older revisions.
        List parts = partClass.executeQuery('from PlmFreeCadPart p where p.nextVersion is null order by p.id asc')
        Set<String> pending = [] as Set<String>
        ServerJob.findAllByStatusInList(['QUEUED','ASSIGNED','RUNNING','UPLOADING_RESULTS']).each { ServerJob j ->
            if (j.jobType != 'CAD_TO_GLB') return
            try {
                def m = new JsonSlurper().parseText(j.parametersJson ?: '{}')
                if (m.partId != null) pending.add(m.partId.toString() + ':' + (m.cadVersion ?: ''))
            } catch (Exception ignored) { }
        }
        parts.each { part ->
            scanned++
            String version = "${part.majorVersion == null ? 1 : part.majorVersion}.${part.minorVersion == null ? 0 : part.minorVersion}"
            if (previewService.modelFile(part).isFile()) { existing++; if (!forceRecreate) return }
            String key = part.id.toString() + ':' + version
            if (pending.contains(key)) { skipped++; return }
            try {
                String storedName = part.plmFilePath?.toString()
                if (!(storedName ==~ /[a-fA-F0-9]{40}\.[a-zA-Z0-9]+/)) { skipped++; return }
                Path modelDir = Paths.get(TaackUiConfiguration.root, 'plmFreecad', 'model').toAbsolutePath().normalize()
                Path cad = modelDir.resolve(storedName).normalize()
                if (!cad.startsWith(modelDir) || !Files.isRegularFile(cad)) { skipped++; return }
                Map manifest = [jobType:script.jobType, routeData:'3D_VIEWER', partId:part.id,
                    cadVersion:version, cadSha1:part.plmContentShaOne, inputFile:'part.FCStd',
                    maintenance:true, forceRecreate:forceRecreate, scriptId:script.id]
                ServerJob job = serverJobService.submit(script.jobType, actor, manifest, '3D_VIEWER')
                Path tmp = Files.createTempFile(dir, 'missing-glb-', '.tmp')
                try {
                    ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))
                    try {
                        zip.putNextEntry(new ZipEntry('manifest.json'))
                        zip.write(JsonOutput.toJson(manifest).getBytes('UTF-8'))
                        zip.closeEntry()
                        zip.putNextEntry(new ZipEntry('part.FCStd'))
                        Files.copy(cad, zip)
                        zip.closeEntry()
                    } finally { zip.close() }
                    String filename = 'job-' + job.id + '.zip'
                    Files.move(tmp, dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING)
                    serverJobService.setPackageFileName(job.id, filename)
                    pending.add(key)
                    queued++
                } finally { Files.deleteIfExists(tmp) }
            } catch (Exception e) {
                errors++
                log.error("Unable to queue missing GLB for part ${part.id}", e)
            }
        }
        [scanned:scanned, existing:existing, queued:queued, skipped:skipped, errors:errors]
    }
}
