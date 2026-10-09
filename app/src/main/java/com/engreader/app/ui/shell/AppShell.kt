package com.engreader.app.ui.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class Tab(val label: String, val filled: ImageVector, val outlined: ImageVector) {
    Home("阅读", Icons.Filled.Home, Icons.Outlined.Home),
    Discover("发现", Icons.Filled.Explore, Icons.Outlined.Explore),
    Wordbook("生词", Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder),
    Progress("进度", Icons.Filled.Insights, Icons.Outlined.Insights),
}

/**
 * App chrome: a bottom tab bar sized for one-handed reach.
 *
 * Each tab is a 56dp-wide column inside a 64dp bar, which keeps the touch target
 * above the 48dp minimum even though the icon itself is 24dp.
 */
@Composable
fun AppShell(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    dueCount: Int?,
    content: @Composable (Modifier) -> Unit,
) {
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            content(Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(bottom = navBar)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { tab ->
                    TabButton(
                        tab = tab,
                        selected = tab == selected,
                        badge = if (tab == Tab.Wordbook) dueCount else null,
                        onClick = { onSelect(tab) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabButton(
    tab: Tab,
    selected: Boolean,
    badge: Int?,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(if (selected) 1f else 0.55f, label = "tabAlpha")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .size(width = 76.dp, height = 56.dp)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(
                imageVector = if (selected) tab.filled else tab.outlined,
                contentDescription = tab.label,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).alpha(alpha),
            )
            if (badge != null && badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(start = 14.dp, bottom = 14.dp)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, shape = androidx.compose.foundation.shape.CircleShape)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
