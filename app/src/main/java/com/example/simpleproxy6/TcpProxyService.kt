package com.example.simpleproxy6

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class Config(
    val localPort: Int,
    val localIP: String,
    val remotePort: Int,
    val remoteIP: String,
    val bortID: String,
    val bortName: String,
    val password: String
)

class TcpProxyService: Service() {
    companion object {
        private const val CHANNEL_ID = "ForegroundServiceChannel"
        private const val NOTIFICATION_ID = 1
        const val TAG = "TcpProxyService"
        private const val HEX_CHARS = "0123456789ABCDEF"

        private var instance: TcpProxyService? = null

        fun getInstance(): TcpProxyService? {
            return instance
        }
    }

    private var serverSocket: ServerSocket? = null
    private var singleClientSocket: Socket? = null
    private lateinit var config: Config
    private val handler = Handler(Looper.getMainLooper())

    private val statusText = AtomicReference("Status: --")
    private val clientsCount = AtomicInteger(0)
    private val bytesReceived = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val authenticatedClients = ConcurrentHashMap<Socket, Boolean>()
    private val clientTimeouts = ConcurrentHashMap<Socket, Long>()
    private var notificationUpdaterHandle: ScheduledFuture<*>? = null
    private var scheduler: ScheduledExecutorService? = null

    //timeout of a client socket
    //it will remain open even when there may be absence of data
    private val TIMEOUT_THRESHOLD = TimeUnit.MINUTES.toMillis(5)


    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configUriString = intent?.getStringExtra("configUri")
        val configUri = Uri.parse(configUriString)
        initConfig(configUri)

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        startTcpProxy()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    fun stopServerAndReleaseResources() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopScheduledTask()
        stopTcpProxy()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        notificationManager.deleteNotificationChannel(CHANNEL_ID)

        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TCP proxy server",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, NotificationBroadcastReceiver::class.java)
        stopIntent.action = NotificationBroadcastReceiver.ACTION_STOP_SERVICE
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationLayout = RemoteViews(packageName, R.layout.notification_layout)
        notificationLayout.setOnClickPendingIntent(R.id.stopButton, stopPendingIntent)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TCP proxy server")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setCustomContentView(notificationLayout)
            .setCustomBigContentView(notificationLayout)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setDefaults(0)
            .setSound(null)
            .build()
    }

    private fun updateNotification() {
        handler.post {
            val notificationLayout = RemoteViews(packageName, R.layout.notification_layout)
            notificationLayout.setTextViewText(R.id.status, statusText.get())
            notificationLayout.setTextViewText(R.id.clientsCount, "Clients: ${clientsCount.get()}")
            notificationLayout.setTextViewText(R.id.bytesReceived, "Rx: ${bytesReceived.get()} B")
            notificationLayout.setTextViewText(R.id.bytesSent, "Tx: ${bytesSent.get()} B")

            val stopIntent = Intent(this, NotificationBroadcastReceiver::class.java)
            stopIntent.action = NotificationBroadcastReceiver.ACTION_STOP_SERVICE
            val stopPendingIntent = PendingIntent.getBroadcast(
                this,
                NOTIFICATION_ID,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            notificationLayout.setOnClickPendingIntent(R.id.stopButton, stopPendingIntent)

            val updatedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("TCP proxy server")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setCustomContentView(notificationLayout)
                    .setCustomBigContentView(notificationLayout)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setDefaults(0)
                    .setSound(null)
                    .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, updatedNotification)
        }
    }

    private fun startTcpProxy() {
        serverSocket = ServerSocket(config.localPort, 0, InetAddress.getByName(config.localIP))

        scheduler = Executors.newScheduledThreadPool(1)
        val notificationUpdater = Runnable {
            updateNotification()
        }

        val notificationUpdateInterval = 2000
        notificationUpdaterHandle = scheduler?.scheduleAtFixedRate(notificationUpdater, 0, notificationUpdateInterval.toLong(), TimeUnit.MILLISECONDS)

        Thread {
            try {
                while (true) {
                    statusText.set("Local: ${serverSocket?.localSocketAddress}\nRemote: ${config.remoteIP}:${config.remotePort} (${config.bortName})")
                    val newClientSocket = serverSocket?.accept()
                    if (newClientSocket != null) {
                        if (singleClientSocket != null) {
                            Log.d(TAG, "Disconnecting current client, connecting new one")
                            singleClientSocket!!.close()
                        }

                        singleClientSocket = newClientSocket
                        handleClient(singleClientSocket!!)
                    }

                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            val targetSocket = Socket(config.remoteIP, config.remotePort)
            if (!authenticatedClients.containsKey(clientSocket)) {
                if (!authenticateAtRemoteServer(targetSocket)) {
                    clientSocket.close()
                    targetSocket.close()

                    val errMsg = "Authentication at the remote server failed"
                    Log.d(TAG, errMsg)
                    Toast.makeText(this, errMsg, Toast.LENGTH_SHORT).show()

                    return
                }

                authenticatedClients[clientSocket] = true
                Log.d(TAG, "Authentication at the remote server succeeded.")
            }

            clientsCount.incrementAndGet()
            updateNotification()

            clientTimeouts[clientSocket] = System.currentTimeMillis()
            val clientToRemoteThread = proxyData(clientSocket.getInputStream(), targetSocket.getOutputStream(), false, clientSocket)
            val remoteToClientThread = proxyData(targetSocket.getInputStream(), clientSocket.getOutputStream(), true, clientSocket)

            clientToRemoteThread.join()
            remoteToClientThread.join()

            clientSocket.close()
            targetSocket.close()
            authenticatedClients.remove(clientSocket)

            clientsCount.decrementAndGet()
            updateNotification()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        //todo - catch socket error too
    }

    private fun proxyData(input: InputStream, output: OutputStream, isInbound: Boolean, clientSocket: Socket): Thread {
        val thread = Thread {
            try {
                val buffer = ByteArray(1024)
                var bytesRead: Int

                // Set the timeout to 3 seconds (adjust as needed)
                clientSocket.soTimeout = 3000
                while (true) {
                    val availableBytes = input.available()
                    if (availableBytes > 0) {
                        bytesRead = input.read(buffer, 0, minOf(availableBytes, buffer.size))
                        if (bytesRead == -1) {
                            if (checkTimeoutAndExitListening(clientSocket)) {
                                break
                            } else {
                                Thread.sleep(0, 100000)
                                continue
                            }
                        }

                        output.write(buffer, 0, bytesRead)
                        output.flush()

                        if (isInbound) {
                            bytesReceived.addAndGet(bytesRead.toLong())
                        } else {
                            bytesSent.addAndGet(bytesRead.toLong())
                        }

                        updateLastActivityTime(clientSocket)
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                try {
                    input.close()
                    output.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        thread.start()
        return thread
    }

    private fun authenticateAtRemoteServer(socket: Socket): Boolean {
        return try {
            val credentialsBytes = hexStringToByteArray(config.bortID) + hexStringToByteArray(config.password) + hexStringToByteArray("10")
            val outputStream = socket.getOutputStream()
            outputStream.write(credentialsBytes)
            outputStream.flush()

            val inputStream = socket.getInputStream()
            val responseBuffer = ByteArray(4)
            val bytesRead = inputStream.read(responseBuffer)

            if (bytesRead == 4) {
                val response = String(responseBuffer, StandardCharsets.UTF_8)
                Log.d(TAG, "Authentication response from server: $response")

                response.equals("OK\r\n", ignoreCase = true)
            } else {
                false
            }
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    private fun updateLastActivityTime(socket: Socket) {
        clientTimeouts[socket] = System.currentTimeMillis()
    }

    private fun checkTimeoutAndExitListening(socket: Socket): Boolean {
        val lastActivityTime = clientTimeouts[socket] ?: 0
        val idleTime = System.currentTimeMillis() - lastActivityTime

        return if (idleTime >= TIMEOUT_THRESHOLD) {
            Log.d(TAG, "Closing socket due to client timeout.")
            clientTimeouts.remove(socket)
            true
        } else {
            false
        }
    }

    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(hexString.length / 2)

        for (i in 0 until hexString.length step 2) {
            val highNibble = HEX_CHARS.indexOf(hexString[i].uppercaseChar())
            val lowNibble = HEX_CHARS.indexOf(hexString[i + 1].uppercaseChar())
            result[i / 2] = ((highNibble shl 4) or lowNibble).toByte()
        }

        return result
    }

    private fun stopTcpProxy() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        try {
            singleClientSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun stopScheduledTask() {
        notificationUpdaterHandle?.cancel(true)
        scheduler?.shutdownNow()
    }

    private fun initConfig(configUri: Uri) {
        val inputStream = contentResolver.openInputStream(configUri)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val content = reader.use { it.readText() }
        inputStream?.close()

        val jsonObject = JSONObject(content)
        config = Config(
            localPort = jsonObject.getInt("local_port"),
            localIP = jsonObject.getString("local_ip"),
            remotePort = jsonObject.getInt("remote_port"),
            remoteIP = jsonObject.getString("remote_ip"),
            bortID = jsonObject.getString("bort_id"),
            bortName = jsonObject.getString("bort_name"),
            password = jsonObject.getString("password")
        )
    }
}
