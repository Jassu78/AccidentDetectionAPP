package com.example.accidentdetection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.accidentdetection.ui.theme.AccidentDetectionTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var isAccidentDetected = false
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var emergencyContact1: String? = null
    private var emergencyContact2: String? = null
    private val LOCATION_PERMISSION_CODE = 1000
    private val SMS_PERMISSION_CODE = 1001

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var isBluetoothConnected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize SensorManager and Accelerometer
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkPermissions()

        setContent {
            AccidentDetectionTheme {
                AppContent(
                    onSaveContacts = { contact1, contact2 ->
                        emergencyContact1 = contact1
                        emergencyContact2 = contact2
                    },
                    onConnectToBike = { connectToBike() },
                    onStartMonitoring = { startAccidentDetection() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    @SuppressLint("MissingPermission")
    override fun onSensorChanged(event: SensorEvent?) {
        if (!isBluetoothConnected) return

        event?.let {
            val x = it.values[0]
            val y = it.values[1]
            val z = it.values[2]

            val acceleration = sqrt(x * x + y * y + z * z)
            Log.d("Accelerometer", "Acceleration: $acceleration")

            if (acceleration > 20 && !isAccidentDetected) {
                isAccidentDetected = true
                showAccidentConfirmationDialog()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_CODE)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_CODE)
        }
    }

    private fun connectToBike() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
        val bikeDevice = pairedDevices.find { it.name == "ESP32" } // Replace "ESP32" with the actual name

        if (bikeDevice != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    bluetoothSocket = bikeDevice.createRfcommSocketToServiceRecord(UUID.randomUUID())
                    bluetoothSocket?.connect()
                    isBluetoothConnected = true
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Connected to bike!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: IOException) {
                    Log.e("Bluetooth", "Connection failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Failed to connect to bike", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Bike not found. Pair with ESP32 first.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAccidentConfirmationDialog() {
        val handler = Handler(Looper.getMainLooper())
        val runnable = Runnable { if (isAccidentDetected) onAccidentDetected() }

        handler.postDelayed(runnable, 30000) // 30 seconds delay

        Toast.makeText(this, "Accident detected! Confirm within 30 seconds.", Toast.LENGTH_SHORT).show()
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun onAccidentDetected() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let { sendAccidentAlert(it) } ?: Toast.makeText(this, "Failed to retrieve location.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendAccidentAlert(location: Location) {
        val message = "Accident detected! Location: https://maps.google.com/?q=${location.latitude},${location.longitude}"

        emergencyContact1?.let {
            SmsManager.getDefault().sendTextMessage(it, null, message, null, null)
        }
        emergencyContact2?.let {
            SmsManager.getDefault().sendTextMessage(it, null, message, null, null)
        }

        Toast.makeText(this, "Accident alert sent!", Toast.LENGTH_SHORT).show()
        isAccidentDetected = false
    }

    private fun startAccidentDetection() {
        if (!isBluetoothConnected) {
            Toast.makeText(this, "Connect to bike first.", Toast.LENGTH_SHORT).show()
            return
        }

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        Toast.makeText(this, "Accident monitoring started.", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppContent(
    onSaveContacts: (String, String) -> Unit,
    onConnectToBike: () -> Unit,
    onStartMonitoring: () -> Unit
) {
    var emergencyContact1 by remember { mutableStateOf("") }
    var emergencyContact2 by remember { mutableStateOf("") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Accident Detection") }) },
        content = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Emergency Contacts", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = emergencyContact1,
                    onValueChange = { emergencyContact1 = it },
                    label = { Text("Contact 1") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = emergencyContact2,
                    onValueChange = { emergencyContact2 = it },
                    label = { Text("Contact 2") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = { onSaveContacts(emergencyContact1, emergencyContact2) }) {
                    Text("Save Contacts")
                }
                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = { onConnectToBike() }) {
                    Text("Connect to Bike")
                }
                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = { onStartMonitoring() }) {
                    Text("Start Monitoring")
                }
            }
        }
    )
}
