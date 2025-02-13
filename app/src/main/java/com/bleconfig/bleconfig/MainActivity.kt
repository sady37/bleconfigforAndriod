// 文件路径: app/src/main/java/com/bleconfig/bleconfig/MainActivity.kt
package com.bleconfig.bleconfig

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import com.bleconfig.bleconfig.ui.theme.BleconfigTheme

class MainActivity : ComponentActivity() {
    private val PERMISSION_REQUEST_CODE = 100

    private val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 先检查权限
        checkAndRequestPermissions()

        setContent {
            BleconfigTheme {
                // 在这里实现您的UI界面
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val notGrantedPermissions = blePermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGrantedPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                notGrantedPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            // 所有权限已获得,开始初始化
            initializeBle()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // 所有权限都已获得
                initializeBle()
            } else {
                // 显示错误提示
                Toast.makeText(this, "Bluetooth permissions are required for this feature", Toast.LENGTH_SHORT).show()
                // 可以考虑关闭应用或禁用部分功能
            }
        }
    }

    private fun initializeBle() {
        // 在这里初始化BLE相关功能
        // 例如：初始化蓝牙适配器，启动扫描等
    }
}