package com.local.socialhub

import android.app.Activity
import android.app.PictureInPictureParams
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream

private const val MAX = 6   // phones struggle beyond this many live pages
private const val MATCH = -1
private const val WRAP = -2

private val SITES = linkedMapOf(
    "youtube" to Pair("YouTube", "https://www.youtube.com"),
    "instagram" to Pair("Instagram", "https://www.instagram.com"),
    "tiktok" to Pair("TikTok", "https://www.tiktok.com"),
    "x" to Pair("X", "https://x.com"),
    "facebook" to Pair("Facebook", "https://www.facebook.com"),
    "reddit" to Pair("Reddit", "https://www.reddit.com"),
    "linkedin" to Pair("LinkedIn", "https://www.linkedin.com"),
    "telegram" to Pair("Telegram", "https://web.telegram.org")
)

private val AD_HOSTS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
    "google-analytics.com", "adnxs.com", "criteo.com", "taboola.com", "outbrain.com",
    "rubiconproject.com", "pubmatic.com", "moatads.com", "scorecardresearch.com",
    "amazon-adsystem.com", "ads.twitter.com", "analytics.twitter.com", "ads-api.twitter.com",
    "ads.tiktok.com", "analytics.tiktok.com", "ads.youtube.com", "an.facebook.com",
    "ads.linkedin.com", "adsrvr.org", "advertising.com", "smartadserver.com"
)

// Skips or fast-forwards YouTube's own in-player ads.
private val YT_SKIP = """
(function(){ if (window.__hubSkip) return; var muted = null;
  window.__hubSkip = setInterval(function(){
    if (window.__hubAdblock === false) return;
    var p = document.querySelector('.html5-video-player.ad-showing');
    var v = document.querySelector('video');
    if (p && v) {
      if (muted === null) muted = v.muted;
      v.muted = true;
      if (isFinite(v.duration) && v.duration > 0) { v.playbackRate = 16; v.currentTime = v.duration; }
    } else if (muted !== null && v) { v.muted = muted; v.playbackRate = 1; muted = null; }
    document.querySelectorAll('.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-overlay-close-button').forEach(function(b){ b.click(); });
  }, 250); })();
"""

// Finds the playing (else biggest) video and returns where to tap it as "x,y,pageWidth".
private val PIP_PREP = """
(function(){
  var vs = [].slice.call(document.querySelectorAll('video')).filter(function(v){ return v.readyState > 0; });
  if (!vs.length) return 'none';
  function area(v){ var r = v.getBoundingClientRect();
    return Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0)) * Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0)); }
  vs.sort(function(a, b){ return ((b.paused === false) - (a.paused === false)) || area(b) - area(a); });
  var v = vs[0];
  if (area(v) <= 0) { try { v.scrollIntoView({block: 'center'}); } catch (e) {} }
  if (!window.__hubPipSet) { window.__hubPipSet = true;
    document.addEventListener('click', function(e){
      if (window.__hubPip) { window.__hubPip = false; e.preventDefault(); e.stopPropagation();
        var t = window.__hubVid; var f = t.requestFullscreen || t.webkitRequestFullscreen || t.webkitEnterFullscreen;
        if (f) f.call(t); }
    }, true);
  }
  window.__hubVid = v; window.__hubPip = true;
  var r = v.getBoundingClientRect();
  return (r.left + r.width / 2) + ',' + (r.top + r.height / 2) + ',' + window.innerWidth;
})()
"""

class MainActivity : Activity() {

    class Win(val id: String, val name: String, val cell: FrameLayout, val web: WebView, var zoom: Int = 100)

    private val BG = Color.parseColor("#12151C")
    private val PANEL = Color.parseColor("#1B2030")
    private val LINE = Color.parseColor("#2B3248")
    private val TEXT = Color.parseColor("#E8ECF4")
    private val DIM = Color.parseColor("#8C95AD")
    private val ACCENT = Color.parseColor("#5B8CFF")

    private val wins = LinkedHashMap<String, Win>()
    private var mode = "grid"
    private var selected: String? = null
    private var adblock = true
    private var fit = true
    private var pendingPip = false
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private lateinit var ui: LinearLayout
    private lateinit var fs: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var emptyMsg: TextView
    private lateinit var toolsBar: LinearLayout
    private lateinit var chipsBar: LinearLayout
    private lateinit var urlBox: EditText
    private lateinit var zoomLbl: TextView
    private lateinit var zoomBar: SeekBar

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun cur(): Win? = selected?.let { wins[it] }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        CookieManager.getInstance().setAcceptCookie(true)

        val root = FrameLayout(this)
        root.setBackgroundColor(BG)
        ui = LinearLayout(this)
        ui.orientation = LinearLayout.VERTICAL

        toolsBar = LinearLayout(this)
        chipsBar = LinearLayout(this)
        ui.addView(scroller(toolsBar), LinearLayout.LayoutParams(MATCH, WRAP))
        ui.addView(scroller(chipsBar), LinearLayout.LayoutParams(MATCH, WRAP))

        urlBox = EditText(this).apply {
            hint = "Open any site"
            setHintTextColor(DIM); setTextColor(TEXT); textSize = 13f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, _, _ ->
                val t = v.text.toString().trim()
                if (t.isNotEmpty()) { openCustom(t); v.text = "" }
                true
            }
        }

        val zoomRow = LinearLayout(this)
        zoomRow.gravity = Gravity.CENTER_VERTICAL
        zoomRow.setPadding(dp(10), 0, dp(10), 0)
        zoomLbl = TextView(this)
        zoomLbl.setTextColor(DIM); zoomLbl.textSize = 12f
        zoomLbl.setOnClickListener { setZoom(100) }
        zoomBar = SeekBar(this)
        zoomBar.max = 150
        zoomBar.progress = 50
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) setZoom(p + 50) }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        zoomRow.addView(zoomLbl, LinearLayout.LayoutParams(dp(120), WRAP))
        zoomRow.addView(zoomBar, LinearLayout.LayoutParams(0, WRAP, 1f))
        ui.addView(zoomRow, LinearLayout.LayoutParams(MATCH, dp(34)))

        content = FrameLayout(this)
        emptyMsg = TextView(this)
        emptyMsg.text = "Pick a site above to open it.\nTap an open site to select it, long-press to close it."
        emptyMsg.setTextColor(DIM); emptyMsg.gravity = Gravity.CENTER
        content.addView(emptyMsg, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> relayout() }
        ui.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        fs = FrameLayout(this)
        fs.setBackgroundColor(Color.BLACK)
        fs.visibility = View.GONE

        root.addView(ui, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(fs, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        refresh()
    }

    // ---------- UI helpers ----------
    private fun scroller(inner: LinearLayout): HorizontalScrollView {
        inner.orientation = LinearLayout.HORIZONTAL
        inner.setPadding(dp(4), dp(2), dp(4), dp(2))
        val s = HorizontalScrollView(this)
        s.isHorizontalScrollBarEnabled = false
        s.setBackgroundColor(PANEL)
        s.addView(inner)
        return s
    }

    private fun pill(text: String, on: Boolean, longClick: (() -> Unit)? = null, click: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 12f
        b.minHeight = 0
        b.minimumHeight = 0
        b.setTextColor(if (on) TEXT else DIM)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(16).toFloat()
        bg.setColor(if (on) Color.parseColor("#335B8CFF") else Color.TRANSPARENT)
        bg.setStroke(dp(1), if (on) ACCENT else LINE)
        b.background = bg
        b.setPadding(dp(12), dp(4), dp(12), dp(4))
        val lp = LinearLayout.LayoutParams(WRAP, dp(34))
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        b.setOnClickListener { click() }
        if (longClick != null) b.setOnLongClickListener { longClick(); true }
        return b
    }

    private fun refreshBars() {
        toolsBar.removeAllViews()
        for ((k, label) in listOf("grid" to "Grid", "square" to "Square", "cols" to "Columns", "rows" to "Rows", "tabs" to "Tabs")) {
            toolsBar.addView(pill(label, mode == k) { mode = k; refresh() })
        }
        toolsBar.addView(pill("Fit", fit) { fit = !fit; applyFit(); refresh() })
        toolsBar.addView(pill("Ad block", adblock) { adblock = !adblock; syncAdblockFlag(); refresh() })
        toolsBar.addView(pill("PiP", false) { pipSelected() })

        chipsBar.removeAllViews()
        for ((id, s) in SITES) addChip(id, s.first)
        for (w in wins.values) if (w.id.startsWith("c")) addChip(w.id, w.name)
        chipsBar.addView(urlBox, LinearLayout.LayoutParams(dp(160), dp(38)))

        val w = cur()
        zoomLbl.text = (w?.name ?: "Window") + "  " + (w?.zoom ?: 100) + "%"
        zoomBar.isEnabled = w != null
        zoomBar.progress = (w?.zoom ?: 100) - 50
    }

    private fun addChip(id: String, name: String) {
        val open = wins.containsKey(id)
        chipsBar.addView(pill(name, open, longClick = { closeSite(id) }) {
            if (open) select(id) else openSite(id, name, SITES[id]?.second ?: return@pill)
        })
    }

    private fun refresh() {
        refreshBars()
        for (w in wins.values) w.cell.setBackgroundColor(if (w.id == selected) ACCENT else PANEL)
        relayout()
    }

    private fun select(id: String?) {
        if (selected == id) return
        selected = id
        refresh()
    }

    // ---------- layouts ----------
    private fun place(w: Win, x: Int, y: Int, cw: Int, ch: Int) {
        val lp = FrameLayout.LayoutParams(cw, ch)
        lp.leftMargin = x
        lp.topMargin = y
        w.cell.layoutParams = lp
        w.cell.visibility = View.VISIBLE
    }

    private fun relayout() {
        val W = content.width
        val H = content.height
        val list = wins.values.toList()
        for (w in list) w.cell.visibility = View.GONE
        emptyMsg.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (W == 0 || H == 0 || list.isEmpty()) return

        if (mode == "tabs") {
            place(cur() ?: list[0], 0, 0, W, H)
            return
        }

        val shown = list.take(MAX)
        val n = shown.size
        val portrait = H > W
        val (cols, rows) = when (mode) {
            "cols" -> Pair(n, 1)
            "rows" -> Pair(1, n)
            else -> if (portrait) {
                val c = if (n <= 1) 1 else 2
                Pair(c, (n + c - 1) / c)
            } else {
                val r = if (n <= 3) 1 else 2
                Pair((n + r - 1) / r, r)
            }
        }
        val gap = dp(4)
        var cw = (W - gap * (cols + 1)) / cols
        var ch = (H - gap * (rows + 1)) / rows
        var ox = gap
        var oy = gap
        if (mode == "square") {
            val s = minOf(cw, ch)
            cw = s
            ch = s
            ox = (W - (s * cols + gap * (cols - 1))) / 2
            oy = (H - (s * rows + gap * (rows - 1))) / 2
        }
        shown.forEachIndexed { i, w ->
            place(w, ox + (i % cols) * (cw + gap), oy + (i / cols) * (ch + gap), cw, ch)
        }
    }

    // ---------- windows ----------
    private fun isAd(u: Uri): Boolean {
        val host = u.host ?: return false
        if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val path = u.path ?: ""
        return host.endsWith("youtube.com") &&
            (path.startsWith("/pagead/") || path.startsWith("/api/stats/ads") || path.contains("get_midroll"))
    }

    private val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (adblock && isAd(request.url)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val sc = request.url.scheme ?: return true
            return sc != "http" && sc != "https"
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != null && url.contains("youtube.com")) {
                view.evaluateJavascript("window.__hubAdblock=" + adblock + ";" + YT_SKIP, null)
            }
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) { showFullscreen(view, callback) }
        override fun onHideCustomView() { hideFullscreen() }
        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun configure(web: WebView) {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = fit
        s.loadWithOverviewMode = fit
        // Look like a normal mobile Chrome instead of an embedded WebView (helps Google sign-in).
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.setBackgroundColor(BG)
        web.webViewClient = client
        web.webChromeClient = chrome
    }

    private fun openSite(id: String, name: String, url: String) {
        val web = WebView(this)
        configure(web)
        val cell = FrameLayout(this)
        cell.setPadding(dp(2), dp(2), dp(2), dp(2))
        cell.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        web.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) select(id)
            false
        }
        content.addView(cell)
        wins[id] = Win(id, name, cell, web)
        selected = id
        web.loadUrl(url)
        refresh()
    }

    private fun openCustom(raw: String) {
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        val host = Uri.parse(url).host
        if (host == null) { toast("That address looks wrong"); return }
        openSite("c" + System.currentTimeMillis(), host.removePrefix("www."), url)
    }

    private fun closeSite(id: String) {
        val w = wins.remove(id) ?: return
        content.removeView(w.cell)
        w.web.destroy()
        if (selected == id) selected = wins.keys.firstOrNull()
        refresh()
    }

    private fun setZoom(z: Int) {
        val w = cur() ?: return
        w.zoom = z.coerceIn(50, 200)
        w.web.settings.textZoom = w.zoom
        zoomLbl.text = w.name + "  " + w.zoom + "%"
        zoomBar.progress = w.zoom - 50
    }

    private fun applyFit() {
        for (w in wins.values) {
            w.web.settings.useWideViewPort = fit
            w.web.settings.loadWithOverviewMode = fit
            w.web.reload()
        }
    }

    private fun syncAdblockFlag() {
        for (w in wins.values) w.web.evaluateJavascript("window.__hubAdblock=" + adblock, null)
    }

    // ---------- picture in picture ----------
    private fun showFullscreen(v: View, cb: WebChromeClient.CustomViewCallback) {
        customView = v
        customCb = cb
        fs.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        fs.visibility = View.VISIBLE
        ui.visibility = View.GONE
        if (pendingPip) { pendingPip = false; enterPip() }
    }

    private fun hideFullscreen() {
        customView?.let { fs.removeView(it) }
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        fs.visibility = View.GONE
        ui.visibility = View.VISIBLE
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } else {
            toast("Picture-in-Picture needs Android 8 or newer")
        }
    }

    private fun pipSelected() {
        if (customView != null) { enterPip(); return }
        val order = (listOfNotNull(cur()) + wins.values).distinct()
        if (order.isEmpty()) { toast("Open a site first"); return }
        tryPip(order, 0)
    }

    // Taps the video for real (a script alone is not allowed to go fullscreen), then pops it out.
    private fun tryPip(list: List<Win>, i: Int) {
        if (i >= list.size) { toast("No video found. Start a video first."); return }
        val w = list[i].web
        if (w.width == 0) { tryPip(list, i + 1); return }
        w.evaluateJavascript(PIP_PREP) { res ->
            val p = (res ?: "").trim('"').split(",")
            val px = p.getOrNull(0)?.toFloatOrNull()
            val py = p.getOrNull(1)?.toFloatOrNull()
            val pw = p.getOrNull(2)?.toFloatOrNull()
            if (px == null || py == null || pw == null || pw <= 0f) {
                tryPip(list, i + 1)
            } else {
                val f = w.width.toFloat() / pw
                pendingPip = true
                val t = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px * f, py * f, 0)
                val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, px * f, py * f, 0)
                w.dispatchTouchEvent(down)
                w.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
                w.postDelayed({
                    if (pendingPip) {
                        pendingPip = false
                        toast("Tap the video's fullscreen button, then press PiP or Home")
                    }
                }, 2500)
            }
        }
    }

    override fun onUserLeaveHint() {
        if (customView != null) enterPip()
    }

    override fun onBackPressed() {
        when {
            customView != null -> hideFullscreen()
            cur()?.web?.canGoBack() == true -> cur()?.web?.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        for (w in wins.values) w.web.destroy()
        super.onDestroy()
    }
}
