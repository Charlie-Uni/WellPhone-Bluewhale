package com.octopus.agent.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

class ShizukuConnector(context: Context) {
    @Volatile private var accepting = false
    private val mutableService = MutableStateFlow<IShellService?>(null)
    val service = mutableService.asStateFlow()
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ShellService::class.java))
        .daemon(false).processNameSuffix("shell").debuggable(false).version(4)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if(accepting) mutableService.value = IShellService.Stub.asInterface(binder)
            else runCatching { Shizuku.unbindUserService(args, this, true) }
        }
        override fun onServiceDisconnected(name: ComponentName) { mutableService.value = null }
    }
    private val deadListener = Shizuku.OnBinderDeadListener { mutableService.value = null }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (accepting && code == 42 && result == PackageManager.PERMISSION_GRANTED) bind()
    }
    init {
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }
    fun connect() {
        check(Shizuku.pingBinder()) { "请先启动 Shizuku" }
        check(Shizuku.getUid() == 2000) { "请使用无线调试启动 Shizuku；当前仅接受 shell UID 2000" }
        accepting = true
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
        else Shizuku.requestPermission(42)
    }
    private fun bind() { Shizuku.bindUserService(args, connection) }
    fun disconnect() {
        accepting = false
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        mutableService.value = null
    }
    fun close() {
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        disconnect()
    }
}
