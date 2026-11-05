package serveradmin

import grails.gorm.transactions.Transactional
import plm.PlmFreeCadPart
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ServerAdminMaintenanceService {
    private static final List<String> FIELDS = [
        'id','originalName','label','description1','description2','description3',
        'material','supplier','surfaceTreatment','dcn','cost','weight',
        'majorVersion','minorVersion','lifecycleState'
    ]

    private static String csvCell(Object input) {
        String s = input == null ? '' : input.toString()
        // Prevent spreadsheet formula execution when opening exports.
        if (s && '=+-@'.contains(s.substring(0,1))) s = "'" + s
        '"' + s.replace('"','""') + '"'
    }

    byte[] exportPartsCsv() {
        StringBuilder b = new StringBuilder(FIELDS.join(',')).append('\r\n')
        PlmFreeCadPart.list(sort:'id', order:'asc').each { part ->
            b.append(FIELDS.collect { csvCell(part."${it}") }.join(',')).append('\r\n')
        }
        b.toString().getBytes(StandardCharsets.UTF_8)
    }

    byte[] metadataBackupZip() {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ZipOutputStream zip = new ZipOutputStream(out)
        try {
            zip.putNextEntry(new ZipEntry('plm-parts-metadata.csv'))
            zip.write(exportPartsCsv())
            zip.closeEntry()
            zip.putNextEntry(new ZipEntry('README.txt'))
            zip.write(('PLM metadata snapshot. This is NOT a complete server backup.\n' +
                'It excludes H2 database files, CAD binaries, attachment content and user credentials.\n' +
                'Restore those with a separate offline database and storage backup.\n').getBytes(StandardCharsets.UTF_8))
            zip.closeEntry()
        } finally { zip.close() }
        out.toByteArray()
    }

    // Small RFC4180 CSV parser: quoted fields, escaped quotes, commas and newlines.
    private static List<List<String>> parseCsv(String input) {
        List<List<String>> rows = []
        List<String> row = []
        StringBuilder cell = new StringBuilder()
        boolean quoted = false
        for (int i=0; i<input.length(); i++) {
            char c = input.charAt(i)
            if (c == '"'.charAt(0)) {
                if (quoted && i+1<input.length() && input.charAt(i+1) == '"'.charAt(0)) { cell.append('"'); i++ }
                else quoted = !quoted
            } else if (c == ','.charAt(0) && !quoted) {
                row << cell.toString(); cell.setLength(0)
            } else if ((c == '\n'.charAt(0) || c == '\r'.charAt(0)) && !quoted) {
                if (c == '\r'.charAt(0) && i+1<input.length() && input.charAt(i+1) == '\n'.charAt(0)) i++
                row << cell.toString(); cell.setLength(0)
                if (row.any { it }) rows << row
                row = []
            } else cell.append(c)
        }
        if (quoted) throw new IllegalArgumentException('Unclosed quoted CSV field')
        row << cell.toString()
        if (row.any { it }) rows << row
        rows
    }

    @Transactional
    Map importMetadataCsv(InputStream stream) {
        String input = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceFirst('^\uFEFF','')
        List<List<String>> rows = parseCsv(input)
        if (!rows) throw new IllegalArgumentException('CSV is empty')
        List<String> headers = rows.remove(0).collect { it.trim() }
        if (!headers.contains('id')) throw new IllegalArgumentException('CSV must have an id column')
        List<String> allowed = ['label','description1','description2','description3','material','supplier','surfaceTreatment','dcn']
        int updated = 0, skipped = 0
        List<String> errors = []
        rows.eachWithIndex { List<String> cells, int i ->
            try {
                Map<String,String> data = [:]
                headers.eachWithIndex { String h, int col -> data[h] = col < cells.size() ? cells[col] : '' }
                Long id = data.id?.trim()?.toLong()
                PlmFreeCadPart part = id ? PlmFreeCadPart.get(id) : null
                if (!part) { skipped++; errors << "Row ${i+2}: unknown part id ${data.id}"; return }
                allowed.each { field ->
                    if (headers.contains(field)) {
                        String value = data[field]?.trim()
                        if (value?.startsWith("'")) value = value.substring(1)
                        if (value?.length() > (field.startsWith('description') ? 1000 : 255))
                            throw new IllegalArgumentException("${field} too long")
                        part."${field}" = value ?: null
                    }
                }
                part.save(failOnError:true, flush:true)
                updated++
            } catch (Exception ex) { skipped++; errors << "Row ${i+2}: ${ex.message}" }
        }
        [updated:updated, skipped:skipped, errors:errors]
    }
}
