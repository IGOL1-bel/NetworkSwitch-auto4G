package com.supernova.networkswitch.presentation.ui.composable

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.supernova.networkswitch.domain.model.NetworkMode

/**
 * A drop-down over the network modes, by RIL value. With [noneLabel] it gets an extra first
 * entry that stands for "no particular mode" and reports [NO_MODE].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeDropdown(
    label: String,
    selectedValue: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    noneLabel: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedMode = NetworkMode.fromValue(selectedValue)
    val selectedText = if (selectedMode != null) stringResource(selectedMode.labelRes) else noneLabel.orEmpty()

    Text(text = label, style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(4.dp))
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = { },
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (noneLabel != null) {
                DropdownMenuItem(
                    text = { Text(noneLabel) },
                    onClick = {
                        onSelected(NO_MODE)
                        expanded = false
                    },
                )
            }
            NetworkMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.labelRes)) },
                    onClick = {
                        onSelected(mode.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Reported by [ModeDropdown] when its "none" entry is picked. */
const val NO_MODE = -1
