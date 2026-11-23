package ai.closepaw.ui.overlay.compose

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.closepaw.protocol.compactThought
import ai.closepaw.ui.capsule.surface.StatusPawGlyph
import ai.closepaw.ui.theme.closePaw

@Composable
fun StatusIslandCompose(
    text: String,
    color: Color,
    ink: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = uiResources()
    val shape = MaterialTheme.shapes.large
    val spacing = MaterialTheme.closePaw.spacing
    Surface(
        onClick = onClick,
        modifier = modifier
            .semantics {
                contentDescription = strings.getString(R.string.ui_status_description, text)
                role = Role.Button
                onClick(label = strings.getString(R.string.ui_show_task_details), action = null)
            },
        shape = shape,
        color = color,
        contentColor = ink,
        shadowElevation = 4.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = spacing.md, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            StatusPawGlyph(
                color = ink,
                size = 18.dp,
            )
            val textModifier = Modifier.widthIn(max = 220.dp)
            Text(
                text = compactThought(text),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = textModifier,
            )
        }
    }
}
