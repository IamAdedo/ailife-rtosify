# Health Detail Activity - Implementation Requirements

## Overview
The HealthDetailActivity is **NOT IMPLEMENTED** yet. Currently, clicking on health metrics in MainActivity shows a toast message "coming soon!". This document details what needs to be implemented.

## Current State
- **MainActivity.kt** (lines 212-230): Click handlers exist but only show toast messages
- **Protocol support**: All required message types already exist:
  - `REQUEST_HEALTH_HISTORY` - Request historical data
  - `RESPONSE_HEALTH_HISTORY` - Receive historical data
  - `START_LIVE_MEASUREMENT` - Start live HR/Oxygen measurement
  - `STOP_LIVE_MEASUREMENT` - Stop live measurement
  - `UPDATE_HEALTH_SETTINGS` - Update step goals and monitoring settings
- **BluetoothService methods**: Already implemented:
  - `requestHealthHistory(type, startTime, endTime)` - Request historical data
  - `startLiveMeasurement(type)` - Start live measurement
  - `stopLiveMeasurement()` - Stop live measurement
  - `updateHealthSettings(settings)` - Update settings
- **Watch-side**: HealthDataCollector has full Content Provider query support

## What Needs to Be Implemented

### 1. HealthDetailActivity.kt
**Location**: `/home/ailife/AndroidStudioProjects/rtosify/rtosify/app/src/main/java/com/ailife/rtosify/HealthDetailActivity.kt`

**Purpose**: Display detailed health data with graphs, goal tracking, and live measurements

**Required Features**:

#### A. Activity Setup
- Receive `HEALTH_TYPE` intent extra: "STEPS", "HEART_RATE", or "OXYGEN"
- Bind to BluetoothService
- Implement ServiceCallback to receive `onHealthHistoryReceived()` and `onHealthDataUpdated()`

#### B. Period Selection (Day/Week/Month)
- MaterialButtonToggleGroup with 3 buttons
- Day: Show last 24 hours
- Week: Show last 7 days
- Month: Show last 30 days
- Calculate Unix timestamps and call `bluetoothService?.requestHealthHistory(type, startTime, endTime)`

#### C. Chart Display using MPAndroidChart
- LineChart component showing historical data points
- X-axis: Time labels (hour for day, date for week/month)
- Y-axis: Value (steps, bpm, %)
- Different colors per metric type:
  - Steps: Primary color
  - Heart Rate: Error color (red)
  - Blood Oxygen: Tertiary color (blue)
- Goal line (horizontal) for steps only

#### D. Pull-to-Refresh
- SwipeRefreshLayout wrapping content
- On refresh: Re-request historical data for current period
- Stop refreshing animation when data received

#### E. Goal Progress Card (Steps Only)
- Show current progress vs daily goal
- Progress bar with percentage
- Example: "7,543 / 10,000 steps (75% complete)"
- Hide this card for HR and Oxygen types

#### F. Settings Menu
- Toolbar menu icon (3 dots)
- Opens dialog with settings:
  - **Step Goal** (EditText, integer, steps only)
  - **Monitoring Interval** (EditText, integer in minutes, all types)
  - **Background Monitoring** (Switch, all types)
- On save: Call `bluetoothService?.updateHealthSettings(settings)`

#### G. "Measure Now" FAB (HR/Oxygen Only)
- FloatingActionButton visible only for HEART_RATE and OXYGEN types
- On click:
  - Call `bluetoothService?.startLiveMeasurement(type)`
  - Change FAB icon to "stop" icon
  - Show pulsing animation on chart
  - Update chart with real-time values (1 second interval)
- On stop:
  - Call `bluetoothService?.stopLiveMeasurement()`
  - Stop animation
  - Revert FAB icon

#### H. Data Handling
- Store received `HealthHistoryResponse` from callback
- Convert `List<HealthDataPoint>` to chart entries
- Handle empty data gracefully (show "No data" message)
- Handle error states (show error message)

### 2. activity_health_detail.xml
**Location**: `/home/ailife/AndroidStudioProjects/rtosify/rtosify/app/src/main/res/layout/activity_health_detail.xml`

**Required Layout Structure**:
```xml
<androidx.coordinatorlayout.widget.CoordinatorLayout>

    <!-- App Bar -->
    <com.google.android.material.appbar.AppBarLayout>
        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            app:title="@string/health_steps_title"
            app:menu="@menu/menu_health_detail" />
    </com.google.android.material.appbar.AppBarLayout>

    <!-- Scrollable Content -->
    <androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        android:id="@+id/swipeRefresh">

        <androidx.core.widget.NestedScrollView>
            <LinearLayout orientation="vertical">

                <!-- Period Toggle -->
                <com.google.android.material.button.MaterialButtonToggleGroup
                    android:id="@+id/togglePeriod">
                    <Button android:id="@+id/btnDay" text="@string/health_period_day" />
                    <Button android:id="@+id/btnWeek" text="@string/health_period_week" />
                    <Button android:id="@+id/btnMonth" text="@string/health_period_month" />
                </com.google.android.material.button.MaterialButtonToggleGroup>

                <!-- Goal Progress Card (steps only) -->
                <com.google.android.material.card.MaterialCardView
                    android:id="@+id/cardGoalProgress"
                    android:visibility="gone">
                    <LinearLayout>
                        <TextView android:id="@+id/tvGoalTitle" />
                        <ProgressBar android:id="@+id/progressGoal" style="horizontal" />
                        <TextView android:id="@+id/tvGoalProgress" />
                    </LinearLayout>
                </com.google.android.material.card.MaterialCardView>

                <!-- Chart Card -->
                <com.google.android.material.card.MaterialCardView>
                    <com.github.mikephil.charting.charts.LineChart
                        android:id="@+id/chart"
                        android:layout_width="match_parent"
                        android:layout_height="300dp" />
                </com.google.android.material.card.MaterialCardView>

            </LinearLayout>
        </androidx.core.widget.NestedScrollView>
    </androidx.swiperefreshlayout.widget.SwipeRefreshLayout>

    <!-- Measure Now FAB (HR/Oxygen only) -->
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fabMeasureNow"
        android:visibility="gone"
        app:layout_anchor="@id/chart"
        app:layout_anchorGravity="bottom|end"
        android:src="@drawable/ic_heart" />

</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

### 3. menu_health_detail.xml
**Location**: `/home/ailife/AndroidStudioProjects/rtosify/rtosify/app/src/main/res/menu/menu_health_detail.xml`

```xml
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item
        android:id="@+id/action_settings"
        android:icon="@drawable/ic_settings"
        android:title="@string/health_settings"
        app:showAsAction="always" />
</menu>
```

### 4. dialog_health_settings.xml (Optional)
**Location**: `/home/ailife/AndroidStudioProjects/rtosify/rtosify/app/src/main/res/layout/dialog_health_settings.xml`

Settings dialog layout with EditTexts for goals and switches for monitoring.

### 5. MainActivity.kt Updates
**Required Changes**:

Replace toast messages (lines 212-230) with actual Intent navigation:

```kotlin
private fun setupHealthClickListeners() {
    layoutStepsAction.setOnClickListener {
        runIfConnected {
            startActivity(Intent(this, HealthDetailActivity::class.java).apply {
                putExtra("HEALTH_TYPE", "STEPS")
            })
        }
    }

    layoutHeartRateAction.setOnClickListener {
        runIfConnected {
            startActivity(Intent(this, HealthDetailActivity::class.java).apply {
                putExtra("HEALTH_TYPE", "HEART_RATE")
            })
        }
    }

    layoutOxygenAction.setOnClickListener {
        runIfConnected {
            startActivity(Intent(this, HealthDetailActivity::class.java).apply {
                putExtra("HEALTH_TYPE", "OXYGEN")
            })
        }
    }
}
```

### 6. String Resources
Already exist in `strings.xml` (lines 212-235):
- `health_steps_title`, `health_hr_title`, `health_oxygen_title`
- `health_period_day`, `health_period_week`, `health_period_month`
- `health_daily_goal`, `health_goal_progress`
- `health_measure_now`, `health_measuring`
- `health_settings`, `health_step_goal`, `health_monitoring_interval`

## Dependencies
Already added in `build.gradle.kts`:
```kotlin
implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
```

## Data Flow
1. User clicks health metric in MainActivity → Opens HealthDetailActivity
2. HealthDetailActivity requests historical data → Watch queries Content Provider
3. Watch sends `RESPONSE_HEALTH_HISTORY` → Phone receives data
4. HealthDetailActivity populates chart with data points
5. User clicks "Measure Now" → Watch starts live measurement (1 second updates)
6. User changes settings → Watch updates Better Health Tracker settings

## Key Implementation Notes

### Time Range Calculation
- **Day**: `startTime = now - 24 hours`, `endTime = now`
- **Week**: `startTime = now - 7 days`, `endTime = now`
- **Month**: `startTime = now - 30 days`, `endTime = now`
- Convert milliseconds to Unix seconds for protocol

### Chart Configuration
- Enable touch gestures
- Disable grid background
- Format X-axis labels based on period
- Set appropriate Y-axis range

### Live Measurement
- Only enable for HR and OXYGEN types
- Update chart in real-time as data arrives
- Show visual indicator (pulsing animation)
- Auto-stop when leaving activity (`onPause` or `onDestroy`)

### Goal Tracking
- Only show for STEPS type
- Get goal from `HealthHistoryResponse.goal` field
- Calculate percentage: `(currentSteps / goal) * 100`

## Existing Infrastructure (Already Working)
✅ Protocol message types defined
✅ BluetoothService callback methods
✅ Watch-side Content Provider queries
✅ Watch-side live measurement support
✅ String resources
✅ MPAndroidChart dependency
✅ Main page health card display
✅ Request-based health data collection (not continuous)

## What's Missing (To Be Implemented)
❌ HealthDetailActivity.kt class
❌ activity_health_detail.xml layout
❌ menu_health_detail.xml menu
❌ Chart rendering logic
❌ Period selection logic
❌ Pull-to-refresh implementation
❌ Settings dialog
❌ "Measure Now" functionality
❌ Goal progress calculation
❌ MainActivity navigation (replace toasts with Intents)

## Additional Context

### Protocol Data Structures (Already Defined)
```kotlin
data class HealthHistoryRequest(
    val type: String,              // "STEP", "HR", "OXYGEN"
    val startTime: Long,           // Unix timestamp in seconds
    val endTime: Long              // Unix timestamp in seconds
)

data class HealthHistoryResponse(
    val type: String,
    val dataPoints: List<HealthDataPoint>,
    val goal: Int? = null,
    val errorState: String? = null
)

data class HealthDataPoint(
    val timestamp: Long,           // Unix timestamp in seconds
    val value: Float
)

data class LiveMeasurementRequest(
    val type: String               // "HR" or "OXYGEN"
)

data class HealthSettingsUpdate(
    val stepGoal: Int? = null,
    val backgroundEnabled: Boolean? = null,
    val monitoringTypes: String? = null,  // "STEP,HR,OXYGEN"
    val interval: Int? = null              // minutes
)
```

### Better Health Tracker API Integration (Watch Side)
The watch app uses `HealthDataCollector.kt` which:
- Queries historical data via Content Provider: `content://com.ailife.betterhealth.healthprovider/records/range/{startTime}/{endTime}`
- Returns data points with Unix timestamps and values
- Supports STEP, HR (heartrate), OXYGEN (bloodoxygen) data types

### Known Issues to Address
1. **Empty Data Handling**: Content Provider may return empty list if no data in range
2. **Timestamp Conversion**: Watch returns Unix seconds, need to convert for display
3. **Live Measurement Lifecycle**: Must stop measurement when activity is destroyed to avoid battery drain
4. **Chart Performance**: Large datasets (month view) may need data aggregation/sampling

This should provide a fresh agent with all the context needed to implement the HealthDetailActivity feature.
