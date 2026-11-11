# 🎙️ Vector Assistant (Android AI Companion + Gemini Multimodal Voice)
> **Hands-Free "Hey Vector" / "Suno Vector" Wake Word • Screen-Off Background Listening • Expressive Vector Visor UI • Gemini Audio AI**

Vector Assistant aapka autonomous AI voice companion hai. Isme **Gemini Multimodal Audio** aur **Always-On Screen-Off Audio Engine** integrated hai — matlab ab aapko har baar circle click nahi karna padega, aur phone ki screen off hone par bhi Vector aapki awaz sun kar jawab dega aur phone tasks execute karega!

---

## ✨ Key Upgrades

### 1. 🧠 Reliable Gemini AI Brain (`gemini-2.5-flash` / `2.5-flash-lite` / `2.0-flash`)
* **Real Key Verification:** Settings mein Gemini API key enter karein — real API test ke sath verified model connect hota hai.
* **Direct Audio Understanding:** Roman Urdu, Urdu, Hindi aur English mixed speech directly `16kHz WAV` audio ke zariye samajhta hai (bina Google STT ki ghaltiyon ke!).
* **Smart Intent Engine:** Questions (`"OpenAI kya hai?"`) aur phone commands (`"Torch on karo"`, `"Ali ko call milao"`) ko cleanly differentiate karta hai.

### 2. 🎙️ True Hands-Free Wake Word + Screen-Off Support
* **No Need to Click Circle Every Time:** Bas bolein **"Hey Vector"** ya **"Suno Vector"** (ek hi saans mein command bhi bol sakte hain, e.g. *"Hey Vector, aaj ka mausam kaisa hai?"*).
* **100% Screen-Off Ready:** Continuous single-lifecycle `AudioRecord` + `PARTIAL_WAKE_LOCK` + `WifiLock` + `foregroundServiceType="microphone"` ke sath screen band hone par bhi kaam karta hai.
* **900ms Pre-Roll Buffer & Speech-Band VAD:** Shuru ke lafz (`"Hey"` / `"Suno"`) miss nahi hote, aur speaker output ke dauran mic automatically mute rehta hai taake self-echo na ho.
* **MFCC + Subsequence DTW Voice Training:** Settings mein 3 voice samples record kar ke offline wake word aur optional **Strict Voice Lock** enable kar sakte hain.

### 3. 🤖 Expressive Vector Visor UI & Floating HUD
* **Animated Vector Visor:** Custom `Canvas` expressive glowing cyan eyes jo voice level ke sath blink, gaze aur pulse karti hain.
* **Live Chat Feed & Prompt Bar:** Full conversation history bubbles, quick command chips, aur text/mic composer.
* **Floating Overlay HUD:** Overlay permission allow karne par dusri apps ke upar bhi Vector eyes pill pop-up hoti hai.
* **Control Center Sheet:** Settings icon par tap kar ke Gemini key, Mic sensitivity, Voice training, VIP contacts, Permissions health aur Diagnostics manage karein.

---

## 📲 Setup (Quick Steps)
1. App open kar ke **Microphone, Phone & Contacts** permissions allow karein.
2. Top-right **Settings (⚙)** icon open kar ke apni **Gemini API Key** paste aur **Verify & Connect** karein.
3. **Screen-Off Battery Unrestricted** allow karein taake screen off hone par OS service band na kare.
4. *(Optional)* **Floating Vector HUD Overlay**, **Accessibility Service** (Home/Back/Lock automation), aur **Notification Access** (VIP WhatsApp/SMS alarm) enable karein.
