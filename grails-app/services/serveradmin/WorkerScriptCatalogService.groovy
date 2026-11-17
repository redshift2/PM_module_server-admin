package serveradmin

import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput
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
            Map script = [name:row.name?.toString(), version:row.version?.toString(), description:row.description?.toString(), downloadUrl:row.downloadUrl?.toString(), jobType:row.jobType?.toString(), configurationSchema: validateSchema(row.configurationSchema)]
            if (!(script.name ==~ /[A-Za-z0-9_.-]{1,120}/) || !(script.version ==~ /[A-Za-z0-9_.-]{1,50}/) ||
                !(script.jobType ==~ /[A-Z0-9_]{2,80}/) || !script.description || script.description.length() > 2000 ||
                !script.downloadUrl?.startsWith(SCRIPT_PREFIX) || !(script.downloadUrl.substring(SCRIPT_PREFIX.length()) ==~ '[A-Za-z0-9_./-]+\\.py') ||
                script.downloadUrl.contains('/../')) throw new IllegalArgumentException('Invalid script entry in manifest: ' + script.name)
            script
        }
    }

    /** Accept only a small declarative field schema, never executable expressions. */
    static Map validateSchema(def raw) {
        if (raw == null) return [fields:[]]
        if (!(raw instanceof Map) || !(raw.fields instanceof List) || raw.fields.size() > 20)
            throw new IllegalArgumentException('Invalid configuration schema')
        Set names = [] as Set
        List fields = raw.fields.collect { f ->
            if (!(f instanceof Map)) throw new IllegalArgumentException('Invalid field')
            String name = f.name?.toString()
            String type = f.type?.toString()
            if (!(name ==~ /[A-Za-z][A-Za-z0-9_]{0,49}/) ||
                !(type in ['text','integer','boolean','select','zipFile','string']) || !names.add(name))
                throw new IllegalArgumentException('Invalid configuration field')
            String label = f.label?.toString() ?: name
            if (label.length() > 100) throw new IllegalArgumentException('Field label too long')
            List options = []
            if (type == 'select') {
                if (!(f.options instanceof List) || f.options.size() > 40 || !f.options)
                    throw new IllegalArgumentException('Invalid select options')
                options = f.options.collect { it.toString() }
                if (options.any { it.length() > 120 }) throw new IllegalArgumentException('Option too long')
            }
            Map field = [name:name, type:(type == 'string' ? 'text' : type), label:label,
                         required:(f.required == true), options:options]
            if (f.containsKey('default')) {
                def value = f['default']
                if (type == 'boolean' && !(value instanceof Boolean)) throw new IllegalArgumentException('Boolean default required')
                if (type == 'integer' && !(value instanceof Number)) throw new IllegalArgumentException('Numeric default required')
                if (type in ['text','string','zipFile','select'] && !(value instanceof String)) throw new IllegalArgumentException('String default required')
                if (type == 'select' && !options.contains(value)) throw new IllegalArgumentException('Default must be a select option')
                field['default'] = value
            }
            field
        }
        [fields:fields]
    }

    @Transactional
    WorkerScript install(Map script, String username) {
        byte[] bytes = download(script.downloadUrl as String, MAX_SCRIPT)
        if (!bytes.length) throw new IllegalArgumentException('Empty script')
        MultipartFile file = [getName:{ 'file' }, getOriginalFilename:{ script.name + '.py' }, getContentType:{ 'text/x-python' }, isEmpty:{ bytes.length == 0 }, getSize:{ (long)bytes.length }, getBytes:{ bytes }, getInputStream:{ new ByteArrayInputStream(bytes) }, transferTo:{ File dest -> dest.bytes = bytes }] as MultipartFile
        WorkerScript installed = workerScriptService.upload(file, script.name as String, script.version as String, script.jobType as String, username)
        // Prefer schema embedded in the Python source; fall back to catalog metadata.
        Map embedded = EmbeddedWorkerScriptSchema.extract(bytes)
        installed.configurationSchemaJson = JsonOutput.toJson(embedded ?: validateSchema(script.configurationSchema))
        installed.save(failOnError:true, flush:true)
        return installed
    }
}
