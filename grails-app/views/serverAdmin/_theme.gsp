<style>
html,body{background:#22272e;color:#e9eef5;margin:0;font:14px system-ui,-apple-system,"Segoe UI",sans-serif}
.sa-header{background:#195d8f;color:white;padding:20px 22px;display:flex;justify-content:space-between;align-items:center;gap:12px}
.sa-header h1{font-size:23px;margin:0}.sa-main{padding:22px;max-width:1600px;margin:auto}
.sa-tabs{display:flex;gap:7px;flex-wrap:wrap;margin-bottom:18px}.sa-tabs a,.sa-btn{border:1px solid #607182;background:#414c59;color:#a9d6ff;padding:10px 13px;border-radius:4px;text-decoration:none;cursor:pointer;font:inherit}
.sa-tabs a.active,.sa-primary{background:#2079b2;color:white;border-color:#298cc9}
.sa-card{border:1px solid #4b5867;background:#2a3038;border-radius:5px;margin-bottom:17px;overflow:hidden}
.sa-card h2{font-size:16px;margin:0;background:#353e48;padding:14px 16px;border-bottom:1px solid #4b5867}.sa-body{padding:16px}
.sa-table{border-collapse:collapse;width:100%}.sa-table th{text-align:left;background:#3a4653;padding:12px}.sa-table td{padding:11px;border-top:1px solid #45515f}.sa-table tr:nth-child(even){background:#303943}
.sa-muted{color:#bbc8d4}.sa-alert{padding:12px;margin-bottom:14px;background:#294e42;border:1px solid #4b9a78}.sa-error{background:#5a3333;border-color:#a85a5a}
.sa-file{background:#303943;color:#fff;padding:8px;border:1px solid #607182}
</style>
<style>
/* Same host-layout title fix used by Materials and PLM: cover the host title
   with the module header, and reserve the header's height in page flow. */
html, body { margin:0 !important; padding:0 !important; }
.sa-header {
  position:fixed !important;
  top:0 !important; left:0 !important; right:0 !important;
  z-index:1000 !important;
  margin:0 !important;
  box-sizing:border-box !important;
  min-height:76px;
}
.sa-main { padding-top:calc(76px + 22px) !important; }
@media(max-width:700px) { .sa-header { min-height:76px; } }
</style>
<script>
(function(){
  function syncHeader(){
    var header=document.querySelector('.sa-header');
    var main=document.querySelector('.sa-main');
    if(header && main){ main.style.paddingTop=(header.offsetHeight+22)+'px'; }
  }
  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',syncHeader);
  else syncHeader();
  window.addEventListener('resize',syncHeader);
})();
</script>