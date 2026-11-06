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
<a href="${request.contextPath}/serverAdmin/events" class="${tab=='events'?'active':''}">Event Manager</a>
<a href="${request.contextPath}/serverAdmin/emailServer" class="${tab=='email'?'active':''}">Email Server</a>
</g:if>
</nav>
<g:if test="${flash.message}"><div class="sa-alert">${flash.message.encodeAsHTML()}</div></g:if>
<g:if test="${flash.error}"><div class="sa-alert sa-error">${flash.error.encodeAsHTML()}</div></g:if>
<g:if test="${tab=='info'}"><section class="sa-card"><h2>Installed Grails Modules</h2><div class="sa-body">
<table class="sa-table"><thead><tr><th>Module</th><th>Version</th><th>Type</th></tr></thead><tbody>
<g:each in="${modules}" var="module"><tr><td>${module.name?.encodeAsHTML()}</td><td>${module.version?.encodeAsHTML()}</td><td>${module.type?.encodeAsHTML()}</td></tr></g:each>
<g:if test="${!modules}"><tr><td colspan="3">Module information is unavailable.</td></tr></g:if>
</tbody></table><p class="sa-muted">Application modules are detected from deployed classes; their versions are shown when build metadata is available.</p></div></section></g:if>
<g:elseif test="${tab=='jobs'}"><section class="sa-card"><h2>Job Queue</h2><div class="sa-body">
<p class="sa-muted">Submitted jobs are stored as QUEUED. A worker/dispatcher is not implemented yet.</p>
<g:if test="${isAdmin}"><form method="post" action="${request.contextPath}/serverAdmin/submitJob">
<select name="jobType" required><option value="MAINTENANCE_REVIEW">Maintenance Review</option><option value="EVENT_REPORT">Event Report</option></select>
<button class="sa-btn sa-primary" type="submit">Submit Job</button></form></g:if>
<g:if test="${isAdmin}"><h3>Submit remote-worker package</h3>
<form method="post" enctype="multipart/form-data" action="${request.contextPath}/serverAdmin/submitPackageJob">
<input type="file" name="file" accept=".zip,application/zip" required />
<button class="sa-btn sa-primary" type="submit">Queue ZIP Package</button></form>
<p class="sa-muted">ZIP is stored for future worker delivery; it is not executed or unpacked.</p></g:if>
<table class="sa-table"><thead><tr><th>ID</th><th>Type</th><th>Status</th><th>Submitted by</th><th>Package</th><th>Created</th></tr></thead><tbody>
<g:each in="${jobs}" var="job"><tr><td>${job.id}</td><td>${job.jobType?.encodeAsHTML()}</td><td>${job.status?.encodeAsHTML()}</td><td>${job.submittedBy?.encodeAsHTML()}</td><td>${job.packageFileName?.encodeAsHTML() ?: "—"}</td><td>${job.dateCreated}</td></tr></g:each>
</tbody></table></div></section></g:elseif>
<g:elseif test="${tab=='events'}"><section class="sa-card"><h2>Event Manager</h2><div class="sa-body">
<p class="sa-muted">Audit events reported by PLM, Materials, and future DCN modules. Modules must call ServerEventService.record(...) after successful changes.</p>
<h3>Create event action rule</h3>
<form method="post" action="${request.contextPath}/serverAdmin/createEventRule">
<input name="name" placeholder="Rule name" maxlength="120" required />
<select name="eventType"><g:each in="${serveradmin.ServerEventService.KNOWN_TYPES.toList().sort()}" var="eventType"><option value="${eventType}">${eventType}</option></g:each></select>
<select name="actionType"><option value="LOG">Log</option><option value="EMAIL">Email</option><option value="SUBMIT_JOB">Submit Job (future worker)</option></select>
<input name="destination" placeholder="Email address or job type" maxlength="255" />
<button class="sa-btn sa-primary" type="submit">Add Rule</button></form>
<h3>Rules</h3><table class="sa-table"><thead><tr><th>Name</th><th>Event</th><th>Action</th><th>Destination</th></tr></thead><tbody>
<g:each in="${rules}" var="rule"><tr><td>${rule.name?.encodeAsHTML()}</td><td>${rule.eventType?.encodeAsHTML()}</td><td>${rule.actionType?.encodeAsHTML()}</td><td>${rule.destination?.encodeAsHTML()}</td></tr></g:each>
</tbody></table><h3>Recent events</h3>
<table class="sa-table"><thead><tr><th>Time</th><th>Module</th><th>Event</th><th>Entity</th><th>Actor</th><th>Message</th></tr></thead><tbody>
<g:each in="${events}" var="evt"><tr><td>${evt.dateCreated}</td><td>${evt.sourceModule?.encodeAsHTML()}</td><td>${evt.eventType?.encodeAsHTML()}</td><td>${evt.entityType?.encodeAsHTML()} ${evt.entityId?.encodeAsHTML()}</td><td>${evt.actor?.encodeAsHTML()}</td><td>${evt.message?.encodeAsHTML()}</td></tr></g:each>
</tbody></table><h3>Pending actions</h3><table class="sa-table"><thead><tr><th>Event</th><th>Action</th><th>Status</th><th>Destination</th><th>Dispatch</th></tr></thead><tbody>
<g:each in="${actions}" var="action"><tr><td>${action.event?.eventType?.encodeAsHTML()}</td><td>${action.actionType?.encodeAsHTML()}</td><td>${action.status?.encodeAsHTML()}</td><td>${action.destination?.encodeAsHTML()}</td><td>
<g:if test="${action.actionType == 'EMAIL' && action.status == 'QUEUED'}"><form method="post" action="${request.contextPath}/serverAdmin/sendEventEmail"><input type="hidden" name="id" value="${action.id}"/><button class="sa-btn" type="submit">Send Email</button></form></g:if></td></tr></g:each>
</tbody></table></div></section></g:elseif>
<g:elseif test="${tab=='email'}"><section class="sa-card"><h2>Email Server Connection</h2><div class="sa-body">
<p>SMTP configuration is read from server environment variables. Passwords are never displayed.</p>
<table class="sa-table"><tr><th>Connection</th><td>${smtpConfigured ? 'Configured (not connection-tested)' : 'Not configured'}</td></tr><tr><th>Host</th><td>${smtpHost?.encodeAsHTML()}</td></tr><tr><th>Port</th><td>${smtpPort?.encodeAsHTML()}</td></tr><tr><th>From</th><td>${smtpFrom?.encodeAsHTML()}</td></tr></table>
<p class="sa-muted">Set SERVER_ADMIN_SMTP_HOST, SERVER_ADMIN_SMTP_PORT, SERVER_ADMIN_SMTP_FROM, SERVER_ADMIN_SMTP_USERNAME, SERVER_ADMIN_SMTP_PASSWORD and restart. SMTP transport supports STARTTLS and authentication.</p>
<h3>Send test email</h3><form method="post" action="${request.contextPath}/serverAdmin/testEmail"><input type="email" name="to" placeholder="Recipient address" required /><button type="submit" class="sa-btn sa-primary">Send Test Email</button></form>
</div></section></g:elseif>
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