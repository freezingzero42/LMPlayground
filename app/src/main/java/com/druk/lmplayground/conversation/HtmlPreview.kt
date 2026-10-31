package com.druk.lmplayground.conversation

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.druk.lmplayground.R
import com.druk.lmplayground.tools.HtmlPreviewTool
import org.json.JSONObject

private const val TAG = "HtmlPreview"

/** Height of the inline preview inside a chat message. */
private val INLINE_PREVIEW_HEIGHT = 320.dp

/**
 * Base URL for previewed pages. It gives the page a real (https) origin so
 * localStorage, fetch() with CORS and friends work, while the reserved
 * `.invalid` TLD guarantees it can never resolve to a real server.
 */
private const val PREVIEW_HOST = "preview.lmplayground.invalid"
private const val PREVIEW_BASE_URL = "https://$PREVIEW_HOST/"

/**
 * The page to render for a `render_html` tool call, or null when the call
 * failed (bad arguments, oversized page) — in which case the chat falls back
 * to the normal collapsible tool-call section so the error stays visible.
 */
fun htmlPreviewFor(toolCall: ToolCallInfo): HtmlPreviewTool.Preview? {
    if (toolCall.name != HtmlPreviewTool.NAME) return null
    val failed = try {
        JSONObject(toolCall.result).has("error")
    } catch (_: Exception) {
        true
    }
    if (failed) return null
    return (HtmlPreviewTool.parse(toolCall.arguments) as? HtmlPreviewTool.ParseResult.Ok)?.preview
}

/**
 * Inline, interactive preview card for a page the model rendered with the
 * `render_html` tool. Shows a live WebView, with toggles for the source,
 * reload and a full-screen view.
 */
@Composable
fun HtmlPreviewCard(
    preview: HtmlPreviewTool.Preview,
    modifier: Modifier = Modifier,
) {
    var showCode by rememberSaveable(preview.html) { mutableStateOf(false) }
    var fullScreen by rememberSaveable(preview.html) { mutableStateOf(false) }
    var reloadCount by remember(preview.html) { mutableIntStateOf(0) }
    val clipboardManager = LocalClipboardManager.current
    val title = preview.title.ifBlank { stringResource(R.string.tool_render_html_title) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Web,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                if (showCode) {
                    PreviewActionButton(
                        icon = Icons.Outlined.ContentCopy,
                        description = stringResource(R.string.copy),
                        onClick = { clipboardManager.setText(AnnotatedString(preview.html)) },
                    )
                    PreviewActionButton(
                        icon = Icons.Outlined.Visibility,
                        description = stringResource(R.string.html_preview_show_preview),
                        onClick = { showCode = false },
                    )
                } else {
                    PreviewActionButton(
                        icon = Icons.Outlined.Refresh,
                        description = stringResource(R.string.html_preview_reload),
                        onClick = { reloadCount++ },
                    )
                    PreviewActionButton(
                        icon = Icons.Outlined.Code,
                        description = stringResource(R.string.html_preview_show_code),
                        onClick = { showCode = true },
                    )
                }
                PreviewActionButton(
                    icon = Icons.Outlined.Fullscreen,
                    description = stringResource(R.string.html_preview_full_screen),
                    onClick = { fullScreen = true },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(INLINE_PREVIEW_HEIGHT),
            ) {
                if (showCode) {
                    HtmlSource(html = preview.html, modifier = Modifier.fillMaxSize())
                } else {
                    // A new key recreates the WebView, which is the most
                    // reliable "reload" for a page loaded from a string.
                    key(reloadCount) {
                        HtmlWebView(
                            html = preview.html,
                            // Inside the scrolling chat, the page gets the
                            // gesture so drags (sliders, canvas games) work.
                            captureTouches = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    if (fullScreen) {
        HtmlPreviewDialog(
            title = title,
            html = preview.html,
            onDismiss = { fullScreen = false },
        )
    }
}

@Composable
private fun PreviewActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun HtmlSource(html: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(
                text = html,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun HtmlPreviewDialog(
    title: String,
    html: String,
    onDismiss: () -> Unit,
) {
    var reloadCount by remember { mutableIntStateOf(0) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { reloadCount++ }) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.html_preview_reload),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                key(reloadCount) {
                    HtmlWebView(
                        html = html,
                        captureTouches = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlWebView(
    html: String,
    captureTouches: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> createPreviewWebView(context, captureTouches) },
        update = { webView ->
            // Only (re)load when the page actually changed; recomposition
            // alone must not restart a running game or animation.
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(PREVIEW_BASE_URL, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}

/**
 * A sandboxed WebView for model-written pages:
 * - JavaScript on (the point of a live preview), but no JS bridge to the app.
 * - Internet on, so pages can use CDN libraries (Tailwind, Chart.js...), web
 *   fonts, remote images and public APIs. Files are cached normally so a
 *   library downloads once.
 * - No file/content access, no geolocation, no popups.
 * - The preview never navigates away from its page. A link the user taps
 *   opens in their browser instead; script- or redirect-driven navigation is
 *   refused, so a page can't silently replace itself with another site.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createPreviewWebView(
    context: Context,
    captureTouches: Boolean,
): WebView =
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // The page's origin is https; let it still show http:// images
            // and media that models often link to.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                // In-page anchors (#section) just scroll; let them through.
                if (url.host == PREVIEW_HOST && url.fragment != null) return false
                if (request.hasGesture() && (scheme == "http" || scheme == "https")) {
                    openInBrowser(view.context, url)
                }
                return true
            }
        }
        // A WebChromeClient lets the page use alert()/confirm(), which
        // generated games and demos commonly rely on.
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                    "(line ${consoleMessage.lineNumber()})")
                return true
            }
        }
        if (captureTouches) {
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
    }

/**
 * Fallback for replies that contain the page as Markdown code blocks instead
 * of a `render_html` call (common with models that can't call tools). Shows a
 * "Preview" button per page; the live preview opens only on tap, so an
 * ordinary coding answer doesn't start running scripts by itself.
 */
@Composable
fun HtmlCodeBlockPreviews(
    responseText: String,
    modifier: Modifier = Modifier,
) {
    val previews = remember(responseText) { HtmlPreviewTool.previewsFromMarkdown(responseText) }
    if (previews.isEmpty()) return
    Column(modifier = modifier) {
        previews.forEachIndexed { index, preview ->
            // key() gives each page its own saved state; without it every
            // iteration shares one rememberSaveable slot.
            key(index) {
                var open by rememberSaveable(responseText) { mutableStateOf(false) }
                if (open) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HtmlPreviewCard(preview = preview)
                } else {
                    TextButton(onClick = { open = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Web,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.html_preview_open))
                    }
                }
            }
        }
    }
}

/** Open a link the user tapped inside a preview in their default browser. */
private fun openInBrowser(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app to open $uri", e)
    }
}
