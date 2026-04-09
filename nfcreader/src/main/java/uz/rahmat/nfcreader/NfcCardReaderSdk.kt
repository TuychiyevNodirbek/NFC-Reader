package uz.rahmat.nfcreader

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NfcCardReaderSdk {

    private val TAG = "NfcCardReaderSdk"
    private var nfcAdapter: NfcAdapter? = null
    private var activity: Activity? = null

    private val _state = MutableStateFlow<NfcScanState>(NfcScanState.Idle)
    val state: StateFlow<NfcScanState> = _state

    private var isScanningEnabled = false

    private val lifecycleObserver = object : DefaultLifecycleObserver {

        override fun onResume(owner: LifecycleOwner) {
            if (isScanningEnabled) startInternal()
        }

        override fun onPause(owner: LifecycleOwner) {
            stopInternal()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            cleanup()
        }
    }

    fun attach(activity: Activity) {
        Log.d(TAG, "attach called")

        this.activity = activity

        (activity as? LifecycleOwner)?.lifecycle?.addObserver(lifecycleObserver)

        nfcAdapter = NfcAdapter.getDefaultAdapter(activity)

        if (nfcAdapter == null) {
            Log.e(TAG, "NFC is not supported on this device")
        } else {
            Log.d(TAG, "NFC adapter initialized")
        }
    }

    fun isNfcEnabled(): Boolean {
        val adapter = nfcAdapter
        return adapter != null && adapter.isEnabled
    }

    fun enableAutoScan(enabled: Boolean) {
        isScanningEnabled = enabled
    }

    fun openNfcSettings() {
        val activity = activity ?: return

        try {
            val intent = Intent(Settings.ACTION_NFC_SETTINGS)
            activity.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
            activity.startActivity(intent)
        }
    }

    fun getNfcStatus(): NfcStatus {
        val adapter = nfcAdapter

        return when {
            adapter == null -> NfcStatus.NOT_SUPPORTED
            !adapter.isEnabled -> NfcStatus.DISABLED
            else -> NfcStatus.ENABLED
        }
    }

    fun startScan() {
        val status = getNfcStatus()

        if (status != NfcStatus.ENABLED) {
            Log.e(TAG, "NFC not ready: $status")

            _state.value = NfcScanState.NfcUnavailable(status)
            return
        }

        isScanningEnabled = true
        startInternal()
    }

    fun stopScan() {
        isScanningEnabled = false
        stopInternal()
    }

    private fun startInternal() {
        if (getNfcStatus() != NfcStatus.ENABLED) {
            Log.e(TAG, "startInternal blocked: NFC not enabled")
            return
        }

        val adapter = nfcAdapter ?: run {
            Log.e(TAG, "NfcAdapter is null")
            return
        }

        Log.d(TAG, "startInternal called")

        _state.value = NfcScanState.Scanning

        adapter.enableReaderMode(
            activity,
            { tag ->
                Log.d(TAG, "Tag discovered: $tag")

                val isoDep = IsoDep.get(tag)

                if (isoDep == null) {
                    Log.e(TAG, "IsoDep is null")
                    return@enableReaderMode
                }

                Thread {
                    try {
                        Log.d(TAG, "Connecting IsoDep...")

                        Log.d(TAG, "IsoDep connected")

                        val result = NfcCardReader.read(isoDep)

                        activity?.runOnUiThread {
                            Log.d(TAG, "Read success: $result")
                            _state.value = NfcScanState.Success(result)
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Read error", e)

                        activity?.runOnUiThread {
                            _state.value = NfcScanState.Error(e)
                        }

                    }
                }.start()
            },
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )

        Log.d(TAG, "enableReaderMode called")
    }

    private fun stopInternal() {
        val activity = activity ?: return

        Log.d(TAG, "stopInternal called")

        nfcAdapter?.disableReaderMode(activity)

        _state.value = NfcScanState.Idle
    }

    private fun cleanup() {
        stopInternal()
        activity = null
    }
}