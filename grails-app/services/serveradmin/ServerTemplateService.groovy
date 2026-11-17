package serveradmin

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Non-versioned FreeCAD and drawing templates. Storage survives application rebuilds. */
class ServerTemplateService {
    private File root() {
        File dir = new File(System.getenv('PM_TEMPLATE_STORAGE_DIR') ?: '/var/lib/parts-manager/templates')
        if (!dir.exists() && !dir.mkdirs()) throw new IOException('Cannot create template storage: ' + dir)
        if (!dir.isDirectory()) throw new IOException('Invalid template storage directory')
        dir
    }

    private File metadata() { new File(root(), 'templates.json') }

    synchronized List<Map> listTemplates() {
        File f = metadata()
        if (!f.exists()) return []
        def parsed = new JsonSlurper().parse(f)
        parsed instanceof List ? (List<Map>) parsed : []
    }

    synchronized Map upload(MultipartFile upload, String category, String description) {
        if (!upload || upload.empty) throw new IllegalArgumentException('Select a template file.')
        if (!(category in ['FREECAD', 'DRAWING'])) throw new IllegalArgumentException('Invalid template category.')
        String name = (upload.originalFilename ?: '').replace('\\', '/').tokenize('/').last()
        if (!name || name.length() > 200 || name.contains('..') || name.contains('/') || name.contains('\\')) throw new IllegalArgumentException('Invalid filename.')
        String extension = name.contains('.') ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : ''
        if (category == 'FREECAD' && !(extension in ['.fcstd', '.fcstd1'])) throw new IllegalArgumentException('FreeCAD templates must be .FCStd files.')
        if (category == 'DRAWING' && !(extension in ['.svg', '.dxf', '.pdf', '.fcstd'])) throw new IllegalArgumentException('Drawing templates must be SVG, DXF, PDF, or FCStd.')
        if (upload.size > 100L * 1024 * 1024) throw new IllegalArgumentException('Template exceeds 100 MB.')
        List<Map> items = listTemplates()
        // No versioning: replace the same category/name in place.
        Map existing = items.find { it.category == category && it.name == name }
        String id = existing?.id ?: UUID.randomUUID().toString()
        File target = new File(root(), id + extension)
        File tmp = File.createTempFile('template-', '.upload', root())
        try {
            upload.inputStream.withCloseable { input -> tmp.withOutputStream { output -> output << input } }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
        Map record = [id:id, name:name, category:category, description:(description ?: '').take(500), size:target.length(), uploadedAt:new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss', Locale.US).format(new Date()), storedName:target.name]
        if (existing) items.remove(existing)
        items.add(record)
        save(items)
        record
    }

    synchronized Map getTemplate(String id) {
        if (!id || !(id ==~ /[0-9a-fA-F-]{36}/)) return null
        Map record = listTemplates().find { it.id == id }
        if (!record) return null
        File file = new File(root(), record.storedName as String)
        if (!file.canonicalPath.startsWith(root().canonicalPath + File.separator) || !file.isFile()) return null
        record + [file:file]
    }

    synchronized void deleteTemplate(String id) {
        Map item = getTemplate(id)
        if (!item) throw new IllegalArgumentException('Template not found.')
        List<Map> items = listTemplates()
        items.removeAll { it.id == id }
        save(items)
        item.file.delete()
    }

    private void save(List<Map> items) {
        File tmp = File.createTempFile('template-index-', '.json', root())
        try {
            tmp.setText(JsonOutput.prettyPrint(JsonOutput.toJson(items)), 'UTF-8')
            Files.move(tmp.toPath(), metadata().toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }
}
