package uz.rahmat.nfc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import uz.rahmat.nfc.ui.theme.MyApplicationTheme
import uz.rahmat.nfcreader.NfcCardReaderSdk
import uz.rahmat.nfcreader.NfcScanState

class MainActivity : ComponentActivity() {
    private val nfcSdk = NfcCardReaderSdk()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcSdk.attach(this)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) {
                    setContent {
                        val state by nfcSdk.state.collectAsState()
                        ScanScreen(
                            state = state,
                            onStart = { nfcSdk.startScan() },
                            onStop = { nfcSdk.stopScan() }
                        )
                    }
                }
            }
        }
    }

}


@Composable
fun ScanScreen(
    state: NfcScanState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        when (state) {

            is NfcScanState.Idle -> {
                Text("Ready")

                Spacer(Modifier.height(16.dp))

                Button(onClick = onStart) {
                    Text("Start scan")
                }
            }

            is NfcScanState.Scanning -> {
                Text("Tap card...")

                Spacer(Modifier.height(16.dp))

                CircularProgressIndicator()

                Spacer(Modifier.height(16.dp))

                Button(onClick = onStop) {
                    Text("Stop scan")
                }
            }

            is NfcScanState.Success -> {
                Text("PAN: ${state.result.pan}")
                Text("Expiry: ${state.result.expiry}")
                Text("Scheme: ${state.result.scheme}")

                Spacer(Modifier.height(16.dp))

                Button(onClick = onStart) {
                    Text("Scan again")
                }
            }

            is NfcScanState.Error -> {
                Text("Error: ${state.throwable.message}")

                Spacer(Modifier.height(16.dp))

                Button(onClick = onStart) {
                    Text("Retry")
                }
            }

            is NfcScanState.NfcUnavailable -> {

            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme {
//        Greeting("Android")
    }
}