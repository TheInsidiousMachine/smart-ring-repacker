package com.smartring.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.smartring.core.ConnectionState
import com.smartring.core.RingServices
import com.smartring.ui.theme.RingTheme
import com.smartring.ui.vm.RingAppViewModel

class RingAppActivity : ComponentActivity() {
    private val vm: RingAppViewModel by lazy {
        ViewModelProvider(
            this,
            RingAppViewModel.factory(application, RingServices.ring, RingServices.studio),
        )[RingAppViewModel::class.java]
    }

    private val permissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) vm.connect()
            else Toast.makeText(this, "Bluetooth permission is needed to connect", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(0xFF100E0E.toInt())
        enableEdgeToEdge()
        setContent {
            RingTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RingApp(vm = vm, onConnect = ::requestConnect)
                }
            }
        }
        val underTest = Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
        if (!underTest && vm.connection.value.state == ConnectionState.DISCONNECTED) requestConnect()
    }

    private fun requestConnect() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.connect() else permissionLauncher.launch(missing.toTypedArray())
    }
}
