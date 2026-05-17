package com.example.metaversearapp.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PinGateScreen(onSuccess: () -> Unit, onCancel: () -> Unit) {
    var pin   by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier            = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint               = Color(0xFF64FFDA),
                    modifier           = Modifier.size(48.dp)
                )
                Text(
                    "Admin Access",
                    fontSize   = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White
                )
                OutlinedTextField(
                    value         = pin,
                    onValueChange = { if (it.length <= 6) { pin = it; error = false } },
                    label         = { Text("PIN", color = Color.Gray) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError        = error,
                    supportingText = if (error) ({ Text("Wrong PIN", color = Color.Red) }) else null,
                    singleLine     = true,
                    colors         = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Color(0xFF64FFDA),
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel", color = Color.Gray) }

                    Button(
                        onClick = {
                            if (pin == ADMIN_PIN) onSuccess()
                            else { error = true; pin = "" }
                        },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF64FFDA))
                    ) { Text("Enter", color = Color.Black, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
