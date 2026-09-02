package com.example.campuslink

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val SERVICE_ID = "com.example.campuslink.SERVICE_ID"
    private val STUDENT_PREFIX = "CL|"
    private val ADMIN_PREFIX = "ADMIN|"
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val CHANNEL_ID = "campuslink_transfers"

    private lateinit var connectionsClient: ConnectionsClient

    private lateinit var statusText: TextView
    private lateinit var nameInput: EditText

    private lateinit var startupContainer: LinearLayout
    private lateinit var btnStudentMode: Button
    private lateinit var btnAdminMode: Button

    private lateinit var adminContainer: LinearLayout
    private lateinit var btnAdminBroadcast: Button
    private lateinit var adminDevicesList: LinearLayout

    private lateinit var studentContainer: LinearLayout
    private lateinit var noticeBoardList: LinearLayout
    private lateinit var btnStudentSend: Button
    private lateinit var studentDevicesList: LinearLayout

    private lateinit var relayScorer: RelayScorer
    private val peerScores = mutableMapOf<String, Float>()
    private val peerNames = mutableMapOf<String, String>()

    private val processedTransferIds = mutableSetOf<String>()

    private val uniqueId = UUID.randomUUID().toString().substring(0, 4)
    private var localEndpointName: String = ""
    private var isAdmin = false

    private val connectedEndpoints = mutableSetOf<String>()
    private val pendingFilePayloads = mutableMapOf<Long, Payload>()
    private val incomingFileMeta = mutableMapOf<Long, Pair<String, String>>()
    private val discoveredDeviceViews = mutableMapOf<String, View>()
    private val adminConnectedViews = mutableMapOf<String, View>()

    private val requiredPermissions: Array<String>
        get() = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusText = findViewById(R.id.statusText)
        nameInput = findViewById(R.id.nameInput)

        startupContainer = findViewById(R.id.startupContainer)
        btnStudentMode = findViewById(R.id.btnStudentMode)
        btnAdminMode = findViewById(R.id.btnAdminMode)

        adminContainer = findViewById(R.id.adminContainer)
        btnAdminBroadcast = findViewById(R.id.btnAdminBroadcast)
        adminDevicesList = findViewById(R.id.adminDevicesList)

        studentContainer = findViewById(R.id.studentContainer)
        noticeBoardList = findViewById(R.id.noticeBoardList)
        btnStudentSend = findViewById(R.id.btnStudentSend)
        studentDevicesList = findViewById(R.id.studentDevicesList)

        connectionsClient = Nearby.getConnectionsClient(this)
        relayScorer = RelayScorer(this)

        createNotificationChannel()
        checkAndRequestPermissions()

        val prefs = getSharedPreferences("CampusLinkPrefs", MODE_PRIVATE)
        nameInput.setText(prefs.getString("DISPLAY_NAME", Build.MODEL))

        btnStudentMode.setOnClickListener { launchMode(asAdmin = false) }

        btnAdminMode.setOnClickListener {
            val pinInput = EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "Enter Admin PIN"
            }
            AlertDialog.Builder(this)
                .setTitle("Admin Verification")
                .setView(pinInput)
                .setPositiveButton("Verify") { _, _ ->
                    if (pinInput.text.toString() == "2026") {
                        launchMode(asAdmin = true)
                    } else {
                        Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnAdminBroadcast.setOnClickListener { if (connectedEndpoints.isNotEmpty()) filePickerLauncher.launch(arrayOf("*/*")) }
        btnStudentSend.setOnClickListener { if (connectedEndpoints.isNotEmpty()) filePickerLauncher.launch(arrayOf("*/*")) }
    }

    private fun launchMode(asAdmin: Boolean) {
        val displayName = nameInput.text.toString().trim()
        if (displayName.isEmpty()) {
            Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("CampusLinkPrefs", MODE_PRIVATE)
        prefs.edit().putString("DISPLAY_NAME", displayName).apply()

        isAdmin = asAdmin
        val prefix = if (isAdmin) ADMIN_PREFIX else STUDENT_PREFIX
        localEndpointName = "$prefix$displayName|$uniqueId"

        startupContainer.visibility = View.GONE
        if (isAdmin) {
            adminContainer.visibility = View.VISIBLE
            btnAdminBroadcast.isEnabled = false
        } else {
            studentContainer.visibility = View.VISIBLE
            btnStudentSend.isEnabled = false
        }

        updateStatus(if (isAdmin) "Command Center Active..." else "Listening on Notice Board...")

        startAdvertising()
        startDiscovery()
    }

    private fun getBatteryLevel(): Float {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
    }

    private fun getDeviceUptimeMinutes(): Float {
        return (SystemClock.elapsedRealtime() / 60000f)
    }

    private fun getSimulatedRssiVariance(): Float {
        return Random.nextFloat() * 15f + 2f
    }

    private fun sendHealthPing(endpointId: String) {
        val battery = getBatteryLevel()
        val uptime = getDeviceUptimeMinutes()
        val pingMessage = "PING|BATTERY:$battery|UPTIME:$uptime"
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(pingMessage.toByteArray(Charsets.UTF_8)))
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun updateStatus(text: String) {
        runOnUiThread { statusText.text = "Status: $text" }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(localEndpointName, SERVICE_ID, connectionLifecycleCallback, options)
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val isRemoteAdmin = info.endpointName.startsWith(ADMIN_PREFIX)
            val isRemoteStudent = info.endpointName.startsWith(STUDENT_PREFIX)
            if (!isRemoteAdmin && !isRemoteStudent) return

            val cleanName = info.endpointName.substringAfter("|").substringBefore("|")

            if (isAdmin) {
                updateStatus("Admin connecting to $cleanName...")
                connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
                return
            }

            if (isRemoteAdmin && !isAdmin) {
                updateStatus("Found Admin Notice Broadcaster. Connecting...")
                connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
                return
            }

            if (!isAdmin && isRemoteStudent) {
                runOnUiThread {
                    if (!discoveredDeviceViews.containsKey(endpointId)) {
                        val peerButton = Button(this@MainActivity).apply {
                            text = "Connect to Student: $cleanName"
                            setOnClickListener {
                                updateStatus("Requesting link with $cleanName...")
                                connectionsClient.requestConnection(localEndpointName, endpointId, connectionLifecycleCallback)
                            }
                        }
                        studentDevicesList.addView(peerButton)
                        discoveredDeviceViews[endpointId] = peerButton
                    }
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            runOnUiThread {
                discoveredDeviceViews.remove(endpointId)?.let { studentDevicesList.removeView(it) }
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val isRemoteAdmin = info.endpointName.startsWith(ADMIN_PREFIX)
            val isRemoteStudent = info.endpointName.startsWith(STUDENT_PREFIX)
            if (!isRemoteAdmin && !isRemoteStudent) {
                connectionsClient.rejectConnection(endpointId)
                updateStatus("Rejected unknown device: ${info.endpointName}")
                return
            }

            peerNames[endpointId] = info.endpointName
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpoints.add(endpointId)
                sendHealthPing(endpointId)

                runOnUiThread {
                    if (isAdmin) {
                        btnAdminBroadcast.isEnabled = true
                        val cleanName = peerNames[endpointId]?.substringAfter("|")?.substringBefore("|") ?: endpointId
                        val nodeView = TextView(this@MainActivity).apply {
                            text = "🟢 $cleanName"
                            setPadding(8, 8, 8, 8)
                            textSize = 14f
                        }
                        adminDevicesList.addView(nodeView)
                        adminConnectedViews[endpointId] = nodeView
                    } else {
                        btnStudentSend.isEnabled = true
                        discoveredDeviceViews.remove(endpointId)?.let { studentDevicesList.removeView(it) }
                    }
                    updateStatus("Connected to ${connectedEndpoints.size} active node(s)")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            peerScores.remove(endpointId)
            peerNames.remove(endpointId)

            runOnUiThread {
                adminConnectedViews.remove(endpointId)?.let { adminDevicesList.removeView(it) }

                val count = connectedEndpoints.size
                if (count == 0) {
                    if (isAdmin) btnAdminBroadcast.isEnabled = false else btnStudentSend.isEnabled = false
                }
                updateStatus(if (count > 0) "Connected to $count node(s)" else "Scanning for network...")
            }
            startAdvertising()
            startDiscovery()
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "CampusLinkFile_${System.currentTimeMillis()}"
    }

    private fun sendFile(uri: Uri) {
        if (connectedEndpoints.isEmpty()) return

        // RULE: Admin -> Students only. Student -> Students only. Never Student -> Admin.
        val eligiblePeers = if (isAdmin) {
            connectedEndpoints
        } else {
            connectedEndpoints.filter { peerNames[it]?.startsWith(ADMIN_PREFIX) != true }
        }

        if (eligiblePeers.isEmpty()) {
            updateStatus("No eligible recipients connected")
            return
        }

        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
            val filePayload = Payload.fromFile(pfd)
            val originalFileName = getFileName(uri)

            val transferId = UUID.randomUUID().toString()
            val metadataString = "${filePayload.id}:$transferId:$originalFileName"
            val metadataPayload = Payload.fromBytes(metadataString.toByteArray(Charsets.UTF_8))
            processedTransferIds.add(transferId)

            val bestPeerId = eligiblePeers.maxByOrNull { peerScores[it] ?: 0.0f }

            if (bestPeerId != null) {
                updateStatus("AI Routing... Transmitting via optimal path")
                connectionsClient.sendPayload(bestPeerId, metadataPayload)
                connectionsClient.sendPayload(bestPeerId, filePayload)
            }
        } catch (e: Exception) {
            updateStatus("Send failed: ${e.message}")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val data = String(payload.asBytes()!!, Charsets.UTF_8)

                    if (data.startsWith("PING|")) {
                        try {
                            val parts = data.split("|")
                            val batteryLevel = parts[1].split(":")[1].toFloat()
                            val uptime = parts[2].split(":")[1].toFloat()
                            val rssiVariance = getSimulatedRssiVariance()
                            peerScores[endpointId] = relayScorer.scoreRelay(rssiVariance, batteryLevel, uptime)
                        } catch (e: Exception) { }
                        return
                    }

                    val parts = data.split(":", limit = 3)
                    if (parts.size == 3) {
                        val payloadId = parts[0].toLongOrNull()
                        val transferId = parts[1]
                        val fileName = parts[2]
                        if (payloadId != null) {
                            incomingFileMeta[payloadId] = Pair(transferId, fileName)
                        }
                    }
                }
                Payload.Type.FILE -> {
                    pendingFilePayloads[payload.id] = payload
                    updateStatus("Receiving incoming file...")
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    val payload = pendingFilePayloads.remove(update.payloadId)
                    if (payload != null) {
                        val (transferId, originalName) = incomingFileMeta.remove(update.payloadId)
                            ?: Pair(UUID.randomUUID().toString(), "File_${System.currentTimeMillis()}")

                        val savedUri = saveToDownloadsAndNotify(payload, originalName)

                        val senderFullName = peerNames[endpointId] ?: ""
                        val isFromAdmin = senderFullName.startsWith(ADMIN_PREFIX)
                        val senderCleanName = senderFullName.substringAfter("|").substringBefore("|")
                        val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

                        runOnUiThread {
                            if (isFromAdmin && !isAdmin) {
                                val noticeItem = TextView(this@MainActivity).apply {
                                    text = "📌 $originalName\nFaculty: $senderCleanName | Received: $currentTime"
                                    setPadding(16, 16, 16, 16)
                                    textSize = 13f
                                }
                                noticeBoardList.addView(noticeItem, 0)
                            } else {
                                Toast.makeText(this@MainActivity, "Received $originalName from peer", Toast.LENGTH_SHORT).show()
                            }
                        }

                        if (savedUri != null && !processedTransferIds.contains(transferId)) {
                            processedTransferIds.add(transferId)

                            // RULE: forwarded files should never route back to Admin either
                            val bestNextNode = connectedEndpoints
                                .filter { it != endpointId && peerNames[it]?.startsWith(ADMIN_PREFIX) != true }
                                .maxByOrNull { peerScores[it] ?: 0.0f }

                            if (bestNextNode != null) {
                                updateStatus("Hopping file to next node...")
                                try {
                                    val pfd = contentResolver.openFileDescriptor(savedUri, "r")
                                    if (pfd != null) {
                                        val forwardPayload = Payload.fromFile(pfd)
                                        val metaPayload = Payload.fromBytes(
                                            "${forwardPayload.id}:$transferId:$originalName".toByteArray(Charsets.UTF_8)
                                        )
                                        connectionsClient.sendPayload(bestNextNode, metaPayload)
                                        connectionsClient.sendPayload(bestNextNode, forwardPayload)
                                    }
                                } catch (e: Exception) { }
                            }
                        }
                    }
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    pendingFilePayloads.remove(update.payloadId)
                    incomingFileMeta.remove(update.payloadId)
                    updateStatus("Transfer failed")
                }
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val percent = (update.bytesTransferred * 100 / update.totalBytes.coerceAtLeast(1))
                    updateStatus("Transferring: $percent%")
                }
                else -> {}
            }
        }
    }

    private fun saveToDownloadsAndNotify(payload: Payload, fileName: String): Uri? {
        try {
            val payloadFile = payload.asFile() ?: return null
            val pfd = payloadFile.asParcelFileDescriptor() ?: return null
            val sourceStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CampusLink")
                }
            }

            val destUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (destUri == null) {
                sourceStream.close()
                return null
            }
            resolver.openOutputStream(destUri)?.use { outStream -> sourceStream.copyTo(outStream) }
            sourceStream.close()

            updateStatus("Saved: $fileName")
            showFileReceivedNotification(fileName, destUri)
            return destUri
        } catch (e: Exception) {
            return null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "File Transfers", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun showFileReceivedNotification(fileName: String, fileUri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, contentResolver.getType(fileUri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Campus Broadcast Received")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: SecurityException) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        relayScorer.close()
        connectionsClient.stopAllEndpoints()
    }
}