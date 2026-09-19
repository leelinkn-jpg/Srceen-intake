package com.linkn.screenintake.ui

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.classify.ExpensePurpose
import com.linkn.screenintake.store.CardAccount

/** 消费确认页的三项选择统一为下拉框，类别、用途、账户再多也不挤占页面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (stored, display) ->
                DropdownMenuItem(text = { Text(display) }, onClick = {
                    onSelect(stored)
                    expanded = false
                })
            }
        }
    }
}

@Composable
fun ExpenseCategoryPicker(selected: String, onSelect: (String) -> Unit) {
    val categories = (ScreenIntakeApp.instance.settingsStore.expenseCategories + selected)
        .filter { it.isNotBlank() }.distinct()
    ChoiceDropdown("消费类别", selected, categories.map { it to it }, onSelect)
}

@Composable
fun ExpensePurposePicker(selected: String?, onSelect: (String?) -> Unit) {
    val purposes = (ScreenIntakeApp.instance.settingsStore.expensePurposes + listOfNotNull(selected)).distinct()
    ChoiceDropdown(
        label = "消费用途（选填）",
        value = selected ?: ExpensePurpose.NONE,
        options = listOf("" to ExpensePurpose.NONE) + purposes.map { it to it }
    ) { onSelect(it.ifBlank { null }) }
}

@Composable
fun ExpenseCardPicker(selected: String?, cards: List<CardAccount>, onSelect: (String?) -> Unit) {
    ChoiceDropdown(
        label = "关联账户（选填）",
        value = selected ?: "未关联",
        options = listOf("" to "未关联") + cards.map { it.name to it.name }
    ) { onSelect(it.ifBlank { null }) }
}
