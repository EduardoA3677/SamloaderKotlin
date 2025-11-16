# Test Keystore for Release Builds

This directory contains a test keystore (`release.keystore`) used for signing release APKs.

## Keystore Details

- **File**: `release.keystore`
- **Type**: PKCS12
- **Alias**: `bifrost_release`
- **Store Password**: `android`
- **Key Password**: `android`
- **Validity**: 10,000 days (until April 3, 2053)

## Purpose

This keystore is intended for **testing and development purposes only**. It allows the GitHub Actions workflow to build and sign release APKs without requiring external secrets or signing actions.

## Security Note

⚠️ **WARNING**: This keystore contains publicly known credentials and should **NEVER** be used for production releases. For production releases, use a properly secured keystore with strong passwords stored as GitHub secrets.

## Certificate Information

- **Owner**: CN=Bifrost Test, OU=Development, O=Bifrost, L=Test, ST=Test, C=US
- **Algorithm**: SHA256withRSA
- **Key Size**: 2048-bit RSA

## Usage

The keystore is automatically used by the Gradle build system when building release APKs. The signing configuration is defined in `build.gradle.kts`:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("release.keystore")
        storePassword = "android"
        keyAlias = "bifrost_release"
        keyPassword = "android"
    }
}
```
