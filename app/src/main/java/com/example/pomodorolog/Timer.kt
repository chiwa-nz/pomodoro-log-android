package com.example.pomodorolog

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.toggl.komposable.architecture.ReduceResult
import com.toggl.komposable.architecture.Reducer
import com.toggl.komposable.extensions.withoutEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.Long
import kotlin.math.ceil
import kotlin.math.floor

sealed class TimerAction {
    data object MainButtonTapped : TimerAction()
    data object ResetButtonTapped : TimerAction()
    data object TimerDecremented : TimerAction()
    data class TimerIncremented(val increment: Long) : TimerAction()
    data class TimerJobSet(val job: Job) : TimerAction()
    data object TimerJobCancelled : TimerAction()
    data class LoopingToggled(val enabled: Boolean) : TimerAction()
}

enum class Status {
    Ongoing,
    Paused,
    Stopped,
    Finished,
}

enum class Mode(val length: Long) {
    SuperShortTest(500),
    ShortTest(1000),
    Test(6000),
    Work(1500000),
    Break(300000),
    Idle(0),
}

data class TimerState(
    val status: Status = Status.Stopped,
    val mode: Mode = Mode.SuperShortTest,
    val elapsedMilliseconds: Long = 0,
    val remainingMilliseconds: Long = 0,
    val timerJob: Job? = null,
    val loopingEnabled: Boolean = true,
)

class TimerReducer : Reducer<TimerState, TimerAction> {
    override fun reduce(state: TimerState, action: TimerAction): ReduceResult<TimerState, TimerAction> =
        when (action) {
            TimerAction.MainButtonTapped -> {
//                Handler(Looper.getMainLooper()).postDelayed({
//                    timerStore.send(TimerAction.TimerDecremented)
//                }, 1000)
                when (state.status) {
                    Status.Stopped, Status.Finished -> {
                        state.copy(
                            status = Status.Ongoing,
                            elapsedMilliseconds = 0,
                        ).withoutEffect()
                    }
                    Status.Ongoing -> {
                        state.copy(
                            status = Status.Paused,
                        ).withoutEffect()
                    }
                    Status.Paused -> {
                        state.copy(
                            status = Status.Ongoing,
                        ).withoutEffect()
                    }
                }
            }
            TimerAction.ResetButtonTapped -> {
                state.timerJob?.cancel()
                state.copy(
                    status = Status.Stopped,
                    elapsedMilliseconds = 0,
                    remainingMilliseconds = 0,
                    timerJob = null,
                ).withoutEffect()
            }
            TimerAction.TimerDecremented -> {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (state.status == Status.Ongoing && state.remainingMilliseconds > 0) {
                        timerStore.send(TimerAction.TimerDecremented)
                    }
                }, 1000)
                if (state.status != Status.Ongoing) {
                    state.withoutEffect()
                } else if (state.remainingMilliseconds.toInt() <= 1) {
                    state.copy(
                        status = Status.Stopped,
                        remainingMilliseconds = 0
                    ).withoutEffect()
                } else {
                    state.copy(remainingMilliseconds = state.remainingMilliseconds - 1).withoutEffect()
                }
            }
            is TimerAction.TimerIncremented -> {
                if (state.elapsedMilliseconds + action.increment >= state.mode.length) {
                    if (state.loopingEnabled) {
                        state.copy(
                            status = Status.Ongoing,
                            elapsedMilliseconds = 0,
                        ).withoutEffect()
                    } else {
                        state.timerJob?.cancel()
                        state.copy(
                            status = Status.Finished,
                            elapsedMilliseconds = state.mode.length,
                        ).withoutEffect()
                    }
                } else {
                    state.copy(elapsedMilliseconds = state.elapsedMilliseconds + action.increment).withoutEffect()
                }
            }
            is TimerAction.TimerJobSet -> {
                state.copy(timerJob = action.job).withoutEffect()
            }
            TimerAction.TimerJobCancelled -> {
                state.timerJob?.cancel()
                state.copy(timerJob = null).withoutEffect()
            }
            is TimerAction.LoopingToggled -> {
                state.copy(loopingEnabled = action.enabled).withoutEffect()
            }
        }
}

fun calculatePercentageOld(state: TimerState): Double {
    return ((0 - state.remainingMilliseconds) + state.mode.length) * 1.0 / state.mode.length
}

fun calculatePercentage(state: TimerState): Double {
    return state.elapsedMilliseconds * 1.0 / state.mode.length
}

@Composable
fun Timer(
    state: TimerState,
    modifier: Modifier = Modifier
) {
    val minutes = floor((state.mode.length - state.elapsedMilliseconds) / 6000.0)
    val seconds = (state.mode.length - state.elapsedMilliseconds) - (minutes * 6000)
    val timerScope = rememberCoroutineScope() // Create a coroutine scope
    val progress = calculatePercentage(state)
    val debug = false
    Column (
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box (
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(bottom = 8.dp)
        ) {
            val image = painterResource(R.drawable.tomato)
            val progressColour =
                if (progress >= 1) Color.Green
                else Color.Red
            CircularProgressIndicator(
                progress = { progress.toFloat() },
//                progress = { 1.toFloat() },
                color = progressColour,
                trackColor = Color.Transparent,
                strokeWidth = 18.dp,
                modifier = Modifier
                    .width(128.dp)
                    .padding(bottom = 76.dp)
//                    .padding(bottom = 88.dp)
            )
            Image(
                painter = image,
                contentDescription = "tomato",
                modifier = Modifier
                    .size(108.dp)
            )
        }
        Text(
            text = "%d:%02.0f".format(minutes.toInt(), ceil(seconds / 100))
        )
        if (debug) {
            Text(
                text = "%.0f".format(progress * 100) + "%"
            )
            Text(
                text = "${state.elapsedMilliseconds}"
            )
            Text(
                text = "${state.mode.length}"
            )
            Text(
                text = "Status: ${state.status}"
            )
            Text(
                text = "Mode: ${state.mode}"
            )
        }
        val mainButtonStringResource =
            if (state.status == Status.Ongoing) R.string.timer_pause
            else if (state.status == Status.Paused) R.string.timer_resume
            else R.string.timer_start
        Row() {
            Button(onClick = {
                timerStore.send(TimerAction.MainButtonTapped)
                if (state.status != Status.Ongoing) {
                    timerStore.send(TimerAction.TimerJobSet(timerScope.launch {
                        while (true) {
                            timerStore.send(TimerAction.TimerIncremented(1))
                            delay(10)
                        }
                    }))
                } else {
                    timerStore.send(TimerAction.TimerJobCancelled)
                }
            }) {
                Text(stringResource(mainButtonStringResource))
            }
            Button(
                modifier = Modifier
                    .padding(start = 8.dp),
                onClick = { timerStore.send(TimerAction.ResetButtonTapped) }
            ) {
                Text(stringResource(R.string.timer_reset))
            }
        }
        Row (
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Looping enabled?",
                modifier = Modifier
                    .padding(end = 16.dp)
            )
            Switch(
                checked = state.loopingEnabled,
                onCheckedChange = {
                    timerStore.send(TimerAction.LoopingToggled(it))
                }
            )
        }
    }
}
