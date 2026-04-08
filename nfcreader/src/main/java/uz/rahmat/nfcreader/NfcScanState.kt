package uz.rahmat.nfcreader

sealed class NfcScanState {
    object Idle : NfcScanState()
    object Scanning : NfcScanState()
    data class Success(val result: CardReadResult) : NfcScanState()
    data class Error(val throwable: Throwable) : NfcScanState()
}