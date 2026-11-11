<style>
/* Server Administration content; navigation and header come from the shared server shell. */
.pm-shell .sa-main { padding:20px 10px 30px; max-width:none; margin:0; color:var(--pm-text); font:14px system-ui,-apple-system,"Segoe UI",sans-serif; }
.pm-shell .sa-main * { box-sizing:border-box; }
.pm-shell .sa-tabs { display:flex; gap:7px; flex-wrap:wrap; margin-bottom:18px; }
.pm-shell .sa-tabs a,.pm-shell .sa-btn { border:1px solid var(--pm-border); background:var(--pm-surface); color:var(--pm-text); padding:10px 13px; border-radius:5px; text-decoration:none; cursor:pointer; font:inherit; display:inline-block; }
.pm-shell .sa-tabs a:hover,.pm-shell .sa-btn:hover,.pm-shell .sa-tabs a.active,.pm-shell .sa-primary { background:var(--pm-blue); color:#fff; border-color:var(--pm-blue); }
.pm-shell .sa-card { border:1px solid var(--pm-border); background:var(--pm-surface); border-radius:6px; margin-bottom:17px; overflow:hidden; }
.pm-shell .sa-card h2 { font-size:16px; margin:0; background:var(--pm-sidebar); padding:14px 16px; border-bottom:1px solid var(--pm-border); }
.pm-shell .sa-body { padding:16px; overflow-x:auto; }
.pm-shell .sa-table { border-collapse:collapse; width:100%; }
.pm-shell .sa-table th { text-align:left; background:var(--pm-sidebar); padding:12px; }
.pm-shell .sa-table td { padding:11px; border-top:1px solid var(--pm-border); }
.pm-shell .sa-table tr:nth-child(even) { background:color-mix(in srgb,var(--pm-surface) 75%,var(--pm-sidebar)); }
.pm-shell .sa-muted { color:var(--pm-muted); }
.pm-shell .sa-alert { padding:12px; margin-bottom:14px; background:#294e42; border:1px solid #4b9a78; }
.pm-shell .sa-error { background:#5a3333; border-color:#a85a5a; }
.pm-shell .sa-main input,.pm-shell .sa-main select,.pm-shell .sa-main textarea { background:var(--pm-bg); color:var(--pm-text); padding:8px; border:1px solid var(--pm-border); border-radius:4px; max-width:100%; }
.pm-shell .sa-main input[type="checkbox"],.pm-shell .sa-main input[type="radio"] { padding:0; }
.pm-shell .sa-main a:not(.sa-btn):not(.active) { color:#82bff5; }
.pm-shell[data-pm-theme="light"] .sa-main a:not(.sa-btn):not(.active) { color:#155a99; }
.pm-shell .sa-file { background:var(--pm-bg); color:var(--pm-text); }
@media(max-width:700px) { .pm-shell .sa-main { padding:12px 0; } .pm-shell .sa-body { padding:10px; } }

/* PM_ATTACHMENT_PAGES_V2 */
.sa-tag-groups{display:flex;flex-wrap:wrap;gap:7px;margin:8px 0 18px}
.sa-tag-layout{display:grid;grid-template-columns:minmax(180px,25%) 1fr;gap:22px}
.sa-tag-item{display:block;padding:9px 12px;border-bottom:1px solid var(--pm-border,#c8d4df);color:inherit;text-decoration:none}
.sa-tag-item:hover,.sa-tag-item.sa-selected{background:var(--pm-blue,#1963a4);color:white}
.sa-pager{display:flex;gap:8px;margin-top:16px}
@media(max-width:750px){.sa-tag-layout{grid-template-columns:1fr}}

/* PM_TERMS_TREE_V3 */
.sa-term-filter{display:flex;gap:12px;align-items:center;margin-bottom:18px}
.sa-term-filter select,.sa-term-editor input,.sa-term-editor select{background:var(--pm-surface,#fff);color:var(--pm-text,#17232e);border:1px solid var(--pm-border,#cbd5e1);border-radius:5px;padding:9px;max-width:100%}
.sa-term-grid{display:grid;grid-template-columns:minmax(280px,1fr) minmax(280px,390px);gap:25px}
.sa-term-tree{border:1px solid var(--pm-border,#cbd5e1);border-radius:6px;overflow:auto;max-height:650px}
.sa-term-node{display:flex;align-items:center;gap:8px;padding:7px 10px 7px calc(10px + var(--depth)*22px);border-bottom:1px solid var(--pm-border,#cbd5e1)}
.sa-term-branch{color:var(--pm-blue,#1963a4)}.sa-term-edit{margin-left:auto}
.sa-term-editor form{display:flex;flex-direction:column;gap:9px;margin-bottom:18px}.sa-term-editor .sa-btn{align-self:flex-start}
.sa-error{padding:10px;border:1px solid #b91c1c;color:#b91c1c}.sa-notice{padding:10px;border:1px solid #15803d}
@media(max-width:800px){.sa-term-grid{grid-template-columns:1fr}}
</style>
<style>
/* PM_ATTACHMENT_ADMIN_STEP1 */
.pm-shell .sa-tagged-nav { position:relative; display:inline-block; }
.pm-shell .sa-tagged-nav > summary { list-style:none; border:1px solid var(--pm-border); background:var(--pm-surface); color:var(--pm-text); padding:10px 13px; border-radius:5px; cursor:pointer; font:inherit; }
.pm-shell .sa-tagged-nav > summary::-webkit-details-marker { display:none; }
.pm-shell .sa-tagged-nav > summary:hover, .pm-shell .sa-tagged-nav[open] > summary { background:var(--pm-blue); color:white; border-color:var(--pm-blue); }
.pm-shell .sa-tagged-menu { position:absolute; z-index:40; top:100%; left:0; min-width:220px; padding:5px; border:1px solid var(--pm-border); border-radius:5px; background:var(--pm-surface); box-shadow:0 6px 18px rgba(0,0,0,.2); }
.pm-shell .sa-tagged-menu a { display:block; white-space:nowrap; margin:2px; }
</style>