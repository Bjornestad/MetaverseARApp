package com.example.metaversearapp.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.metaversearapp.ui.ARViewModel

@Composable
fun ARUiOverlay(viewModel: ARViewModel) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusOverlay(viewModel.statusText)
            Spacer(modifier = Modifier.height(8.dp))

            DestinationSelector(viewModel)

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.toggleScanning() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (viewModel.isScanning) Color.Red else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (viewModel.isScanning) "Cancel Scan" else "Scan QR to Improve Accuracy")
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (viewModel.selectedDestination != null && viewModel.geospatialPose != null) {
                NavigationArrow(
                    currentPose = viewModel.geospatialPose!!,
                    destination = viewModel.selectedDestination!!,
                    latOffset = viewModel.latOffset,
                    lonOffset = viewModel.lonOffset
                )
            }

            GeospatialBottomOverlay(viewModel = viewModel)
        }
    }
}

@Composable
fun DestinationSelector(viewModel: ARViewModel) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f))
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Target Destination:", style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(onClick = { viewModel.isDropdownExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(viewModel.selectedDestination?.name ?: "Select Room")
                }
                DropdownMenu(
                    expanded = viewModel.isDropdownExpanded,
                    onDismissRequest = { viewModel.isDropdownExpanded = false }
                ) {
                    viewModel.allLocations.forEach { loc ->
                        DropdownMenuItem(
                            text = { Text(loc.name) },
                            onClick = {
                                viewModel.onDestinationSelected(loc)
                            }
                        )
                    }
                }
            }
        }
    }
}
