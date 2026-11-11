package jp.noppy.lumamusic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

private val LineBreaks = Regex("[\\r\\n\\u0085\\u2028\\u2029]+")

/** Ellipsize only at layout time so accessibility still receives the full text. */
@Composable
internal fun TrackText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    Text(
        text.replace(LineBreaks, " "),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = onTextLayout,
    )
}
