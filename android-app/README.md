# Smart Pet Feeder Dashboard

A modern, minimal Android dashboard application for monitoring a Smart Pet Feeding System. The app visualizes feeding data, analytics, and anomaly alerts with a clean, user-friendly interface.

## Features

### Dashboard Components
- **Header** - App branding, pet name, and connection status indicator
- **Summary Cards** - Today's feeding count, last feeding time, total food dispensed, and system status
- **Analytics Charts** - Food portion trends (line chart) and feeding frequency (bar chart)
- **Time Filters** - Toggle between Day, Week, and Month views
- **Feeding History** - Scrollable list of feeding records with status badges
- **Anomaly Alerts** - Real-time anomaly detection with severity indicators
- **Quick Actions** - Manual feed and edit schedule buttons

### Anomaly Detection
The system automatically detects:
- **Missed Feedings** - No record within 30 minutes of scheduled time
- **Portion Deviations** - Dispensed weight below 80% or above 120% of target
- **Frequency Irregularities** - Consecutive missed feedings or recurring deviations

### UI States
- **Loading** - Shown during initial data fetch
- **Empty** - Displayed when no feeding records exist
- **Error** - Shown on connection failures with retry option

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java |
| UI | XML Layouts |
| Build System | Gradle (Groovy) |
| Charts | MPAndroidChart |
| UI Components | Material Design Components |
| Architecture | Activity + Adapter + Model |

## Project Structure

```
app/
├── build.gradle                          # Module-level Gradle config
├── src/main/
│   ├── AndroidManifest.xml               # App manifest
│   ├── java/com/smartpetfeeder/
│   │   ├── MainActivity.java             # Main dashboard activity
│   │   ├── adapter/
│   │   │   └── FeedingHistoryAdapter.java # RecyclerView adapter
│   │   ├── model/
│   │   │   ├── FeedingRecord.java        # Feeding event model
│   │   │   ├── AnomalyAlert.java         # Anomaly alert model
│   │   │   ├── SummaryStats.java         # Dashboard statistics
│   │   │   └── DashboardData.java        # Aggregated dashboard data
│   │   ├── firebase/
│   │   │   └── FirebaseSimulator.java    # Real-time data simulator
│   │   └── util/
│   │       ├── AnomalyDetector.java      # Anomaly detection logic
│   │       └── ChartHelper.java          # Chart configuration helper
│   └── res/
│       ├── layout/
│       │   ├── activity_main.xml         # Main dashboard layout
│       │   ├── item_feeding_record.xml   # Feeding history item
│       │   └── item_anomaly_alert.xml    # Anomaly alert item
│       ├── values/
│       │   ├── colors.xml                # Color palette
│       │   ├── strings.xml               # String resources
│       │   ├── styles.xml                # App theme & styles
│       │   └── dimens.xml                # Dimension resources
│       └── drawable/
│           └── [icons and backgrounds]   # Vector icons and shapes
build.gradle                              # Project-level Gradle config
settings.gradle                           # Project settings
gradle.properties                         # Gradle properties
```

## Setup & Build

### Prerequisites
- Android Studio Arctic Fox (2020.3.1) or later
- JDK 8 or later
- Android SDK API 24+

### Build Steps

1. **Open in Android Studio**
   - Open the project folder in Android Studio
   - Let Gradle sync complete

2. **Build the project**
   ```bash
   ./gradlew assembleDebug
   ```

3. **Install on device/emulator**
   ```bash
   ./gradlew installDebug
   ```

### Gradle Dependencies

```groovy
dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'androidx.cardview:cardview:1.0.0'
    implementation 'androidx.lifecycle:lifecycle-viewmodel:2.7.0'
    implementation 'androidx.lifecycle:lifecycle-livedata:2.7.0'
    implementation 'com.github.PhilJay:MPAndroidChart:v3.1.0'
}
```

## Design System

### Color Palette
| Token | Color | Usage |
|-------|-------|-------|
| Primary | `#5B8DEF` | Brand, charts, buttons |
| Secondary | `#10B981` | Success states |
| Accent | `#F59E0B` | Warnings, highlights |
| Background | `#F8FAFC` | App background |
| Surface | `#FFFFFF` | Cards |
| Text Primary | `#1F2937` | Headings |
| Text Secondary | `#6B7280` | Body text |

### Typography
- Headlines: 18-20sp, Bold
- Body: 14-16sp, Regular
- Captions: 10-12sp, Regular
- Status Badges: 10sp, Bold, Uppercase

## Firebase Integration

The app includes a `FirebaseSimulator` class that generates realistic mock data. To integrate with a real Firebase Realtime Database:

1. Add Firebase SDK dependencies to `build.gradle`
2. Add `google-services.json` to the app directory
3. Replace `FirebaseSimulator` with `FirebaseDatabase` references
4. Implement `ValueEventListener` for real-time updates

The data listener interface (`FirebaseSimulator.DataUpdateListener`) can be adapted to work with Firebase's `ValueEventListener` without modifying the UI layer.

## License

This project is provided as a reference implementation for Android dashboard UI development.
