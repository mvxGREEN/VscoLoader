# VscoLoader 📸🎥⬇️

[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)](https://github.com/mvxGREEN/Tagger/actions)
[![License: WTFPL](https://img.shields.io/badge/License-WTFPL-brightgreen.svg)](http://www.wtfpl.net/about/)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org/)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84.svg)](https://developer.android.com/)

A simple Android application for downloading photos, videos and profiles from VSCO. 

## ✨ Features
* **Direct Downloads**: Easily download your favorite VSCO images or videos directly to your Android device's local storage.
* **Kotlin-First**: Written entirely (100%) in Kotlin, leveraging modern language features for a safe and concise codebase.
* **Modern Build System**: Utilizes Gradle Kotlin DSL (`*.gradle.kts`) for build scripts, ensuring better type safety and seamless IDE support.

## 🛠 Tech Stack
* **Language**: [Kotlin](https://kotlinlang.org/)
* **Platform**: Android SDK
* **Build Tool**: Gradle (Kotlin DSL)

## 🚀 Getting Started

To get a local copy up and running, follow these simple steps.

### Prerequisites
* **Android Studio**: Make sure you have the latest version of [Android Studio](https://developer.android.com/studio) installed.
* **Android SDK**: Check the `build.gradle.kts` for specific `minSdk` and `targetSdk` configurations.

### Installation & Build

1. **Clone the repository**
    `git clone https://github.com/mvxGREEN/VscoLoader-Android.git`

2. **Open the project in Android Studio**
   * Launch Android Studio.
   * Select **Open an existing Android Studio project**.
   * Navigate to the cloned `VscoLoader-Android` directory and click **OK**.

3. **Sync Gradle**
   * Wait for Android Studio to index the files and sync the Gradle dependencies.

4. **Run the App**
   * Connect an Android device via USB (with USB Debugging enabled) or start an Android Emulator.
   * Click the **Run** button (green play icon) in the Android Studio toolbar.

## 💡 Usage

1. Open the VscoLoader app.
2. Paste the URL of the VSCO post you wish to download.
3. Tap the download button and wait for the image/video to be saved to your device.

*(Alternatively, use the Android "Share" intent from the official VSCO app to share URL's directly to VscoLoader)*

## 🤝 Contributing
Contributions, issues, and feature requests are welcome! 
Feel free to check the [issues page](https://github.com/mvxGREEN/VscoLoader-Android/issues) if you want to contribute. 

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📄 License
This project is licensed under the **WTFPL** (Do What The F*ck You Want To Public License) - see the [LICENSE](LICENSE) file for details.
