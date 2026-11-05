package serveradmin

import grails.gorm.transactions.Transactional
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import materials.Material
import materials.MaterialCategory
import java.nio.charset.StandardCharsets

/** JSON interchange of the Materials Library, including category hierarchy. */
class ServerAdminMaterialsService {
    byte[] exportJson() {
        Map payload = [format:'pm-materials-v1',
            categories:MaterialCategory.list(sort:'id').collect { c ->
                [name:c.name, parent:c.parent?.name, description:c.description,
                 displayOrder:c.displayOrder, active:c.active]
            },
            materials:Material.list(sort:'id').collect { m ->
                [name:m.name, category:m.category?.name,
                 generalDescription:m.generalDescription, densityKgM3:m.densityKgM3,
                 propertiesJson:m.propertiesJson, sourceFile:m.sourceFile, active:m.active]
            }]
        JsonOutput.prettyPrint(JsonOutput.toJson(payload)).getBytes(StandardCharsets.UTF_8)
    }

    @Transactional
    Map importJson(InputStream stream) {
        Object parsed = new JsonSlurper().parse(new InputStreamReader(stream, StandardCharsets.UTF_8))
        if (!(parsed instanceof Map) || parsed.format != 'pm-materials-v1' ||
            !(parsed.categories instanceof List) || !(parsed.materials instanceof List))
            throw new IllegalArgumentException('Expected a pm-materials-v1 export with categories and materials')
        if (parsed.categories.size() > 10000 || parsed.materials.size() > 100000)
            throw new IllegalArgumentException('Import exceeds record limits')
        // Validate first; a failure rolls back the transaction.
        Map<String, Map> categories = [:]
        parsed.categories.each { entry ->
            if (!(entry instanceof Map) || !entry.name?.toString()?.trim())
                throw new IllegalArgumentException('Category name is required')
            String name = entry.name.toString().trim()
            if (categories.containsKey(name)) throw new IllegalArgumentException('Duplicate category: ' + name)
            categories[name] = entry
        }
        parsed.materials.each { entry ->
            if (!(entry instanceof Map) || !entry.name?.toString()?.trim() || !categories.containsKey(entry.category?.toString()))
                throw new IllegalArgumentException('Material must have a name and an exported category')
            def props = new JsonSlurper().parseText(entry.propertiesJson?.toString() ?: '{}')
            if (!(props instanceof Map)) throw new IllegalArgumentException('Material properties must be a JSON object')
        }
        Map<String, MaterialCategory> resolved = [:]
        int remaining = categories.size()
        while (remaining > 0) {
            int progress = 0
            categories.each { String name, Map data ->
                if (resolved.containsKey(name)) return
                String parentName = data.parent?.toString()
                if (parentName && !resolved.containsKey(parentName)) return
                MaterialCategory parent = parentName ? resolved[parentName] : null
                MaterialCategory category = MaterialCategory.findByNameAndParent(name, parent)
                if (!category) category = new MaterialCategory(name:name, parent:parent)
                category.description = data.description?.toString()
                category.displayOrder = data.displayOrder == null ? 0 : Integer.parseInt(data.displayOrder.toString())
                category.active = data.active != false
                category.save(failOnError:true)
                resolved[name] = category
                progress++
            }
            if (!progress) throw new IllegalArgumentException('Category hierarchy has missing parents or a cycle')
            remaining -= progress
        }
        int imported = 0
        parsed.materials.each { Map data ->
            String name = data.name.toString().trim()
            MaterialCategory category = resolved[data.category.toString()]
            Material material = Material.findByNameAndCategory(name, category)
            if (!material && data.sourceFile) material = Material.findBySourceFile(data.sourceFile.toString())
            if (!material) material = new Material()
            material.name = name
            material.category = category
            material.generalDescription = data.generalDescription?.toString()
            material.densityKgM3 = data.densityKgM3 == null || data.densityKgM3.toString() == '' ? null : new BigDecimal(data.densityKgM3.toString())
            material.propertiesJson = data.propertiesJson?.toString() ?: '{}'
            material.sourceFile = data.sourceFile?.toString()
            material.active = data.active != false
            material.save(failOnError:true)
            imported++
        }
        Material.withSession { it.flush() }
        [categories:resolved.size(), materials:imported]
    }
}
