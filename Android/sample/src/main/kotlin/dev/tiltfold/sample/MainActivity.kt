package dev.tiltfold.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tiltfold.TiltFold
import dev.tiltfold.TiltFoldConfig
import dev.tiltfold.rememberTiltMonitor
import dev.tiltfold.tiltFoldBlurSupported

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TiltFoldSample() }
    }
}

/**
 * The smallest thing that shows the effect honestly: live Compose content, a black background for
 * it to dissolve into, and a readout that stays put so you can see what the numbers are doing.
 *
 * Tilt the device left or right. On a device with no gravity or accelerometer sensor — most
 * emulators, unless you open the virtual sensors panel — drag horizontally instead; the toggle at
 * the bottom switches between the two.
 */
@Composable
fun TiltFoldSample() {
    val tilt = rememberTiltMonitor()
    val dragRoll = remember { mutableStateOf(0f) }
    var useSensor by remember { mutableStateOf(tilt.isAvailable) }
    val density = LocalDensity.current.density

    val roll = if (useSensor) tilt.roll else dragRoll.value
    val config = TiltFoldConfig()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(useSensor) {
                    if (useSensor) return@pointerInput
                    detectHorizontalDragGestures { change, dragAmount ->
                        change.consume()
                        // A quarter of a degree per dp of travel: a full screen width is roughly
                        // the whole usable range.
                        val degrees = dragAmount / density * 0.25f
                        dragRoll.value = (dragRoll.value + degrees).coerceIn(-90f, 90f)
                    }
                }
        ) {
            // Everything inside this lambda is composed once per blur level (four times by
            // default), so it must not own state of its own. Anything stateful belongs outside,
            // passed in, exactly like `roll` is here.
            TiltFold(roll = roll, modifier = Modifier.fillMaxSize(), config = config) {
                DemoContent()
            }
        }

        // Deliberately outside the fold: a readout that dissolved along with everything else
        // would be useless at exactly the moment you want to read it.
        Readout(
            roll = roll,
            progress = config.progress(roll.toDouble()),
            rootEdge = if (roll < 0f) "left" else "right",
            useSensor = useSensor,
            sensorAvailable = tilt.isAvailable,
            onToggle = { useSensor = !useSensor },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

/** A card and a list: ordinary Compose content, nothing about it knows the effect exists. */
@Composable
private fun DemoContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BasicText(
            text = "Thursday",
            style = TextStyle(color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        )
        BasicText(
            text = "11 September",
            style = TextStyle(color = Color(0xFF9AA0A6), fontSize = 16.sp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1E3A8A), Color(0xFF3B2E63))
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicText(
                text = "The picture is not folding",
                style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            )
            BasicText(
                text = "It is hanging in space. Tilting the device does not move the picture, " +
                    "it moves your eye. The far part recedes, goes out of focus, and leaves " +
                    "the window.",
                style = TextStyle(color = Color(0xFFD7DBE0), fontSize = 15.sp, lineHeight = 21.sp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        BasicText(
            text = "Today",
            style = TextStyle(color = Color(0xFF9AA0A6), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        )

        // A plain Column rather than a LazyColumn on purpose. A lazy list owns scroll state, and
        // this subtree is composed once per blur level, so four independent lists would scroll
        // independently and the ladder would come apart. Hoist the state, or keep it static.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ListRow(Color(0xFF34A853), "Stand-up", "9:30 - 9:45")
            ListRow(Color(0xFFEA4335), "Design review", "11:00 - 12:00")
            ListRow(Color(0xFFFBBC04), "Lunch", "12:30")
            ListRow(Color(0xFF4285F4), "Ship the Android port", "15:00 - 16:30")
            ListRow(Color(0xFF9334E6), "Read the spec again", "17:00")
        }
    }
}

@Composable
private fun ListRow(accent: Color, title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF16181C))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            BasicText(
                text = title,
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            )
            BasicText(
                text = detail,
                style = TextStyle(color = Color(0xFF80868B), fontSize = 13.sp)
            )
        }
    }
}

@Composable
private fun Readout(
    roll: Float,
    progress: Double,
    rootEdge: String,
    useSensor: Boolean,
    sensorAvailable: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = when {
        !sensorAvailable -> "no sensor, drag to tilt"
        useSensor -> "gravity sensor, tap to drag instead"
        else -> "drag, tap to use the sensor"
    }
    val blur = if (tiltFoldBlurSupported) "blur ladder on" else "no blur below API 31"

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC101214))
            .clickable(enabled = sensorAvailable, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            text = "roll %.1f°   progress %.2f   root %s".format(roll, progress, rootEdge),
            style = TextStyle(color = Color.White, fontSize = 14.sp)
        )
        BasicText(
            text = "$source · $blur",
            style = TextStyle(color = Color(0xFF80868B), fontSize = 12.sp)
        )
    }
}
