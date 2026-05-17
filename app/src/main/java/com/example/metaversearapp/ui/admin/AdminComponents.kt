package com.example.metaversearapp.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Large number + small label chip used in the Admin Hub stats card. */
@Composable
internal fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64FFDA))
        Text(label, fontSize = 12.sp, color = Color.Gray)
    }
}

/** Numbered instruction row used in the How-to card on the Admin Hub. */
@Composable
internal fun InstructionStep(num: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier          = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            "$num.",
            color      = Color(0xFF64FFDA),
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.width(24.dp),
            fontSize   = 13.sp
        )
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}
