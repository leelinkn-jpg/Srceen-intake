package com.linkn.screenintake.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HabitScreen(resumeTick: Int) {
    EmptyHint("习惯这块还没有专门的数据记录，功能正在开发中。")
}

private enum class WorkTab { TODO, NOTE }

@Composable
fun WorkScreen(resumeTick: Int) {
    var tab by remember { mutableStateOf(WorkTab.TODO) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = tab == WorkTab.TODO, onClick = { tab = WorkTab.TODO }, label = { Text("待办") })
            FilterChip(selected = tab == WorkTab.NOTE, onClick = { tab = WorkTab.NOTE }, label = { Text("灵感") })
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                WorkTab.TODO -> TodoListScreen(resumeTick = resumeTick, domainFilter = "工作")
                WorkTab.NOTE -> NoteListScreen(resumeTick = resumeTick, domainFilter = "工作")
            }
        }
    }
}
