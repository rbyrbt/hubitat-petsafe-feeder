/**
 *  PetSafe Smart Feed Driver for Hubitat
 *
 *  Copyright 2026 rbyrbt
 *  THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT ANY WARRANTY. THE AUTHORS ARE NOT LIABLE FOR ANY DAMAGES ARISING FROM ITS USE.
 *
 */

metadata {
    definition(name: "PetSafe Smart Feeder", namespace: "rbyrbt.petsafe", author: "rbyrbt", importUrl: "https://raw.githubusercontent.com/rbyrbt/hubitat-petsafe-feeder/main/drivers/petsafe-feeder.groovy") {
        capability "Switch"
        capability "Battery"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        // Custom attributes - Device Info
        attribute "feederId", "number"
        attribute "friendlyName", "string"
        attribute "thingName", "string"
        attribute "productName", "string"
        attribute "firmwareVersion", "string"
        attribute "serial", "string"
        attribute "region", "string"
        attribute "createdAt", "string"
        
        // Custom attributes - Connection & Network
        attribute "connectionStatus", "enum", ["online", "offline"]
        attribute "connectionStatusTimestamp", "string"
        attribute "networkRssi", "number"
        attribute "networkSnr", "number"
        
        // Custom attributes - Power
        attribute "batteryVoltage", "number"
        attribute "isBatteriesInstalled", "enum", ["true", "false"]
        attribute "isAdapterInstalled", "enum", ["true", "false"]
        
        // Custom attributes - Food
        attribute "foodLevel", "enum", ["full", "low", "empty"]
        attribute "foodLevelRaw", "number"
        attribute "foodSensorCurrent", "number"
        attribute "foodSensorReference", "number"
        
        // Custom attributes - Feeding
        attribute "lastFeedTime", "string"
        attribute "lastFeedAmount", "number"
        attribute "slowFeed", "enum", ["enabled", "disabled"]
        attribute "nextScheduledFeed", "string"
        attribute "schedules", "string"
        attribute "schedulesJson", "string"
        
        // Custom attributes - Settings
        attribute "childLock", "enum", ["enabled", "disabled"]
        attribute "paused", "enum", ["true", "false"]
        attribute "petType", "string"
        attribute "timezone", "string"
        
        // Custom attributes - Firmware
        attribute "revisionDesired", "number"
        attribute "revisionReported", "number"

        // Commands: on, off (from Switch), feed, refresh (from Refresh), schedules
        command "feed", [[name: "amount", type: "ENUM", description: "Amount", constraints: getAmountOptions()]]
        
        // Schedule management commands (grouped at end)
        command "scheduleAdd", [
            [name: "time", type: "ENUM", description: "Feed time", constraints: getTimeOptions()],
            [name: "amount", type: "ENUM", description: "Amount", constraints: getAmountOptions()]
        ]
        command "scheduleDelete", [
            [name: "scheduleId", type: "NUMBER", description: "Schedule ID (see schedules attribute)"]
        ]
        command "scheduleUpdate", [
            [name: "scheduleId", type: "NUMBER", description: "Schedule ID (see schedules attribute)"],
            [name: "time", type: "ENUM", description: "New feed time", constraints: getTimeOptions()],
            [name: "amount", type: "ENUM", description: "New amount", constraints: getAmountOptions()]
        ]
    }

    preferences {
        input name: "defaultFeedAmount", type: "number", title: "Default Feed Amount", description: "Amount in 1/8 cup increments (1-8). Example: 1 = 1/8 cup, 2 = 1/4 cup", range: "1..8", defaultValue: 1
        input name: "dateFormat", type: "enum", title: "Date Format", options: ["YYYY-MM-DD": "YYYY-MM-DD (2024-01-15)", "M/D/YY": "M/D/YY (1/15/24)"], defaultValue: "YYYY-MM-DD"
        input name: "timeFormat", type: "enum", title: "Time Format", options: ["24": "24-hour (14:30)", "12": "12-hour (2:30 PM)"], defaultValue: "24"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

// Constants for battery calculation (ported from smart-feed.ts)
@groovy.transform.Field static final int MIN_VOLTAGE = 22755
@groovy.transform.Field static final int MAX_VOLTAGE = 29100

// Static options for command dropdowns
@groovy.transform.Field static final List TIME_OPTIONS = [
    "00:00", "00:30", "01:00", "01:30", "02:00", "02:30", "03:00", "03:30",
    "04:00", "04:30", "05:00", "05:30", "06:00", "06:30", "07:00", "07:30",
    "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00", "11:30",
    "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30",
    "16:00", "16:30", "17:00", "17:30", "18:00", "18:30", "19:00", "19:30",
    "20:00", "20:30", "21:00", "21:30", "22:00", "22:30", "23:00", "23:30"
]

@groovy.transform.Field static final List AMOUNT_OPTIONS = [
    "1 - 1/8 cup", "2 - 1/4 cup", "3 - 3/8 cup", "4 - 1/2 cup",
    "5 - 5/8 cup", "6 - 3/4 cup", "7 - 7/8 cup", "8 - 1 cup"
]

/**
 * Get time options for schedule commands (30-minute increments)
 */
def getTimeOptions() {
    return TIME_OPTIONS
}

/**
 * Get amount options for schedule commands (1-8 in 1/8 cup increments)
 */
def getAmountOptions() {
    return AMOUNT_OPTIONS
}

def installed() {
    logDebug "PetSafe Smart Feeder installed"
    initialize()
}

def updated() {
    logDebug "PetSafe Smart Feeder updated"
    initialize()
}

def initialize() {
    sendEvent(name: "switch", value: "off")
}

def uninstalled() {
    logDebug "PetSafe Smart Feeder uninstalled"
}

// Switch capability - turning on triggers a feed
def on() {
    logDebug "Switch turned on - triggering feed"
    def amount = (settings.defaultFeedAmount ?: 1).toString()
    feed(amount)
}

def off() {
    logDebug "Switch turned off"
    sendEvent(name: "switch", value: "off", descriptionText: "Feeder switch turned off")
}

/**
 * Feed the pet with specified amount
 * @param amount Amount from dropdown (e.g., "1 - 1/8 cup") or number 1-8
 */
def feed(String amount = null) {
    def feedAmount
    if (amount) {
        // Handle dropdown selection (e.g., "1 - 1/8 cup") or plain number
        feedAmount = amount.contains(" - ") ? extractAmount(amount) : (amount as Integer)
    } else {
        feedAmount = settings.defaultFeedAmount ?: 1
    }
    
    if (feedAmount < 1 || feedAmount > 8) {
        log.error "Invalid feed amount: ${feedAmount}. Must be between 1 and 8."
        return
    }

    if (!parent) {
        log.error "No parent app configured. Please add this device through the PetSafe Manager app."
        return
    }

    logInfo "Feeding ${device.displayName} ${feedAmount}/8 cup"
    sendEvent(name: "switch", value: "on", descriptionText: "Feeding ${feedAmount}/8 cup")

    def slowFeed = device.currentValue("slowFeed") == "enabled"
    
    // Request parent app to execute the feed
    parent.feedDevice(device.deviceNetworkId, feedAmount, slowFeed)

    // Schedule switch to turn off after 5 seconds
    runIn(5, "turnOffSwitch")
}

def turnOffSwitch() {
    sendEvent(name: "switch", value: "off", descriptionText: "Feed cycle complete")
}

// ============================================
// Schedule Management Commands
// ============================================

/**
 * Add a new feeding schedule
 * @param time Time from dropdown (HH:mm format)
 * @param amount Amount from dropdown (e.g., "1 - 1/8 cup")
 */
def scheduleAdd(String time, String amount) {
    def amountInt = extractAmount(amount)
    if (!validateScheduleParams(time, amountInt)) return
    
    if (!parent) {
        log.error "No parent app configured"
        return
    }
    
    logInfo "Adding schedule: ${time} - ${amountInt}/8 cup"
    parent.addSchedule(device.deviceNetworkId, time, amountInt)
}

/**
 * Update an existing feeding schedule
 * @param scheduleId The schedule ID to update (shown in schedules attribute)
 * @param time New time from dropdown (HH:mm format)
 * @param amount New amount from dropdown (e.g., "1 - 1/8 cup")
 */
def scheduleUpdate(BigDecimal scheduleId, String time, String amount) {
    def amountInt = extractAmount(amount)
    if (!validateScheduleParams(time, amountInt)) return
    
    if (!parent) {
        log.error "No parent app configured"
        return
    }
    
    if (!scheduleId) {
        log.error "Schedule ID is required"
        return
    }
    
    logInfo "Updating schedule ${scheduleId.intValue()}: ${time} - ${amountInt}/8 cup"
    parent.updateSchedule(device.deviceNetworkId, scheduleId.intValue(), time, amountInt)
}

/**
 * Extract amount number from dropdown selection (e.g., "1 - 1/8 cup" -> 1)
 */
private Integer extractAmount(String amountSelection) {
    if (!amountSelection) return 1
    try {
        // Extract the number before " - "
        def numPart = amountSelection.split(" - ")[0]?.trim()
        return numPart as Integer
    } catch (e) {
        logDebug "Could not extract amount from: ${amountSelection}, defaulting to 1"
        return 1
    }
}

/**
 * Delete a feeding schedule
 * @param scheduleId The schedule ID to delete (shown in schedules attribute)
 */
def scheduleDelete(BigDecimal scheduleId) {
    if (!parent) {
        log.error "No parent app configured"
        return
    }
    
    if (!scheduleId) {
        log.error "Schedule ID is required"
        return
    }
    
    logInfo "Deleting schedule ${scheduleId.intValue()}"
    parent.deleteSchedule(device.deviceNetworkId, scheduleId.intValue())
}

/**
 * Validate schedule parameters
 */
private boolean validateScheduleParams(String time, Integer amount) {
    // Validate time format
    if (!time || !time.matches(/^\d{1,2}:\d{2}$/)) {
        log.error "Invalid time format: ${time}. Use HH:mm format (e.g., 07:30)"
        return false
    }
    
    def parts = time.split(":")
    def hour = parts[0] as int
    def minute = parts[1] as int
    
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
        log.error "Invalid time: ${time}. Hour must be 0-23, minute must be 0-59"
        return false
    }
    
    // Validate amount
    def amt = amount ?: 0
    if (amt < 1 || amt > 8) {
        log.error "Invalid amount: ${amount}. Must be between 1 and 8"
        return false
    }
    
    return true
}

/**
 * Refresh device status from API
 */
def refresh() {
    logDebug "Refreshing ${device.displayName}"
    if (parent) {
        parent.refreshDevice(device.deviceNetworkId)
    } else {
        log.warn "No parent app configured - cannot refresh"
    }
}

/**
 * Update device state from feeder info (called by parent app)
 * @param info Map containing feeder information from PetSafe API
 */
def updateFromFeederInfo(Map info) {
    logDebug "Updating from feeder info: ${info}"

    if (info == null) {
        log.error "Received null feeder info"
        return
    }

    // === Device Info ===
    if (info.id != null) {
        sendEvent(name: "feederId", value: info.id)
    }
    if (info.thing_name) {
        sendEvent(name: "thingName", value: info.thing_name)
    }
    if (info.product_name) {
        sendEvent(name: "productName", value: info.product_name)
    }
    if (info.firmware_version) {
        sendEvent(name: "firmwareVersion", value: info.firmware_version)
    }
    if (info.serial != null) {
        sendEvent(name: "serial", value: info.serial ?: "")
    }
    if (info.region != null) {
        sendEvent(name: "region", value: info.region ?: "")
    }
    if (info.created_at) {
        sendEvent(name: "createdAt", value: formatDateTime(info.created_at))
    }

    // === Connection & Network ===
    def connectionStatus = (info.connection_status == 2) ? "online" : "offline"
    sendEvent(name: "connectionStatus", value: connectionStatus, descriptionText: "Feeder is ${connectionStatus}")
    
    if (info.connection_status_timestamp) {
        sendEvent(name: "connectionStatusTimestamp", value: formatDateTime(info.connection_status_timestamp))
    }
    if (info.network_rssi != null) {
        sendEvent(name: "networkRssi", value: info.network_rssi, unit: "dBm")
    }
    if (info.network_snr != null) {
        sendEvent(name: "networkSnr", value: info.network_snr, unit: "dB")
    }

    // === Power ===
    // Battery level calculation (ported from smart-feed.ts getBatteryLevel)
    def batteryLevel = calculateBatteryLevel(info)
    sendEvent(name: "battery", value: batteryLevel, unit: "%", descriptionText: "Battery level is ${batteryLevel}%")
    
    if (info.battery_voltage != null) {
        sendEvent(name: "batteryVoltage", value: info.battery_voltage)
    }
    def isBatteriesInstalled = info.is_batteries_installed ? "true" : "false"
    sendEvent(name: "isBatteriesInstalled", value: isBatteriesInstalled)
    
    def isAdapterInstalled = info.is_adapter_installed ? "true" : "false"
    sendEvent(name: "isAdapterInstalled", value: isAdapterInstalled)

    // === Food ===
    def foodLevelRaw = info.is_food_low ?: 0
    def foodLevel = "full"
    if (foodLevelRaw == 2) {
        foodLevel = "empty"
    } else if (foodLevelRaw == 1) {
        foodLevel = "low"
    }
    sendEvent(name: "foodLevel", value: foodLevel, descriptionText: "Food level is ${foodLevel}")
    sendEvent(name: "foodLevelRaw", value: foodLevelRaw)
    
    if (info.food_sensor_current != null) {
        sendEvent(name: "foodSensorCurrent", value: info.food_sensor_current)
    }
    if (info.food_sensor_reference != null) {
        sendEvent(name: "foodSensorReference", value: info.food_sensor_reference)
    }

    // === Firmware Revision ===
    if (info.revision_desired != null) {
        sendEvent(name: "revisionDesired", value: info.revision_desired)
    }
    if (info.revision_reported != null) {
        sendEvent(name: "revisionReported", value: info.revision_reported)
    }

    // === Settings ===
    if (info.settings) {
        def slowFeed = info.settings.slow_feed ? "enabled" : "disabled"
        sendEvent(name: "slowFeed", value: slowFeed)

        def childLock = info.settings.child_lock ? "enabled" : "disabled"
        sendEvent(name: "childLock", value: childLock)
        
        def paused = info.settings.paused ? "true" : "false"
        sendEvent(name: "paused", value: paused)
        
        if (info.settings.pet_type != null) {
            sendEvent(name: "petType", value: info.settings.pet_type ?: "")
        }
        
        if (info.settings.timezone) {
            sendEvent(name: "timezone", value: info.settings.timezone)
        }

        // Friendly name
        if (info.settings.friendly_name) {
            sendEvent(name: "friendlyName", value: info.settings.friendly_name)
            
            // Update device label if name changed
            if (device.label != info.settings.friendly_name) {
                device.setLabel(info.settings.friendly_name)
            }
        }
    }

    // Process schedules
    if (info.schedules) {
        updateSchedules(info.schedules, info.settings?.timezone)
    }
}

/**
 * Update last feed information (called by parent app after fetching history)
 */
def updateLastFeed(Map lastFeedMessage) {
    if (lastFeedMessage) {
        def amount = lastFeedMessage.payload?.amount ?: 1
        def feedTimeRaw = lastFeedMessage.created_at ?: ""
        def feedTime = formatDateTime(feedTimeRaw)
        
        sendEvent(name: "lastFeedAmount", value: amount, descriptionText: "Last feed was ${amount}/8 cup")
        sendEvent(name: "lastFeedTime", value: feedTime, descriptionText: "Last fed at ${feedTime}")
    }
}

/**
 * Calculate battery level from voltage (ported from smart-feed.ts)
 */
private int calculateBatteryLevel(Map info) {
    def voltage = 0
    try {
        voltage = info.battery_voltage as int
    } catch (e) {
        logDebug "Could not parse battery voltage: ${info.battery_voltage}"
        return 100
    }

    // If batteries not installed or invalid reading, return 100%
    if (!info.is_batteries_installed || voltage < 100 || voltage > MAX_VOLTAGE) {
        return 100
    }

    // Calculate percentage based on voltage range
    def percentage = ((voltage - MIN_VOLTAGE) * 100) / (MAX_VOLTAGE - MIN_VOLTAGE)
    return Math.max(0, Math.min(100, percentage.intValue()))
}

/**
 * Update schedule information
 */
private void updateSchedules(List schedules, String timezone) {
    if (!schedules || schedules.isEmpty()) {
        sendEvent(name: "schedules", value: "No schedules configured")
        sendEvent(name: "schedulesJson", value: "[]")
        sendEvent(name: "nextScheduledFeed", value: "None")
        return
    }

    // Format schedules for display (with IDs for reference)
    def scheduleList = schedules.collect { schedule ->
        def time = schedule.time ? formatTimeOnly(schedule.time) : "Unknown"
        def amount = schedule.amount ?: 1
        // Convert ID to string without thousands separators
        def id = schedule.id != null ? String.valueOf(schedule.id as long) : "?"
        "[${id}] ${time} - ${amount}/8 cup"
    }
    sendEvent(name: "schedules", value: scheduleList.join(", "))
    
    // Store raw JSON for programmatic access
    def schedulesJson = schedules.collect { schedule ->
        [id: schedule.id, time: schedule.time, amount: schedule.amount]
    }
    sendEvent(name: "schedulesJson", value: groovy.json.JsonOutput.toJson(schedulesJson))

    // Calculate next scheduled feed
    def nextFeed = calculateNextScheduledFeed(schedules, timezone)
    sendEvent(name: "nextScheduledFeed", value: nextFeed ?: "None")
}

/**
 * Calculate the next scheduled feed time
 */
private String calculateNextScheduledFeed(List schedules, String timezone) {
    if (!schedules || schedules.isEmpty()) {
        return null
    }

    try {
        def tz = timezone ? TimeZone.getTimeZone(timezone) : location.timeZone
        def now = new Date()
        def calendar = Calendar.getInstance(tz)
        calendar.setTime(now)
        def currentMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        def nextSchedule = null
        def minDiff = Integer.MAX_VALUE
        def isNextToday = false

        schedules.each { schedule ->
            if (schedule.time) {
                def parts = schedule.time?.toString()?.split(":")
                if (parts != null && parts.size() >= 2) {
                    try {
                        def scheduleMinutes = (parts[0] as int) * 60 + (parts[1] as int)
                        def diff = scheduleMinutes - currentMinutes
                        def scheduledToday = diff > 0
                        if (diff <= 0) diff += 1440 // Add 24 hours if schedule is tomorrow
                        
                        if (diff < minDiff) {
                            minDiff = diff
                            nextSchedule = schedule
                            isNextToday = scheduledToday
                        }
                    } catch (NumberFormatException nfe) {
                        logDebug "Could not parse schedule time: ${schedule.time}"
                    }
                }
            }
        }

        if (nextSchedule) {
            def dayLabel = isNextToday ? "Today" : "Tomorrow"
            def formattedTime = formatTimeOnly(nextSchedule.time)
            return "${dayLabel} at ${formattedTime} (${nextSchedule.amount ?: 1}/8 cup)"
        }
    } catch (e) {
        logDebug "Error calculating next schedule: ${e.message}"
    }

    return null
}

// Date/Time formatting helpers

/**
 * Format a full datetime string (e.g., from API created_at field)
 * Handles multiple formats:
 *   - ISO 8601: "2024-01-15T14:30:00Z" or "2024-01-15T14:30:00.000Z"
 *   - Space-separated: "2024-01-15 14:30:00"
 * 
 * IMPORTANT: PetSafe API returns ALL timestamps in UTC, but inconsistently 
 * includes the 'Z' suffix. We always parse as UTC and convert to local time.
 */
private String formatDateTime(String dateTimeStr) {
    if (!dateTimeStr) return ""
    
    def dateTime = null
    
    // Clean the datetime string - remove Z suffix and milliseconds for parsing
    def cleanDateTimeStr = dateTimeStr
        .replaceAll(/Z$/, "")
        .replaceAll(/\.\d+Z$/, "")
        .replaceAll(/\.\d+$/, "")
    
    // Try different date formats
    def formats = [
        "yyyy-MM-dd'T'HH:mm:ss",  // ISO 8601
        "yyyy-MM-dd HH:mm:ss"      // Space-separated
    ]
    
    for (format in formats) {
        try {
            def sdf = new java.text.SimpleDateFormat(format)
            // Always parse as UTC - PetSafe API returns UTC timestamps
            // (sometimes with 'Z' suffix, sometimes without)
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"))
            dateTime = sdf.parse(cleanDateTimeStr)
            break
        } catch (e) {
            // Try next format
        }
    }
    
    if (dateTime) {
        // Get local timezone for display (hub's location timezone)
        def localTz = location.timeZone ?: TimeZone.getDefault()
        
        // Format date part in local timezone
        def datePart = formatDateOnly(dateTime, localTz)
        
        // Format time part in local timezone
        def timePart = formatTimeFromDate(dateTime, localTz)
        
        return "${datePart} ${timePart}"
    } else {
        logDebug "Could not parse datetime: ${dateTimeStr}"
        return dateTimeStr // Return original if parsing fails
    }
}

/**
 * Format a date object according to user preference
 * @param date The Date object to format
 * @param tz The timezone to use for formatting (defaults to location timezone)
 */
private String formatDateOnly(Date date, TimeZone tz = null) {
    def timezone = tz ?: location.timeZone ?: TimeZone.getDefault()
    def format = settings.dateFormat ?: "YYYY-MM-DD"
    
    def sdf
    if (format == "M/D/YY") {
        sdf = new java.text.SimpleDateFormat("M/d/yy")
    } else {
        sdf = new java.text.SimpleDateFormat("yyyy-MM-dd")
    }
    sdf.setTimeZone(timezone)
    return sdf.format(date)
}

/**
 * Format a time string (HH:mm format from schedules) according to user preference
 */
private String formatTimeOnly(String time24) {
    if (!time24) return ""
    
    def timeFormat = settings.timeFormat ?: "24"
    
    if (timeFormat == "12") {
        try {
            def parts = time24.split(":")
            if (parts.size() >= 2) {
                def hour = parts[0] as int
                def minute = parts[1]
                def ampm = hour >= 12 ? "PM" : "AM"
                def hour12 = hour % 12
                if (hour12 == 0) hour12 = 12
                return "${hour12}:${minute} ${ampm}"
            }
        } catch (e) {
            logDebug "Could not parse time: ${time24}"
        }
    }
    
    return time24 // Return as-is for 24-hour format
}

/**
 * Format time from a Date object according to user preference
 * @param date The Date object to format
 * @param tz The timezone to use for formatting (defaults to location timezone)
 */
private String formatTimeFromDate(Date date, TimeZone tz = null) {
    def timezone = tz ?: location.timeZone ?: TimeZone.getDefault()
    def timeFormat = settings.timeFormat ?: "24"
    
    def sdf
    if (timeFormat == "12") {
        sdf = new java.text.SimpleDateFormat("h:mm a")
    } else {
        sdf = new java.text.SimpleDateFormat("HH:mm")
    }
    sdf.setTimeZone(timezone)
    return sdf.format(date)
}

// Logging helpers
private void logDebug(String msg) {
    if (settings.logEnable) {
        log.debug "${device.displayName}: ${msg}"
    }
}

private void logInfo(String msg) {
    if (settings.txtEnable) {
        log.info "${device.displayName}: ${msg}"
    }
}
