# ASIFBOT Android App

ASIFBOT is the Android control app for your trading bot. It includes:

- Email/password login through your backend API.
- 3-day trial support through the backend account state.
- Google Play subscription purchase flow.
- Bot ON/OFF control through `/bot/control`.
- Status panel for access, VPS connection, and bot switch state.

## Defaults

- Package name: `com.asifbot.app`
- App name: `ASIFBOT`
- Subscription product id: `asifbot_monthly`
- Backend URL: `https://api.asifbot.com`
- Demo mode: `true` for APK testing without a backend
- Target SDK: API 35
- Play Billing Library: `8.0.0`

## Play Console Setup

Create a subscription product:

- Product ID: `asifbot_monthly`
- Base plan: monthly or your chosen billing period
- Offer: 3-day free trial for new customers

The app chooses the first offer that contains a free pricing phase. If no free-trial offer exists, it falls back to the first available subscription offer.

## Backend Requirement

The app cannot directly turn an MT4/MT5 EA on and off from a phone. The safe production structure is:

```text
ASIFBOT Android app -> HTTPS backend -> VPS/EA bridge -> MT4/MT5 Expert Advisor
```

The EA or bridge must poll your backend for `enabled=true/false`, then allow or pause new trades. The Android app is already wired for that backend contract in `API_CONTRACT.md`.

For phone testing, the app currently uses `DEMO_MODE=true` in `app/build.gradle.kts`. That makes login/create-account work locally and prevents the placeholder `api.asifbot.com` error. Before Play Store release with real bot control, set `DEMO_MODE=false` and connect `API_BASE_URL` to your real backend.

## Build Notes

This PC currently does not have Android Studio, Java, or Gradle available from the command line, so the project source was created but not compiled locally.

### Best Option For A Weak PC

Use the included GitHub Actions workflow:

1. Upload this `ASIFBOT_App` folder to a GitHub repository.
2. Open the repository on GitHub.
3. Go to `Actions`.
4. Run `Android Build`.
5. Download the generated artifacts:
   - `ASIFBOT-debug-apk` for phone testing.
   - `ASIFBOT-release-aab-unsigned` as the release bundle build output.

For Play Store upload, the release AAB must be signed with an upload key. The next production step is to add release signing secrets to GitHub Actions or generate a signed AAB from a machine that has Java/Android tools.
