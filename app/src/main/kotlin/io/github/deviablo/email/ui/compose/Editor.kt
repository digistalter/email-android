// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui.compose

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Talks to the editor page: formatting commands and reading the text back. */
class EditorController {
    internal var web: WebView? = null

    private fun js(code: String, result: ((String?) -> Unit)? = null) {
        web?.evaluateJavascript(code) { result?.invoke(it) } ?: result?.invoke(null)
    }

    fun command(name: String, value: String? = null) {
        val arg = if (value == null) "null" else JSONObject.quote(value)
        js("document.body.focus();document.execCommand(${JSONObject.quote(name)}, false, $arg);")
    }

    fun insertImage(dataUri: String) = command("insertImage", dataUri)

    /** Selected text, to prefill the link dialog. */
    suspend fun selection(): String = suspendCancellableCoroutine { c ->
        js("window.getSelection().toString()") { c.resume(decode(it)) }
    }

    /** The inside of <body> as it is now. */
    suspend fun html(): String = suspendCancellableCoroutine { c ->
        js("document.body.innerHTML") { c.resume(decode(it)) }
    }

    /** Cursor at the very beginning (above signature and quote) and the keyboard up. */
    fun focusStart() {
        val w = web ?: return
        w.requestFocus()
        js(
            "var r=document.createRange();var n=document.body.firstChild||document.body;" +
                "while(n.firstChild&&n.nodeType==1&&n.firstChild.nodeName!='BR')n=n.firstChild;" +
                "r.setStart(n,0);r.collapse(true);var s=window.getSelection();s.removeAllRanges();s.addRange(r);document.body.focus();",
        )
        val imm = w.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        w.post { imm.showSoftInput(w, 0) }
    }

    private fun decode(value: String?): String =
        if (value == null || value == "null") "" else runCatching { JSONArray("[$value]").getString(0) }.getOrDefault("")
}

/**
 * The text area of the composer: a WebView with an editable body. JavaScript runs only so the
 * app can send formatting commands; the content was cleaned beforehand, nothing is loaded from the
 * net and links do not open.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichEditor(initialHtml: String, controller: EditorController, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.blockNetworkLoads = true
                settings.textZoom = 100
                isVerticalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.url.scheme == "data") null else WebResourceResponse("text/plain", "utf-8", null)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                    // Tapping a picture switches its size: full width, half, quarter (proportional).
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            "document.addEventListener('click',function(e){var i=e.target;if(i.tagName!='IMG')return;" +
                                "var w=i.style.width;i.style.width=w==''?'50%':(w=='50%'?'25%':'');i.style.height='auto';});",
                            null,
                        )
                    }
                }
                loadDataWithBaseURL("about:blank", page(initialHtml, dark), "text/html", "utf-8", null)
                controller.web = this
            }
        },
        onRelease = { if (controller.web === it) controller.web = null; it.destroy() },
    )
}

private fun page(body: String, dark: Boolean): String {
    val fg = if (dark) "#e3e3e3" else "#1f1f1f"
    val muted = if (dark) "#c4c7c5" else "#5f6368"
    val link = if (dark) "#a8c7fa" else "#0b57d0"
    return """<!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline';">
        <style>
        html{margin:0;padding:0;background:transparent}
        body{margin:0;padding:8px 16px 48px;min-height:240px;font-family:sans-serif;font-size:16px;line-height:1.5;color:$fg;overflow-wrap:anywhere;outline:none;caret-color:$link}
        img{max-width:100%;height:auto}
        a{color:$link}
        blockquote{margin:8px 0;padding-left:10px;border-left:3px solid #c4c7c5;color:$muted}
        .signature{color:$muted}
        </style></head><body contenteditable="true">$body</body></html>"""
}
