package com.oaurumo.davsrv.server

import android.text.TextUtils
import android.view.View
import com.oaurumo.davsrv.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight, dependency-free web UI served for directory GETs so the server is
 * usable from any browser on the LAN (browse, download, upload, new folder).
 * All copy comes from string resources, so the page follows the app language.
 */
object BrowserPage {

    private val dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }

    fun render(node: DavNode, segments: List<String>, includeHidden: Boolean, lockManager: LockManager): String {
        val children = runCatching { node.list() }
            .getOrDefault(emptyList())
            .filter { includeHidden || !Names.isHidden(it.name) }

        val locale = ServerText.locale()
        val lang = locale.toLanguageTag()
        val rtl = TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL

        val title = if (segments.isEmpty()) "DavSrv" else segments.last()
        val sb = StringBuilder(8192)
        sb.append("<!DOCTYPE html><html lang=\"").append(esc(lang)).append("\" dir=\"")
            .append(if (rtl) "rtl" else "ltr").append("\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(esc(title)).append(" · DavSrv</title>")
        sb.append("<style>").append(CSS).append("</style></head><body>")
        sb.append("<div class=\"wrap\">")
        sb.append("<header><div class=\"brand\">")
        sb.append("<svg viewBox=\"0 0 24 24\" width=\"22\" height=\"22\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.7\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M6.5 18a4 4 0 0 1-.3-8A5.5 5.5 0 0 1 17 9.6 3.8 3.8 0 0 1 17.5 18Z\"/><path d=\"M9 13.5h6M12 10.5v6\"/></svg>")
        sb.append("<span>DavSrv</span></div><div class=\"meta\">WebDAV</div></header>")

        sb.append("<nav class=\"crumb\">")
        sb.append("<a href=\"/\">").append(esc(ServerText.s(R.string.web_root))).append("</a>")
        var acc = ""
        segments.forEach { seg ->
            acc += "/" + Xml.encodeSegment(seg)
            sb.append("<span class=\"sep\">/</span><a href=\"").append(acc).append("/\">")
                .append(esc(seg)).append("</a>")
        }
        sb.append("</nav>")

        sb.append("<div class=\"bar\">")
        sb.append("<label class=\"btn primary\">").append(esc(ServerText.s(R.string.web_upload)))
        sb.append("<input type=\"file\" id=\"file\" multiple hidden></label>")
        sb.append("<button class=\"btn\" id=\"mkdir\">").append(esc(ServerText.s(R.string.web_new_folder)))
        sb.append("</button>")
        sb.append("<span class=\"hint\" id=\"status\">")
            .append(esc(ServerText.s(R.string.web_items, children.size)))
            .append("</span>")
        sb.append("</div><div id=\"prog\" class=\"prog\"></div>")

        sb.append("<ul class=\"list\">")
        if (segments.isNotEmpty()) {
            val parent = Xml.href(segments.dropLast(1), true)
            sb.append("<li class=\"row\"><a class=\"name\" href=\"").append(parent).append("\">")
            sb.append("<span class=\"ico dir\">↰</span><span class=\"txt\">")
                .append(esc(ServerText.s(R.string.web_up))).append("</span></a></li>")
        }
        children.forEach { child ->
            val href = Xml.href(segments + child.name, child.isDirectory)
            val cls = if (child.isDirectory) "ico dir" else "ico"
            sb.append("<li class=\"row\"><a class=\"name\" href=\"").append(esc(href)).append("\"")
            if (!child.isDirectory) sb.append(" download")
            sb.append("><span class=\"").append(cls).append("\">")
            sb.append(if (child.isDirectory) "▸" else iconFor(child.mimeType))
            sb.append("</span><span class=\"txt\">").append(esc(child.name)).append("</span>")
            if (child.isDirectory) sb.append("<span class=\"slash\">/</span>")
            sb.append("</a><span class=\"size\">")
            sb.append(
                if (child.isDirectory) esc(ServerText.s(R.string.web_dir_items, child.childCount()))
                else humanSize(child.size)
            )
            sb.append("</span><span class=\"time\">")
                .append(dateFmt.get()!!.format(Date(child.lastModified)))
                .append("</span></li>")
        }
        if (children.isEmpty()) {
            sb.append("<li class=\"empty\">").append(esc(ServerText.s(R.string.web_empty))).append("</li>")
        }
        sb.append("</ul>")

        val space = Storage.spaceOf(node)
        sb.append("<footer><span>").append(esc(ServerText.s(R.string.web_footer))).append("</span>")
        if (space != null) {
            sb.append("<span>")
                .append(esc(ServerText.s(R.string.web_free, humanSize(space.first), humanSize(space.second))))
                .append("</span>")
        }
        sb.append("</footer>")
        sb.append("</div><script>").append(script(segments)).append("</script></body></html>")
        return sb.toString()
    }

    private fun iconFor(mime: String): String = when {
        mime.startsWith("image/") -> "▣"
        mime.startsWith("video/") -> "▶"
        mime.startsWith("audio/") -> "♪"
        mime.startsWith("text/") -> "≡"
        mime.contains("pdf") -> "▤"
        mime.contains("zip") || mime.contains("compressed") || mime.contains("rar") -> "◫"
        else -> "•"
    }

    private fun esc(value: String): String = Xml.escape(value)

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format(Locale.US, if (value >= 10) "%.0f %s" else "%.1f %s", value, units[i])
    }

    /** Escapes text for a single-quoted JavaScript literal. */
    private fun jsEsc(text: String): String = text
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "")
        .replace("\n", "\\n")

    /** Message template with {0}/{1} placeholders, safe to inline in JS. */
    private fun tpl(resId: Int, vararg args: String): String = jsEsc(ServerText.s(resId, *args))

    private fun script(segments: List<String>): String {
        val base = "/" + segments.joinToString("/") { Xml.encodeSegment(it) }.let {
            if (it.isBlank()) "" else it.trimStart('/') + if (segments.isEmpty()) "" else "/"
        }
        val safeBase = if (segments.isEmpty()) "/" else base
        val uploading = tpl(R.string.web_uploading, "{0}")
        val uploadFailed = tpl(R.string.web_upload_failed, "{0}", "{1}")
        val mkdirPrompt = tpl(R.string.web_mkdir_prompt)
        val createFailed = tpl(R.string.web_create_failed, "{0}")
        val downloading = tpl(R.string.web_downloading)
        return SCRIPT
            .replace("__UPLOADING__", uploading)
            .replace("__UPLOAD_FAILED__", uploadFailed)
            .replace("__MKDIR_PROMPT__", mkdirPrompt)
            .replace("__CREATE_FAILED__", createFailed)
            .replace("__DOWNLOADING__", downloading)
            .replace("__BASE__", safeBase)
    }

    private val SCRIPT = """
        const base = "__BASE__";
        const statusEl = document.getElementById('status');
        const progEl = document.getElementById('prog');
        const MSG_UP = "__UPLOADING__";
        const MSG_UP_FAIL = "__UPLOAD_FAILED__";
        const MSG_MKDIR = "__MKDIR_PROMPT__";
        const MSG_CREATE_FAIL = "__CREATE_FAILED__";
        const MSG_DOWNLOAD = "__DOWNLOADING__";
        function say(t){ statusEl.textContent = t; }
        function f(s, a, b){ return s.replace('{0}', a).replace('{1}', b); }
        document.getElementById('file').addEventListener('change', async (e) => {
          const files = Array.from(e.target.files || []);
          for (const f1 of files) {
            try {
              say(f(MSG_UP, f1.name));
              const res = await fetch(base + encodeURIComponent(f1.name), {
                method: 'PUT', body: f1,
                headers: {'Content-Type': f1.type || 'application/octet-stream'}
              });
              if (!res.ok) throw new Error(res.status);
            } catch (err) { alert(f(MSG_UP_FAIL, f1.name, err.message)); }
          }
          location.reload();
        });
        document.getElementById('mkdir').addEventListener('click', async () => {
          const name = prompt(MSG_MKDIR);
          if (!name) return;
          try {
            const res = await fetch(base + encodeURIComponent(name), { method: 'MKCOL' });
            if (!res.ok && res.status !== 405) throw new Error(res.status);
            location.reload();
          } catch (err) { alert(f(MSG_CREATE_FAIL, err.message)); }
        });
        document.querySelectorAll('a.name').forEach(a => {
          a.addEventListener('click', () => { if (a.hasAttribute('download')) say(MSG_DOWNLOAD); });
        });
    """.trimIndent()

    private const val CSS = """
        :root{--bg:#f5f8f7;--card:#fff;--ink:#15201e;--muted:#6b7f7b;--teal:#00897b;--line:#e2ebe9}
        @media(prefers-color-scheme:dark){:root{--bg:#0e1514;--card:#16211f;--ink:#e6f0ee;--muted:#8ea5a1;--teal:#4dd0c4;--line:#233330}}
        *{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 -apple-system,"Segoe UI",Roboto,"Noto Sans SC",sans-serif}
        .wrap{max-width:880px;margin:0 auto;padding:22px 16px 40px}
        header{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px}
        .brand{display:flex;gap:9px;align-items:center;font-weight:650;font-size:19px;color:var(--teal)}
        .meta{font-size:12px;color:var(--muted);letter-spacing:.08em;text-transform:uppercase}
        .crumb{display:flex;flex-wrap:wrap;gap:4px;align-items:center;font-size:13px;margin-bottom:14px;color:var(--muted)}
        .crumb a{color:var(--muted);text-decoration:none;padding:3px 7px;border-radius:7px}
        .crumb a:hover{background:var(--card);color:var(--teal)}
        .crumb .sep{opacity:.45}
        .bar{display:flex;gap:8px;align-items:center;margin-bottom:10px;flex-wrap:wrap}
        .btn{font:inherit;font-size:13px;padding:8px 14px;border-radius:10px;border:1px solid var(--line);background:var(--card);color:var(--ink);cursor:pointer}
        .btn:hover{border-color:var(--teal)}
        .btn.primary{background:var(--teal);border-color:var(--teal);color:#fff}
        .hint{font-size:12px;color:var(--muted);margin-inline-start:auto}
        .prog{font-size:12px;color:var(--teal);height:16px}
        .list{list-style:none;margin:6px 0 0;padding:0;background:var(--card);border:1px solid var(--line);border-radius:14px;overflow:hidden}
        .row{display:grid;grid-template-columns:1fr 84px 128px;gap:10px;align-items:center;padding:11px 14px;border-bottom:1px solid var(--line)}
        .row:last-child{border-bottom:0}
        .row:hover{background:rgba(0,137,123,.05)}
        .name{display:flex;align-items:center;gap:10px;text-decoration:none;color:var(--ink);min-width:0}
        .txt{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .ico{width:22px;text-align:center;color:var(--teal);font-size:15px}
        .ico.dir{color:#e0a11b}
        .slash{color:var(--muted);margin-inline-start:-6px}
        .size,.time{font-size:12px;color:var(--muted);text-align:end}
        .empty{padding:26px;text-align:center;color:var(--muted);font-size:13px}
        footer{display:flex;justify-content:space-between;margin-top:16px;font-size:12px;color:var(--muted);flex-wrap:wrap;gap:6px}
        @media(max-width:560px){.row{grid-template-columns:1fr 70px}.time{display:none}}
    """
}
