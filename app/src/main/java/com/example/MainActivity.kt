package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.UUID

// Standard Serial Port Profile (SPP) UUID
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
private const val APP_NAME = "Virtual Receipt Printer"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                PermissionRequiredContent {
                    VirtualPrinterApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionRequiredContent(content: @Composable () -> Unit) {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissions)

    if (permissionsState.allPermissionsGranted) {
        content()
    } else {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = "Bluetooth Permissions Required",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    text = "This app requires Bluetooth and Location permissions to act as a virtual printer and accept connections.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 32.dp)
                )
                Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirtualPrinterApp() {
    val context = LocalContext.current
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    
    var isServerRunning by remember { mutableStateOf(false) }
    var bluetoothEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }
    val printLogs = remember { mutableStateListOf<String>("SYSTEM: Virtual printer initialized.", "Waiting for data...") }
    val coroutineScope = rememberCoroutineScope()

    var serverSocket by remember { mutableStateOf<BluetoothServerSocket?>(null) }
    var clientSocket by remember { mutableStateOf<BluetoothSocket?>(null) }

    val discoverableLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_CANCELED) {
            // Discoverability granted
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(APP_NAME, SPP_UUID)
                    withContext(Dispatchers.Main) {
                        printLogs.add("SYSTEM: Server started on SPP UUID.")
                    }
                    
                    while (isServerRunning) {
                        try {
                            clientSocket = serverSocket?.accept()
                            clientSocket?.let { socket ->
                                withContext(Dispatchers.Main) {
                                    printLogs.add("SYSTEM: Client connected: ${socket.remoteDevice.address}")
                                }
                                
                                val inputStream: InputStream = socket.inputStream
                                val buffer = ByteArray(1024)
                                var bytes: Int
                                
                                while (true) {
                                    try {
                                        bytes = inputStream.read(buffer)
                                        if (bytes > 0) {
                                            val receivedData = buffer.copyOfRange(0, bytes)
                                            // Phase 5 preparation: basic translation for now
                                            val textData = String(receivedData)
                                            val hexData = receivedData.joinToString(" ") { "%02X".format(it) }
                                            
                                            withContext(Dispatchers.Main) {
                                                printLogs.add("RX: $textData")
                                                printLogs.add("HEX: $hexData")
                                            }
                                        }
                                    } catch (e: IOException) {
                                        withContext(Dispatchers.Main) {
                                            printLogs.add("SYSTEM: Client disconnected.")
                                        }
                                        break
                                    }
                                }
                            }
                        } catch (e: IOException) {
                             if (isServerRunning) {
                                 withContext(Dispatchers.Main) {
                                     printLogs.add("SYSTEM: Error accepting connection - ${e.message}")
                                 }
                             }
                             break
                        }
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        printLogs.add("SYSTEM: Error starting server - ${e.message}")
                        isServerRunning = false
                    }
                }
            }
        } else {
            // Discoverability denied, we can still run but won't be visible to new devices
            isServerRunning = false
            printLogs.add("SYSTEM: Discoverability request denied.")
        }
    }

    LaunchedEffect(Unit) {
        val filter = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    bluetoothEnabled = state == BluetoothAdapter.STATE_ON
                    if (!bluetoothEnabled) {
                        isServerRunning = false
                        serverSocket?.close()
                        clientSocket?.close()
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }
    
    DisposableEffect(Unit) {
        onDispose {
            serverSocket?.close()
            clientSocket?.close()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = "App Icon",
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Control Panel
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bluetooth Server",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (!bluetoothEnabled) "Bluetooth Disabled" else if (isServerRunning) "Listening for connections..." else "Stopped",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (!bluetoothEnabled) MaterialTheme.colorScheme.error else if (isServerRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isServerRunning && bluetoothEnabled,
                        onCheckedChange = { start -> 
                            isServerRunning = start
                            if (start && bluetoothAdapter != null) {
                                printLogs.clear()
                                printLogs.add("SYSTEM: Requesting discoverability...")
                                val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                                }
                                discoverableLauncher.launch(discoverableIntent)
                            } else {
                                try {
                                    serverSocket?.close()
                                    clientSocket?.close()
                                    printLogs.add("SYSTEM: Server stopped.")
                                } catch (e: Exception) {
                                    Log.e("VirtualPrinter", "Error closing sockets", e)
                                }
                            }
                        },
                        modifier = Modifier.padding(start = 16.dp),
                        enabled = bluetoothEnabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Log Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Print Spooler",
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = { printLogs.clear() }) {
                    Text("Clear")
                }
            }

            // Spooler Terminal
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(8.dp)
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(printLogs) { log ->
                        val color = when {
                            log.startsWith("SYSTEM:") -> Color.Yellow
                            log.startsWith("RX:") -> Color.White
                            log.startsWith("HEX:") -> Color.LightGray
                            else -> Color(0xFF00FF00) // Default green
                        }
                        Text(
                            text = log,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
