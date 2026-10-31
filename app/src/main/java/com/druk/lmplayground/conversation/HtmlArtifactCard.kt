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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.druk.lmplayground.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "HtmlArtifact"

/** Max height of the code view before it scrolls inside the card. */
private val CODE_MAX_HEIGHT = 320.dp

/** Height of the inline live preview. */
private val PREVIEW_HEIGHT = 420.dp

/**
 * Base URL for previewed pages. It gives the page a real (https) origin so
 * localStorage, fetch() with CORS and friends work, while the reserved
 * `.invalid` TLD guarantees it can never resolve to a real server.
 */
private const val PREVIEW_HOST = "preview.lmplayground.invalid"
private const val PREVIEW_BASE_URL = "https://$PREVIEW_HOST/"

/**
 * An assistant reply: Markdown text, with every ```html code block shown as
 * an [HtmlArtifactCard] instead of plain code. Replies without HTML blocks
 * render exactly as before (one selectable Markdown text).
 */
@Composable
fun AssistantReplyContent(
    text: String,
    isGenerating: Boolean,
    textStyle: TextStyle,
) {
    val segments = remember(text) { HtmlArtifacts.split(text) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (segment in segments) {
            when (segment) {
                is HtmlArtifacts.Segment.Text -> {
                    val styled = messageFormatter(text = segment.text, primary = false)
                    SelectionContainer {
                        Text(text = styled, style = textStyle)
                    }
                }
                is HtmlArtifacts.Segment.Artifact -> {
                    // key() by ordinal: the card keeps its own state (code vs
                    // preview) while the reply around it keeps streaming.
                    key(segment.ordinal) {
                        HtmlArtifactCard(
                            code = segment.code,
                            // An unclosed fence is still being written —
                            // unless generation stopped, then preview what
                            // there is.
                            writing = !segment.complete && isGenerating,
                            // The page may pull in sibling ```css / ```js
                            // blocks, so it is keyed on the whole reply.
                            documentKey = text,
                            buildDocument = { HtmlArtifacts.previewDocument(text, segment) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A web page the model wrote, shown as an artifact: the code first (streaming
 * in while the model writes), then — from the header, next to Copy, Download
 * and Full screen — a Live preview button that runs it in a sandboxed WebView.
 */
@Composable
fun HtmlArtifactCard(
    code: String,
    writing: Boolean,
    documentKey: Any,
    buildDocument: () -> String,
    modifier: Modifier = Modifier,
) {
    var showPreview by rememberSaveable { mutableStateOf(false) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var reloadCount by remember { mutableIntStateOf(0) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageTitle = remember(code) { HtmlArtifacts.title(code) }
    val title = pageTitle.ifBlank { stringResource(R.string.html_artifact_default_title) }
    // Only build the document when it's going to be shown.
    val document = if ((showPreview || fullScreen) && !writing) {
        remember(documentKey) { buildDocument() }
    } else {
        null
    }

    // Download: Android's "save as" picker lets the user choose the folder
    // and file name; we then write the page there. The file is the same
    // document the live preview runs, so it opens identically in a browser.
    val savedMessage = stringResource(R.string.html_artifact_saved)
    val saveFailedMessage = stringResource(R.string.html_artifact_save_failed)
    val currentBuildDocument by rememberUpdatedState(buildDocument)
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/html"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // cancelled
        val page = currentBuildDocument()
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                        out.write(page.toByteArray(Charsets.UTF_8))
                    } != null
                } catch (e: Exception) {
                    Log.w(TAG, "Saving HTML to $uri failed", e)
                    false
                }
            }
            Toast.makeText(
                context,
                if (saved) savedMessage else saveFailedMessage,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            // Header: title + status, then every action in one row.
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Web,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (writing) stringResource(R.string.html_artifact_writing) else "HTML",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (!writing) {
                    // Live preview <-> Code. Tonal so it reads as the main action.
                    FilledTonalIconButton(
                        onClick = { showPreview = !showPreview },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (showPreview) Icons.Outlined.Code else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(
                                if (showPreview) R.string.html_artifact_code else R.string.html_artifact_live_preview,
                            ),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (showPreview) {
                        CardIconButton(
                            icon = Icons.Outlined.Refresh,
                            description = stringResource(R.string.html_preview_reload),
                            onClick = { reloadCount++ },
                        )
                    }
                }
                CardIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    description = stringResource(R.string.copy),
                    onClick = { clipboardManager.setText(AnnotatedString(code)) },
                )
                if (!writing) {
                    CardIconButton(
                        icon = Icons.Outlined.Download,
                        description = stringResource(R.string.html_artifact_download),
                        onClick = { saveLauncher.launch(HtmlArtifacts.fileName(pageTitle)) },
                    )
                    CardIconButton(
                        icon = Icons.Outlined.Fullscreen,
                        description = stringResource(R.string.html_preview_full_screen),
                        onClick = { fullScreen = true },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (showPreview && document != null) {
                key(reloadCount) {
                    HtmlWebView(
                        html = document,
                        // Inside the scrolling chat the page gets the gesture,
                        // so drags (sliders, canvas games) work.
                        captureTouches = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PREVIEW_HEIGHT),
                    )
                }
            } else {
                CodeView(code = code, followTail = writing)
            }
        }
    }

    if (fullScreen && document != null) {
        HtmlPreviewDialog(
            title = title,
            html = document,
            onDismiss = { fullScreen = false },
        )
    }
}

@Composable
private fun CardIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
    }
}

/**
 * The artifact's source. Scrolls inside the card past [CODE_MAX_HEIGHT];
 * while the model is writing it follows the newest line, like a terminal.
 */
@Composable
private fun CodeView(code: String, followTail: Boolean) {
    val vertical = rememberScrollState()
    if (followTail) {
        LaunchedEffect(Unit) {
            snapshotFlow { vertical.maxValue }.collect { vertical.scrollTo(it) }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = CODE_MAX_HEIGHT)
            .verticalScroll(vertical)
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = LocalContentColor.current,
                ),
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
 *   fonts, remote images and public APIs. Normal HTTP caching, so a library
 *   downloads once.
 * - No file/content access, no geolocation, no popups.
 * - The preview never navigates away from its page. A link the user taps
 *   opens in their browser; script- or redirect-driven navigation is refused,
 *   so a page can't silently replace itself with another site.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createPreviewWebView(context: Context, captureTouches: Boolean): WebView =
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
            // The page's origin is https; still show http:// images and
            // media that models often link to.
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
                // In-page anchors (#section) just scroll; let them through.
                if (url.host == PREVIEW_HOST && url.fragment != null) return false
                val scheme = url.scheme?.lowercase()
                if (request.hasGesture() && (scheme == "http" || scheme == "https")) {
                    openInBrowser(view.context, url)
                }
                return true
            }
        }
        // A WebChromeClient enables alert()/confirm()/prompt() dialogs, which
        // generated games and demos commonly rely on.
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    TAG,
                    "${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                        "(line ${consoleMessage.lineNumber()})",
                )
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

/** Open a link the user tapped inside a preview in their default browser. */
private fun openInBrowser(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No app to open $uri", e)
    }
}
