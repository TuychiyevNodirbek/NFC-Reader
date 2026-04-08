# NFC Card Reader SDK

Lightweight Android SDK for reading EMV card data via NFC (IsoDep).

---

## ✨ Features

- Read PAN (card number)
- Read expiry date
- Detect card scheme:
    - VISA
    - MASTERCARD
    - HUMO
    - UZCARD
    - MIR
    - UNIONPAY
- Works with EMV contactless cards (IsoDep)
- Simple API (start / stop scanning)
- No UI dependency (pure SDK)

---

## 📦 Installation

### Step 1. Add JitPack repository

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }


implementation("com.github.TuychiyevNodirbek:nfcreader-sdk:v1.0.0")


<uses-permission android:name="android.permission.NFC" />

<uses-feature
    android:name="android.hardware.nfc"
    android:required="true" />

val nfcSdk = NfcCardReaderSdk()
nfcSdk.attach(activity)

nfcSdk.startScan()

lifecycleScope.launch {
    nfcSdk.state.collect { state ->
        when (state) {

            is NfcScanState.Idle -> {
                // waiting
            }

            is NfcScanState.Scanning -> {
                // scanning...
            }

            is NfcScanState.Success -> {
                val card = state.result

                println("PAN: ${card.pan}")
                println("Expiry: ${card.expiry}")
                println("Scheme: ${card.scheme}")
            }

            is NfcScanState.Error -> {
                println("Error: ${state.throwable.message}")
            }
        }
    }
}