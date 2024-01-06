package com.example.simpleproxy6

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.net.URI
import java.nio.file.Paths

class MainActivity : Activity() {
    companion object {
        const val READ_REQUEST_CODE = 42
        private const val PREFS_NAME = "ConfigSelectionPrefs"
        private const val PREF_LAST_CONFIG_URI = "lastConfigUri"
    }

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        showConfigPicker()
    }

    private fun showConfigPicker() {
        val lastConfigUriString = sharedPreferences.getString(PREF_LAST_CONFIG_URI, null)
        val msg = if (lastConfigUriString != null) {
            val uriObj = URI(lastConfigUriString)
            val path = Paths.get(uriObj.path)
            path.fileName.toString()
        } else {
            "[none]"
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select Config file")
            .setMessage("Latest: $msg")
            .setPositiveButton("Latest") { _, _ ->
                openLatestConfig()
            }
            .setNegativeButton("New") { _, _ ->
                selectConfigFile()
            }
            .setOnCancelListener {
                finish()
            }

        val dialog = builder.create()
        dialog.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (requestCode == READ_REQUEST_CODE && resultCode == RESULT_OK) {
            resultData?.data?.also { uri ->
                finish()
                startTcpProxyService(uri)
            }
        } else {
            finish()
        }
    }

    private fun startTcpProxyService(configUri: Uri) {
        saveLastConfigUri(configUri)
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(configUri, takeFlags)
        val serviceIntent = Intent(this, TcpProxyService::class.java).apply {
            putExtra("configUri", configUri.toString())
        }

        // Compatibility with Android S and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(this, "TCP proxy server is starting...", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun openLatestConfig() {
        val lastConfigUriString = sharedPreferences.getString(PREF_LAST_CONFIG_URI, null)

        if (!lastConfigUriString.isNullOrBlank()) {
            val lastConfigUri = Uri.parse(lastConfigUriString)
            startTcpProxyService(lastConfigUri)
        } else {
            selectConfigFile()
        }
    }

    private fun selectConfigFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json"))
        }

        startActivityForResult(intent, READ_REQUEST_CODE)
    }

    private fun saveLastConfigUri(uri: Uri) {
        val editor = sharedPreferences.edit()
        editor.putString(PREF_LAST_CONFIG_URI, uri.toString())
        editor.apply()
    }
}