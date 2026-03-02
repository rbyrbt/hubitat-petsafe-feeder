# PetSafe Smart Feed for Hubitat

This Hubitat integration allows you to control and monitor PetSafe Smart Feed automatic pet feeders from your Hubitat Elevation hub.

## Features

- **Feed on demand** - Trigger feeding via switch or custom command with easy dropdown selection
- **Battery monitoring** - View battery level and low battery alerts
- **Food level status** - See when food is full, low, or empty
- **Feeding schedules** - View, add, update, and delete schedules with dropdown-based commands
- **Connection status** - Monitor online/offline status
- **Local time display** - All timestamps automatically converted to your hub's timezone

## Prerequisites

- Hubitat Elevation hub (C-5 or newer recommended)
- PetSafe Smart Feed device(s) registered in the PetSafe app

## Installation

### Step 1: Install the Driver

1. In Hubitat, go to **Drivers Code**
2. Click **+ New Driver**
3. Copy and paste the contents of `drivers/petsafe-feeder.groovy`
4. Click **Save**

### Step 2: Install the App

1. In Hubitat, go to **Apps Code**
2. Click **+ New App**
3. Copy and paste the contents of `apps/petsafe-manager.groovy`
4. Click **Save**

### Step 3: Add the App

1. In Hubitat, go to **Apps** → **Add User App**
2. Select **PetSafe Smart Feed Manager**
3. Click **Configure Authentication**

### Step 4: Authenticate with PetSafe

**Option A: Generate Token In-App (Recommended)**

1. On the Authentication page, click **Generate New Token**
2. Enter your PetSafe account email address
3. Click **Send Verification Code**
4. Check your email for the verification code from PetSafe
5. Enter the code and click **Verify Code & Generate Token**
6. The token is automatically saved!

**Option B: Manual Token Entry**

If you have a refresh token from another source (e.g., generated via CLI or another tool):

1. On the Authentication page, paste your token in the **Refresh Token** field under "Manual Token Entry"
2. Click **Test Authentication** to verify it works

### Step 5: Test Authentication (Optional)

After entering your token, click **Test Authentication** to verify the connection works. You should see a success message showing how many feeders were found.

### Step 6: Discover Feeders

1. Return to the main app page
2. Click **Discover Feeders**
3. Toggle ON each feeder you want to add to Hubitat
4. Devices are created automatically when toggled

Your feeders will appear as devices in Hubitat!

## Usage

### Triggering a Feed

There are two ways to feed your pet:

1. **Switch** - Turn the device "On" to trigger a feed (uses the default amount from Preferences)
2. **Feed command** - Use `feed(amount)` and select the amount from the dropdown

### Device Attributes

**Standard Capabilities:**
| Attribute | Description |
|-----------|-------------|
| `switch` | on/off - turns on during feeding |
| `battery` | Battery level percentage (0-100) |

**Device Info:**
| Attribute | Description |
|-----------|-------------|
| `feederId` | PetSafe feeder ID number |
| `friendlyName` | Device name from PetSafe app |
| `thingName` | Internal device identifier |
| `productName` | Product model name (e.g., SmartFeed_2.0) |
| `firmwareVersion` | Feeder firmware version |
| `serial` | Serial number (if available) |
| `region` | Device region |
| `createdAt` | When device was registered |

**Connection & Network:**
| Attribute | Description |
|-----------|-------------|
| `connectionStatus` | online or offline |
| `connectionStatusTimestamp` | Last connection status change |
| `networkRssi` | WiFi signal strength (dBm) |
| `networkSnr` | Signal to noise ratio (dB) |

**Power:**
| Attribute | Description |
|-----------|-------------|
| `batteryVoltage` | Raw battery voltage reading |
| `isBatteriesInstalled` | true/false |
| `isAdapterInstalled` | true if AC adapter connected |

**Food:**
| Attribute | Description |
|-----------|-------------|
| `foodLevel` | full, low, or empty |
| `foodLevelRaw` | Raw food level (0=full, 1=low, 2=empty) |
| `foodSensorCurrent` | Current food sensor reading |
| `foodSensorReference` | Reference food sensor value |

**Feeding:**
| Attribute | Description |
|-----------|-------------|
| `lastFeedTime` | Timestamp of last feeding |
| `lastFeedAmount` | Amount of last feeding (1-8) |
| `slowFeed` | enabled or disabled |
| `schedules` | List of schedules with IDs: "[id] time - amount" |
| `schedulesJson` | JSON array of schedules for programmatic access |
| `nextScheduledFeed` | Next scheduled feeding time |

**Settings:**
| Attribute | Description |
|-----------|-------------|
| `childLock` | enabled or disabled |
| `paused` | true if feeding is paused |
| `petType` | cat or dog (if configured) |
| `timezone` | Device timezone |

**Firmware:**
| Attribute | Description |
|-----------|-------------|
| `revisionDesired` | Target firmware revision |
| `revisionReported` | Current firmware revision |

### Device Commands

**Feeding:**
| Command | Description |
|---------|-------------|
| `on()` | Trigger feed with default amount |
| `off()` | Turn off switch (no action) |
| `feed(amount)` | Feed specific amount (select from dropdown: 1-8 in 1/8 cup increments) |
| `refresh()` | Manually refresh device status |

**Schedule Management:**
| Command | Description |
|---------|-------------|
| `scheduleAdd(time, amount)` | Add a new schedule (select time and amount from dropdowns) |
| `scheduleUpdate(id, time, amount)` | Update existing schedule by ID (get ID from schedules attribute) |
| `scheduleDelete(id)` | Delete a schedule by ID |

**Amount Dropdown Options:**
- 1 - 1/8 cup
- 2 - 1/4 cup
- 3 - 3/8 cup
- 4 - 1/2 cup
- 5 - 5/8 cup
- 6 - 3/4 cup
- 7 - 7/8 cup
- 8 - 1 cup

**Time Dropdown Options:**
- 30-minute increments from 00:00 to 23:30

### Rule Machine Example

To feed your pet at a specific time:

```
Trigger: Time is 7:00 AM
Actions: 
  - Run Custom Action: "PetSafe Feeder" → feed → select "2 - 1/4 cup"
```

To get notified when food is low:

```
Trigger: "PetSafe Feeder" foodLevel changed to "low"
Actions:
  - Send notification: "Pet feeder food is running low!"
```

### Managing Schedules

Schedule IDs are displayed in the device's `schedules` attribute in the format `[ID] time - amount`, for example:
```
[1565237] 6:30 AM - 1/8 cup, [1565238] 10:30 AM - 1/8 cup
```

Use these IDs when updating or deleting schedules.

**Add a new schedule:**
1. Go to device Commands tab
2. Find `scheduleAdd`
3. Select time from dropdown (e.g., 07:30)
4. Select amount from dropdown (e.g., "2 - 1/4 cup")
5. Click the command button

**Update an existing schedule:**
1. Note the schedule ID from the `schedules` attribute
2. Use `scheduleUpdate` command
3. Enter the schedule ID (e.g., 1565237)
4. Select new time and amount from dropdowns

**Delete a schedule:**
1. Note the schedule ID from the `schedules` attribute
2. Use `scheduleDelete` command
3. Enter the schedule ID (e.g., 1565237)

## Settings

### App Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Polling Interval | How often to check feeder status (1, 5, 10, 15, 30, 45 min or 1 hour) | 30 minutes |
| Debug Logging | Enable detailed logging | On |

### Device Settings

| Setting | Description | Default |
|---------|-------------|---------|
| Default Feed Amount | Amount to dispense when switch turned on | 1 (1/8 cup) |
| Date Format | Display dates as YYYY-MM-DD or M/D/YY | YYYY-MM-DD |
| Time Format | Display times in 24-hour or 12-hour format | 24-hour |
| Debug Logging | Enable debug logging | On |
| Description Logging | Enable info-level event logging | On |

## Troubleshooting

### "Authentication failed" error

- Generate a new token using the in-app token generator
- Verify your PetSafe account credentials are correct
- Make sure your PetSafe account has feeders registered
- Check that your Hubitat hub has internet connectivity

### Verification code not received

- Check your spam/junk folder
- Verify you're using the correct email address registered with PetSafe
- Wait a few minutes and try again

### Feeder shows "offline"

- Check that your feeder is powered and connected to WiFi
- Verify the feeder shows as online in the PetSafe mobile app
- Try refreshing the device

### Feed command doesn't work

- Check the Hubitat logs for error messages
- Verify the feeder is online
- Try the feed command from the PetSafe mobile app to confirm the feeder is working

### Token expires frequently

The app automatically refreshes the ID token before it expires. If you're seeing frequent authentication errors:
- Generate a new refresh token using the in-app generator
- Check that your Hubitat hub has internet connectivity
- Verify your PetSafe account is still active

## API Limitations

The PetSafe API has some restrictions on what can be controlled via third-party integrations:

| Feature | Hubitat Support |
|---------|-----------------|
| Trigger feeding | ✓ Full support |
| View schedules | ✓ Full support |
| Add/update/delete schedules | ✓ Full support |
| Slow feed | ✗ Must change in PetSafe app |
| Child lock | ✗ Must change in PetSafe app |

## API Rate Limits

PetSafe's API has rate limits. The default 30-minute polling interval is safe for normal use. If you have multiple feeders, the app polls them sequentially.

Avoid:
- Setting polling interval below 1 minute
- Excessive manual refresh calls
- Rapid successive feed commands

## HomeKit Integration

If you want to access your PetSafe feeders via Apple HomeKit, you can use Hubitat's built-in [HomeKit Integration](https://docs.hubitat.com/index.php?title=HomeKit_Integration) to expose the feeder devices to HomeKit.

## Credits

This integration is a Hubitat adaptation of [homebridge-petsafe-smart-feed](https://github.com/dgreif/homebridge-petsafe-smart-feed) by [dgreif](https://github.com/dgreif).

## License

Licensed under the MIT License.
