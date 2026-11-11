package serveradmin

import grails.gorm.transactions.Transactional
import groovy.json.JsonSlurper
import org.springframework.web.multipart.MultipartFile
import java.net.HttpURLConnection
import java.net.URL

class WorkerScriptCatalogService {
    static final String MANIFEST_URL = 'https://raw.githubusercontent.com/redshift2/Part-Manager_Remote_worker/main/scripts/scripts.json'
    static final String SCRIPT_PREFIX = 'https://raw.githubusercontent.com/redshift2/Part-Manager_Remote_worker/main/scripts/'
    static final int MAX_MANIFEST = 256 * 1024
    static final int MAX_SCRIPT = 1024 * 1024
    WorkerScriptService workerScriptService

    private byte[] download(String address, int limit) {
        if (!address.startsWith(SCRIPT_PREFIX)) throw new IllegalArgumentException('Only scripts in the approved repository are allowed')
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection()
        c.connectTimeout = 5000
        c.readTimeout = 15000
        c.instanceFollowRedirects = false
        try {
            if (c.responseCode != 200) throw new IllegalStateException('GitHub returned HTTP ' + c.responseCode)
            if (c.contentLengthLong > limit) throw new IllegalArgumentException('Download exceeds size limit')
            ByteArrayOutputStream out = new ByteArrayOutputStream()
            c.inputStream.withCloseable { input ->
                byte[] buffer = new byte[8192]
                int count
                while ((count = input.read(buffer)) != -1) {
                    if (out.size() + count > limit) throw new IllegalArgumentException('Download exceeds size limit')
                    out.write(buffer, 0, count)
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    List<Map> refresh() {
        def parsed = new JsonSlurper().parseText(new String(download(MANIFEST_URL, MAX_MANIFEST), 'UTF-8'))
        if (!(parsed instanceof Map) || !(parsed.scripts instanceof List)) throw new IllegalArgumentException('Manifest must contain a scripts array')
        if (parsed.scripts.size() > 200) throw new IllegalArgumentException('Too many scripts in manifest')
        return parsed.scripts.collect { row ->
            if (!(row instanceof Map)) throw new IllegalArgumentException('Invalid script entry')
            Map script = [name:row.name?.toString(), version:row.version?.toString(), description:row.description?.toString(), downloadUrl:row.downloadUrl?.toString(), jobType:row.jobType?.toString()]
            if (!(script.name ==~ /[A-Za-z0-9_.-]{1,120}/) || !(script.version ==~ /[A-Za-z0-9_.-]{1,50}/) ||
                !(script.jobType ==~ /[A-Z0-9_]{2,80}/) || !script.description || script.description.length() > 2000 ||
                !script.downloadUrl?.startsWith(SCRIPT_PREFIX) || !(script.downloadUrl.substring(SCRIPT_PREFIX.length()) ==~ '[A-Za-z0-9_./-]+\\.py') ||
                script.downloadUrl.contains('/../')) throw new IllegalArgumentException('Invalid script entry in manifest: ' + script.name)
            script
        }
    }

    @Transactional
    WorkerScript install(Map script, String username) {
        byte[] bytes = download(script.downloadUrl as String, MAX_SCRIPT)
        if (!bytes.length) throw new IllegalArgumentException('Empty script')
        MultipartFile file = [getName:{ 'file' }, getOriginalFilename:{ script.name + '.py' }, getContentType:{ 'text/x-python' }, isEmpty:{ bytes.length == 0 }, getSize:{ (long)bytes.length }, getBytes:{ bytes }, getInputStream:{ new ByteArrayInputStream(bytes) }, transferTo:{ File dest -> dest.bytes = bytes }] as MultipartFile
        return workerScriptService.upload(file, script.name as String, script.version as String, script.jobType as String, username)
    }
}
