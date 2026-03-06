<p align="center">
  <img src="logo.png" alt="WinNative" width="600">
</p>

# WinNative: High-Performance Windows Emulation for Android

**WinNative** is an advanced, high-performance Windows (x86_64) emulation environment for Android. It bridges the gap between desktop gaming and mobile mobility by unifying the best technologies from **Winlator Bionic**, **Pluvia**, and **GameNative-Performance**. 

Designed for enthusiasts and power users, WinNative provides a "plug-and-play" experience with a console-like interface, deep controller integration, and hardware-specific optimizations for modern Snapdragon/Adreno devices.

---

## 🚀 Key Features

### 🎮 Console-First Experience (Unified UI)
- **Controller-Friendly Design:** Navigable entirely via gamepad, making it perfect for handhelds like the Odin 2, Retroid Pocket, or docked phones.
- **Unified Library:** A beautiful, Compose-based library view that aggregates local shortcuts and Steam games into a single, high-fidelity grid.
- **Pluvia Integration:** Leverages the Pluvia ecosystem for advanced library management and aesthetic "Big Picture" layouts.

### ⚙️ Peak Performance
- **Bionic Environment:** Uses an Ubuntu Bionic-based RootFS for lower overhead and better compatibility with modern x86_64 applications.
- **Adreno Optimization:** Deeply integrated Turnip/Zink drivers with specialized profiles for Snapdragon 8 Gen 2/3 and 8 Elite (Adreno 7xx/8xx).
- **Ludashi Branding:** Built with the `com.ludashi.benchmark` package name to trigger "Performance Mode" on Xiaomi and other OEM devices, reducing thermal throttling.
- **Hybrid Emulation:** Supports Box86/Box64, FEXCore (Arm64EC), and WowBox64 to ensure maximum compatibility across different instruction sets.

### 🌐 Seamless Steam Integration
- **Account Sync:** Sign in to your Steam account to browse your library and download assets directly.
- **SteamPipe Support:** Integrated SteamPipe DLLs for better compatibility with modern Steam games.
- **Local Playtime Tracking:** Offline-first playtime management that persists even without an active cloud connection.

---

## 🛠️ Components & Drivers

WinNative is built on the shoulders of giants. It includes and supports:
- **Translators:** Box86/Box64 by [ptitSeb](https://github.com/ptitSeb), FEX-Emu.
- **Graphics:** DXVK (up to 2.4), VKD3D (3.0b optimized for A840), D8VK, and CNC DDraw.
- **Kernel/Environment:** PRoot environment with custom `evshim` for low-latency input.
- **Drivers:** Optimized Turnip drivers (v1.8.8/v1.8.9) with specific fixes for UBWC v5/v6 and Adreno 8xx chips.

---

## 🗺️ Roadmap

We are committed to making WinNative the gold standard for mobile emulation.
- [x] **Unified Interface:** Compose-based library with controller navigation.
- [x] **Smart Power Management:** Dynamic refresh of power constraints during heavy emulation.
- [x] **Adreno 8xx Support:** Full compatibility with Snapdragon 8 Elite.
- [ ] **Direct Game Launching:** Deep-linking shortcuts directly from the Android home screen.
- [ ] **Advanced DRM Handling:** Improved support for games requiring specialized file redirections.
- [ ] **Cloud Save Sync:** Integrating local playtime and saves with community cloud solutions.

---

## 📦 Installation

1. **Download:** Get the latest APK from the [Releases](https://github.com/maxjivi05/WinNative/releases) section.
2. **Variants:**
   - `Ludashi`: Best for Xiaomi/RedMagic (Performance Mode trigger).
   - `Vanilla`: Standard package name for side-loading with other forks.
3. **Setup:** Launch the app, allow the ImageFS to install, and start adding your games or syncing with Steam.

---

## 🤝 Credits & Acknowledgments

- **Original Winlator** by [brunodev85](https://github.com/brunodev85/winlator)
- **Winlator Bionic** by [Pipetto-crypto](https://github.com/Pipetto-crypto/winlator)
- **Pluvia/GameNative** features by the GameNative community.
- **Mesa/Turnip** contributions by [Danylo](https://blogs.igalia.com/dpiliaiev/tags/mesa/) and the Mesa3D team.

---
<p align="center">
  <i>Developed for the community, by the community.</i>
</p>
