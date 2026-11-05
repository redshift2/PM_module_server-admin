<!doctype html>
<html><head><title>Server Administration</title><g:render template="theme"/></head><body>
<header class="sa-header"><h1>Server Administration</h1><a class="sa-btn" href="${request.contextPath}/">⌂ Main Menu</a></header>
<main class="sa-main">
<nav class="sa-tabs" aria-label="Server administration tabs">
<a href="${request.contextPath}/serverAdmin/serverInfo" class="${tab=='info'?'active':''}">Server Info</a>
<a href="${request.contextPath}/serverAdmin/jobQueue" class="${tab=='jobs'?'active':''}">Job Queue</a>
<g:if test="${isAdmin}">
<a href="${request.contextPath}/serverAdmin/authorization" class="${tab=='auth'?'active':''}">Authorization</a>
<a href="${request.contextPath}/serverAdmin/maintenance" class="${tab=='maintenance'?'active':''}">Server Maintenance</a>
</g:if>
</nav>
<g:if test="${flash.message}"><div class="sa-alert">${flash.message.encodeAsHTML()}</div></g:if>
<g:if test="${flash.error}"><div class="sa-alert sa-error">${flash.error.encodeAsHTML()}</div></g:if>
<g:if test="${tab=='info'}"><section class="sa-card"><h2>Installed Grails Modules</h2><div class="sa-body">
<table class="sa-table"><thead><tr><th>Module</th><th>Version</th><th>Type</th></tr></thead><tbody>
<g:each in="${modules}" var="module"><tr><td>${module.name?.encodeAsHTML()}</td><td>${module.version?.encodeAsHTML()}</td><td>${module.type?.encodeAsHTML()}</td></tr></g:each>
<g:if test="${!modules}"><tr><td colspan="3">Module information is unavailable.</td></tr></g:if>
</tbody></table><p class="sa-muted">Application modules are detected from deployed classes; their versions are shown when build metadata is available.</p></div></section></g:if>
<g:elseif test="${tab=='jobs'}"><section class="sa-card"><h2>Job Queue</h2><div class="sa-body sa-muted">Job queue integration will be added later.</div></section></g:elseif>
<g:elseif test="${tab=='auth'}"><section class="sa-card"><h2>Authorization</h2><div class="sa-body sa-muted">OAuth2 authorization integration will be added later.</div></section></g:elseif>
<g:elseif test="${tab=='maintenance'}">
<section class="sa-card"><h2>Export and Metadata Snapshot</h2><div class="sa-body">
<p>Export PLM part metadata as CSV, or download a ZIP containing the metadata CSV and a backup scope notice.</p>
<a href="${request.contextPath}/serverAdmin/exportParts" class="sa-btn sa-primary">Export Parts Metadata (CSV)</a>
<a href="${request.contextPath}/serverAdmin/backup" class="sa-btn">Download Metadata Snapshot (ZIP)</a>
<p class="sa-muted">These are metadata exports, not complete database/CAD/attachment backups.</p></div></section>
<section class="sa-card"><h2>Materials Library Import / Export</h2><div class="sa-body">
<p>Export all material records and category hierarchy as JSON. Import a previously exported file to create or update matching materials and categories.</p>
<a class="sa-btn sa-primary" href="${request.contextPath}/serverAdmin/exportMaterials">Export Materials (JSON)</a>
<form action="${request.contextPath}/serverAdmin/importMaterials" method="post" enctype="multipart/form-data" style="margin-top:16px">
<input class="sa-file" type="file" name="file" accept=".json,application/json" required />
<button class="sa-btn sa-primary" type="submit">Import Materials (JSON)</button>
</form><p class="sa-muted">Imports merge by category name/parent and material name/category. Back up the library before importing. This does not import FreeCAD .FCMat files.</p>
</div></section>
<section class="sa-card"><h2>Bulk Import Part Metadata</h2><div class="sa-body">
<p>Upload a CSV with an <code>id</code> column to update existing parts in bulk. Supports label, descriptions, material, supplier, surface treatment, and DCN. Does not modify CAD content, lifecycle, version, or create new parts.</p>
<form action="${request.contextPath}/serverAdmin/importParts" method="post" enctype="multipart/form-data">
<input class="sa-file" type="file" name="file" accept=".csv,text/csv" required />
<button class="sa-btn sa-primary" type="submit">Import Metadata CSV</button>
</form>
<p class="sa-muted">New CAD part ingestion requires the FreeCAD protobuf upload protocol and is not implemented in this first version.</p></div></section>
</g:elseif>
</main></body></html>