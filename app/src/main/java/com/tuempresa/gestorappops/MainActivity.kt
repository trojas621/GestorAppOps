package com.tuempresa.gestorappops

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import java.io.BufferedReader
import java.io.InputStreamReader

val StandardAppOps = listOf(
    "CAMERA", "RECORD_AUDIO", "READ_CLIPBOARD", "RUN_IN_BACKGROUND", 
    "FINE_LOCATION", "COARSE_LOCATION", "WRITE_SETTINGS", "VIBRATE",
    "SYSTEM_ALERT_WINDOW", "WAKE_LOCK", "PROJECT_MEDIA"
)

class MainActivity : ComponentActivity(), OnRequestPermissionResultListener {

    private val REQUEST_CODE_SHIZUKU = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF5C8DF6),
                    background = Color(0xFF000000),
                    surface = Color(0xFF1C1C1E)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppOpsScreen(
                        checkShizukuPermission = { checkShizukuPermission() },
                        requestShizukuPermission = { requestShizukuPermission() },
                        packageManager = packageManager
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(this)
    }

    private fun checkShizukuPermission(): Boolean {
        if (Shizuku.isPreV11()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun requestShizukuPermission() {
        if (Shizuku.getVersion() >= 11 && !checkShizukuPermission()) {
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppOpsScreen(
    checkShizukuPermission: () -> Boolean,
    requestShizukuPermission: () -> Unit,
    packageManager: PackageManager
) {
    var hasPermission by remember { mutableStateOf(checkShizukuPermission()) }
    var appsList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<AppInfo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showSystemApps by remember { mutableStateOf(false) }

    LaunchedEffect(hasPermission, showSystemApps) {
        if (hasPermission) {
            isLoading = true
            appsList = loadInstalledApps(packageManager, showSystemApps)
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Privacidad Samsung", fontWeight = FontWeight.Bold) },
                actions = {
                    if (hasPermission && selectedApp == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Sistema", fontSize = 12.sp, color = Color.Gray)
                            Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (!hasPermission) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Button(onClick = requestShizukuPermission) { Text("Vincular con Shizuku") }
                }
            } else if (isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (selectedApp == null) {
                LazyColumn {
                    items(appsList) { app ->
                        ListItem(
                            headlineContent = { Text(app.name) },
                            supportingContent = { Text(app.packageName) },
                            modifier = Modifier.clickable { selectedApp = app }
                        )
                    }
                }
            } else {
                AppDetailsScreen(app = selectedApp!!, onBack = { selectedApp = null })
            }
        }
    }
}

@Composable
fun AppDetailsScreen(app: AppInfo, onBack: () -> Unit) {
    var appOpsList by remember { mutableStateOf<List<AppOpState>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(true) }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            appOpsList = fetchAppOps(app.packageName)
            isRefreshing = false
        }
    }

    Column {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
            Text(app.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        if (isRefreshing) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            LazyColumn {
                items(appOpsList) { op ->
                    Card(Modifier.padding(8.dp).fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(op.opName, fontWeight = FontWeight.Bold)
                            Text("Estado actual: ${op.currentMode}")
                            Row {
                                Button(onClick = { setAppOpMode(app.packageName, op.opName, "allow"); isRefreshing = true }) { Text("Permitir") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { setAppOpMode(app.packageName, op.opName, "ignore"); isRefreshing = true }) { Text("Ignorar") }
                            }
                        }
                    }
                }
            }
        }
    }
}

suspend fun loadInstalledApps(pm: PackageManager, showSystem: Boolean): List<AppInfo> = withContext(Dispatchers.IO) {
    pm.getInstalledApplications(PackageManager.GET_META_DATA).mapNotNull {
        val isSys = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isSys && !showSystem) null else AppInfo(pm.getApplicationLabel(it).toString(), it.packageName, isSys)
    }.sortedBy { it.name }
}

suspend fun fetchAppOps(pkg: String): List<AppOpState> = withContext(Dispatchers.IO) {
    val list = mutableListOf<AppOpState>()
    try {
        val p = Shizuku.newProcess(arrayOf("sh", "-c", "cmd appops get $pkg"), null, null)
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        reader.readLines().forEach { line ->
            if (line.contains(":")) {
                val parts = line.split(":")
                list.add(AppOpState(parts[0].trim(), parts[1].trim()))
            }
        }
    } catch (e: Exception) {}
    if (list.isEmpty()) StandardAppOps.forEach { list.add(AppOpState(it, "default")) }
    list
}

fun setAppOpMode(pkg: String, op: String, mode: String) {
    try { Shizuku.newProcess(arrayOf("sh", "-c", "cmd appops set $pkg $op $mode"), null, null).waitFor() } catch (e: Exception) {}
}

data class AppInfo(val name: String, val packageName: String, val isSystem: Boolean)
data class AppOpState(val opName: String, val currentMode: String)
