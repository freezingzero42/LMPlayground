@file:OptIn(ExperimentalMaterial3Api::class)

package com.druk.lmplayground.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.druk.lmplayground.R
import com.druk.llamacpp.tools.Tool

/**
 * User-facing copy for a tool, keyed by [Tool.name]. The runtime
 * [Tool.description] is written for the model, not the user, so the settings
 * screen uses its own plain-language strings and a worked example.
 */
private data class ToolUiInfo(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    @StringRes val exampleRes: Int,
    val icon: ImageVector,
)

private val TOOL_UI: Map<String, ToolUiInfo> = mapOf(
    "add_calendar_event" to ToolUiInfo(
        R.string.tool_add_calendar_event_title,
        R.string.tool_add_calendar_event_desc,
        R.string.tool_add_calendar_event_example,
        Icons.Outlined.Event,
    ),
    "run_javascript" to ToolUiInfo(
        R.string.tool_run_javascript_title,
        R.string.tool_run_javascript_desc,
        R.string.tool_run_javascript_example,
        Icons.Outlined.Code,
    ),
    "get_date_time" to ToolUiInfo(
        R.string.tool_get_date_time_title,
        R.string.tool_get_date_time_desc,
        R.string.tool_get_date_time_example,
        Icons.Outlined.Schedule,
    ),
    "web_search" to ToolUiInfo(
        R.string.tool_web_search_title,
        R.string.tool_web_search_desc,
        R.string.tool_web_search_example,
        Icons.Outlined.Search,
    ),
    "web_fetch" to ToolUiInfo(
        R.string.tool_web_fetch_title,
        R.string.tool_web_fetch_desc,
        R.string.tool_web_fetch_example,
        Icons.Outlined.Public,
    ),
    "set_alarm" to ToolUiInfo(
        R.string.tool_set_alarm_title,
        R.string.tool_set_alarm_desc,
        R.string.tool_set_alarm_example,
        Icons.Outlined.Alarm,
    ),
    "calculator" to ToolUiInfo(
        R.string.tool_calculator_title,
        R.string.tool_calculator_desc,
        R.string.tool_calculator_example,
        Icons.Outlined.Calculate,
    ),
    "convert_units" to ToolUiInfo(
        R.string.tool_convert_units_title,
        R.string.tool_convert_units_desc,
        R.string.tool_convert_units_example,
        Icons.Outlined.SwapHoriz,
    ),
)

@Composable
fun ToolsScreen(
    tools: List<Tool>,
    enabledStates: Map<String, Boolean>,
    onToolEnabledChanged: (String, Boolean) -> Unit,
    htmlPreviewInternet: Boolean,
    onHtmlPreviewInternetChanged: (Boolean) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        ToolsContent(
            tools = tools,
            enabledStates = enabledStates,
            onToolEnabledChanged = onToolEnabledChanged,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            htmlPreviewInternet = htmlPreviewInternet,
            onHtmlPreviewInternetChanged = onHtmlPreviewInternetChanged,
        )
    }
}

/**
 * The tool rows. When [htmlPreviewInternet] is given, the "Internet access in
 * previews" row follows them, styled like another tool.
 */
@Composable
fun ToolsContent(
    tools: List<Tool>,
    enabledStates: Map<String, Boolean>,
    onToolEnabledChanged: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    htmlPreviewInternet: Boolean? = null,
    onHtmlPreviewInternetChanged: (Boolean) -> Unit = {},
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        tools.forEach { tool ->
            ToolRow(
                tool = tool,
                enabled = enabledStates[tool.name] ?: false,
                onToggle = { onToolEnabledChanged(tool.name, it) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (htmlPreviewInternet != null) {
            HtmlPreviewInternetRow(
                enabled = htmlPreviewInternet,
                onToggle = onHtmlPreviewInternetChanged,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

/**
 * "Internet access in previews" for live HTML previews, styled like a tool
 * row. App-wide and off by default, so previews stay offline unless the user
 * opts in; shown on the Tools page and in a model's Tools tab, both writing
 * the same setting.
 */
@Composable
fun HtmlPreviewInternetRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) = SwitchRow(
    icon = Icons.Outlined.Language,
    title = stringResource(R.string.tools_html_preview_internet_title),
    description = stringResource(R.string.tools_html_preview_internet_desc),
    checked = enabled,
    onCheckedChange = onToggle,
)

@Composable
private fun ToolRow(
    tool: Tool,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val ui = TOOL_UI[tool.name]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (ui != null) {
            Icon(
                imageVector = ui.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = if (ui != null) stringResource(ui.titleRes) else tool.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (ui != null) stringResource(ui.descRes) else tool.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tools_example_label, stringResource(ui.exampleRes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}
