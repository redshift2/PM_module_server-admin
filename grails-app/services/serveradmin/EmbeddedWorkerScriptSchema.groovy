package serveradmin

import groovy.json.JsonSlurper

/** Reads a declarative JSON string literal from Python source; never runs Python. */
class EmbeddedWorkerScriptSchema {
    static Map extract(byte[] bytes) {
        String source = new String(bytes, 'UTF-8')
        // A dedicated top-level assignment with a triple-quoted JSON string.
        // This is deliberately not a general Python expression evaluator.
        def matcher = (source =~ /(?m)^SCRIPT_SCHEMA_JSON\s*=\s*(?:r)?("""|\'\'\')/)
        if (!matcher.find()) return null
        String delimiter = matcher.group(1)
        int start = matcher.end()
        int end = source.indexOf(delimiter, start)
        if (end < 0) throw new IllegalArgumentException('Unterminated SCRIPT_SCHEMA_JSON')
        String raw = source.substring(start, end)
        if (raw.length() > 8000) throw new IllegalArgumentException('Embedded schema exceeds 8000 characters')
        def parsed = new JsonSlurper().parseText(raw)
        return WorkerScriptCatalogService.validateSchema(parsed)
    }
}
