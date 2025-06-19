package com.example.pomodorolog

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.toggl.komposable.architecture.Reducer
import com.toggl.komposable.extensions.createStore
import com.toggl.komposable.scope.DispatcherProvider
import com.toggl.komposable.scope.StoreScopeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        bluetoothStore.send(BluetoothAction.ActivityCreated(this))
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
            ) {
                PomodoroApp(
                    context = applicationContext,
                    pm = packageManager,
                )
            }
        }
    }
}

val timerAppReducer: Reducer<TimerState, TimerAction> = TimerReducer()
val bluetoothAppReducer: Reducer<BluetoothState, BluetoothAction> = BluetoothReducer()

val dispatcherProvider = DispatcherProvider(
    io = Dispatchers.IO,
    computation = Dispatchers.Default,
    main = Dispatchers.Main,
)
val coroutineScope = object : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = dispatcherProvider.main
}
val storeScopeProvider = StoreScopeProvider { coroutineScope }
val timerStore = createStore(
    initialState = TimerState(),
    reducer = timerAppReducer,
    storeScopeProvider = storeScopeProvider,
    dispatcherProvider = dispatcherProvider,
)
val bluetoothStore = createStore(
    initialState = BluetoothState(),
    reducer = bluetoothAppReducer,
    storeScopeProvider = storeScopeProvider,
    dispatcherProvider = dispatcherProvider,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BasePage(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    pageTitle: String? = null,
    titleColour: Color? = null
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = pageTitle ?: "",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = titleColour ?: Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            content()
        }
    }
}

@Preview
@Composable
fun PomodoroApp(
    modifier: Modifier = Modifier,
    context: Context? = null,
    pm: PackageManager? = null,
) {
    val timerState by timerStore.state.collectAsState(initial = TimerState())
    val bluetoothState by bluetoothStore.state.collectAsState(initial = BluetoothState())
    BasePage(
        modifier = modifier,
        titleColour = Color.Red,
        pageTitle = "Timer App",
        content = {
            Column (
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Box (
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Timer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        state = timerState
                    )
                }
                BluetoothMain(
                    state = bluetoothState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    context = context
                )
            }
        }
    )
}