package org.nacre.calculator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val history by Store.history.collectAsState()
    Page("History", onBack, {
        if (history.isNotEmpty()) {
            IconButton(onClick = Store::clearHistory) {
                Icon(painterResource(R.drawable.ic_delete), "Clear history")
            }
        }
    }) {
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No calculations yet", color = MaterialTheme.colorScheme.outline)
            }
            return@Page
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(history) { entry ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        Store.recall(entry.result)
                        onBack()
                    }.padding(24.dp, 16.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        display(entry.expression),
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "= ${display(entry.result)}",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            }
        }
    }
}

@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(
                Modifier.padding(8.dp),
                Arrangement.spacedBy(8.dp),
                Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), "Back")
                }
                Text(
                    title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge
                )
                actions()
            }
            content()
        }
    }
}
