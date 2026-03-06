/**
 *  PetSafe Smart Feed Manager App for Hubitat
 *
 *  Copyright 2026 rbyrbt
 *  If you find this helpful, feel free to drop a tip https://ko-fi.com/rbyrbt
 *
 *  THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT ANY WARRANTY. THE AUTHORS ARE NOT LIABLE FOR ANY DAMAGES ARISING FROM ITS USE.
 *  Based on Homebridge integration homebridge-petsafe-smart-feed by dgreif https://github.com/dgreif/homebridge-petsafe-smart-feed
 *
 *  REVISION HISTORY
 *
 *  v1.0   03-06-26   Initial release
 *
 */

definition(
    name: "PetSafe Smart Feed Manager",
    namespace: "rbyrbt",
    author: "rbyrbt",
    description: "Manage PetSafe Smart Feed devices",
    category: "Pets",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/rbyrbt/hubitat-petsafe-feeder/main/apps/petsafe-manager.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "authPage")
    page(name: "generateTokenPage")
    page(name: "discoverPage")
}

// ==================== Cognito Constants ====================
// From parent project dgreif/homebridge-petsafe-smart-feed
@groovy.transform.Field static final String PETSAFE_CLIENT_ID = "18hpp04puqmgf5nc6o474lcp2g"
@groovy.transform.Field static final String COGNITO_REGION = "us-east-1"
@groovy.transform.Field static final String COGNITO_URL = "https://cognito-idp.us-east-1.amazonaws.com/"
@groovy.transform.Field static final String PETSAFE_API_BASE = "https://platform.cloud.petsafe.net/smart-feed/"

def mainPage() {
    // Process any pending feeder selections when returning to main page
    processFeederSelectionsIfNeeded()
    
    dynamicPage(name: "mainPage", title: "Manage Your PetSafe Smart Feeders", install: true, uninstall: true) {
        section("Authentication") {
            if (state.idToken) {
                paragraph "✓ Authenticated with PetSafe"
                href "authPage", title: "Update Token", description: "Click to update your refresh token"
            } else {
                paragraph "⚠ Not authenticated"
                href "authPage", title: "Configure Authentication", description: "Click to enter your refresh token", required: true
            }
        }

        if (state.idToken) {
            section("Device Discovery") {
                href "discoverPage", title: "Discover Feeders", description: "Find and add PetSafe feeders"
            }

            section("Managed Devices") {
                def children = getChildDevices()
                if (children) {
                    children.each { child ->
                        paragraph "• ${child.label ?: child.name} (${child.deviceNetworkId})"
                    }
                } else {
                    paragraph "No feeders added yet. Use 'Discover Feeders' to find devices."
                }
            }

            section("Settings") {
                input name: "pollingInterval", type: "enum", title: "Polling Interval", 
                    options: ["1": "1 minute", "5": "5 minutes", "10": "10 minutes", "15": "15 minutes", "30": "30 minutes", "45": "45 minutes", "60": "1 hour"],
                    defaultValue: "30", required: true
                input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
            }
        }
    }
}

def authPage() {
    // Check if we should test auth (button was clicked)
    def testResult = null
    if (state.testAuthRequested) {
        state.testAuthRequested = false
        testResult = testAuthentication()
    }
    
    dynamicPage(name: "authPage", title: "PetSafe Authentication", install: false, uninstall: false) {
        section("Generate Token") {
            href "generateTokenPage", title: "Generate New Token", description: "Log in with your PetSafe email to generate a token"
        }
        
        section("Manual Token Entry") {
            paragraph "If you have a refresh token from another source, you can enter it here."
            input name: "refreshToken", type: "text", title: "Refresh Token", description: "Paste your refresh token here", required: false
        }

        if (settings.refreshToken) {
            section("Token Status") {
                paragraph "✓ Token configured"
            }
            
            section("Test Connection") {
                input name: "btnTestAuth", type: "button", title: "Test Authentication", submitOnChange: true
                
                if (testResult != null) {
                    if (testResult.success) {
                        paragraph "✓ Authentication successful! Found ${testResult.feederCount} feeder(s)."
                    } else {
                        paragraph "✗ Authentication failed: ${testResult.error}"
                    }
                }
            }
        }
    }
}

def generateTokenPage() {
    // Handle button actions
    def statusMessage = null
    def statusSuccess = false
    
    if (state.sendCodeRequested) {
        state.sendCodeRequested = false
        def result = sendVerificationCode()
        statusMessage = result.message
        statusSuccess = result.success
    }
    
    if (state.verifyCodeRequested) {
        state.verifyCodeRequested = false
        def result = verifyCodeAndGetToken()
        statusMessage = result.message
        statusSuccess = result.success
    }
    
    dynamicPage(name: "generateTokenPage", title: "Generate Auth Token", install: false, uninstall: false) {
        section("Step 1: Enter Email") {
            paragraph "Enter the email address registered with your PetSafe account."
            input name: "petsafeEmail", type: "email", title: "PetSafe Email", description: "your@email.com", required: true, submitOnChange: true
            
            if (settings.petsafeEmail) {
                input name: "btnSendCode", type: "button", title: "Send Verification Code", submitOnChange: true
            }
        }
        
        if (state.authSession) {
            section("Step 2: Enter Code") {
                paragraph "A verification code has been sent to ${state.authEmail}. Enter it below."
                input name: "verificationCode", type: "text", title: "Verification Code", description: "Enter the code from your email", required: true, submitOnChange: true
                
                if (settings.verificationCode) {
                    input name: "btnVerifyCode", type: "button", title: "Verify Code & Generate Token", submitOnChange: true
                }
            }
        }
        
        if (statusMessage) {
            section("Status") {
                if (statusSuccess) {
                    paragraph "✓ ${statusMessage}"
                } else {
                    paragraph "✗ ${statusMessage}"
                }
            }
        }
        
        if (settings.refreshToken && statusSuccess) {
            section("Success") {
                paragraph "Token has been saved! You can now return to the main page and discover your feeders."
            }
        }
    }
}

def appButtonHandler(btn) {
    switch (btn) {
        case "btnTestAuth":
            state.testAuthRequested = true
            break
        case "btnSendCode":
            state.sendCodeRequested = true
            break
        case "btnVerifyCode":
            state.verifyCodeRequested = true
            break
    }
}

/**
 * Send verification code to user's email via Cognito CUSTOM_AUTH flow
 */
private Map sendVerificationCode() {
    def email = settings.petsafeEmail
    
    if (!email) {
        return [success: false, message: "Please enter an email address"]
    }
    
    logDebug "Sending verification code to ${email}"
    
    def body = [
        AuthFlow: "CUSTOM_AUTH",
        ClientId: PETSAFE_CLIENT_ID,
        AuthParameters: [
            USERNAME: email,
            AuthFlow: "CUSTOM_CHALLENGE"
        ]
    ]
    
    def params = [
        uri: COGNITO_URL,
        headers: [
            "Content-Type": "application/x-amz-json-1.1",
            "X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth"
        ],
        requestContentType: "application/json",
        body: body
    ]
    
    try {
        httpPostJson(params) { response ->
            if (response.status == 200) {
                def data = response.data
                
                // Store session data for the challenge response
                state.authSession = data.Session
                state.authChallengeName = data.ChallengeName
                state.authEmail = email
                state.authUsername = data.ChallengeParameters?.USERNAME ?: email
                
                logDebug "Verification code sent successfully"
                log.info "Verification code sent to ${email}"
            } else {
                throw new Exception("Failed with status ${response.status}")
            }
        }
        return [success: true, message: "Verification code sent to ${email}. Check your email!"]
    } catch (e) {
        log.error "Failed to send verification code: ${e.message}"
        state.authSession = null
        return [success: false, message: "Failed to send code: ${e.message}"]
    }
}

/**
 * Verify the code and get the refresh token
 */
private Map verifyCodeAndGetToken() {
    def code = settings.verificationCode
    
    if (!code) {
        return [success: false, message: "Please enter the verification code"]
    }
    
    if (!state.authSession) {
        return [success: false, message: "No pending verification. Please send a new code."]
    }
    
    // Strip non-numeric characters from code
    def cleanCode = code.replaceAll(/\D/, "")
    
    logDebug "Verifying code for ${state.authEmail}"
    
    def body = [
        ClientId: PETSAFE_CLIENT_ID,
        ChallengeName: state.authChallengeName ?: "CUSTOM_CHALLENGE",
        Session: state.authSession,
        ChallengeResponses: [
            ANSWER: cleanCode,
            USERNAME: state.authUsername
        ]
    ]
    
    def params = [
        uri: COGNITO_URL,
        headers: [
            "Content-Type": "application/x-amz-json-1.1",
            "X-Amz-Target": "AWSCognitoIdentityProviderService.RespondToAuthChallenge"
        ],
        requestContentType: "application/json",
        body: body
    ]
    
    try {
        httpPostJson(params) { response ->
            if (response.status == 200) {
                def data = response.data
                
                if (data.AuthenticationResult?.RefreshToken) {
                    // Success! Save the refresh token
                    app.updateSetting("refreshToken", data.AuthenticationResult.RefreshToken)
                    
                    // Clear auth session state
                    state.remove("authSession")
                    state.remove("authChallengeName")
                    state.remove("authEmail")
                    state.remove("authUsername")
                    
                    // Clear the verification code input
                    app.updateSetting("verificationCode", "")
                    
                    log.info "Successfully generated new refresh token"
                } else if (data.Session) {
                    // Need another challenge round
                    state.authSession = data.Session
                    throw new Exception("Incorrect code. Please try again.")
                } else {
                    throw new Exception("No token received. Please try again.")
                }
            } else {
                throw new Exception("Failed with status ${response.status}")
            }
        }
        return [success: true, message: "Token generated and saved successfully!"]
    } catch (e) {
        log.error "Failed to verify code: ${e.message}"
        return [success: false, message: "Verification failed: ${e.message}"]
    }
}

/**
 * Test authentication by refreshing token and fetching feeders
 */
private Map testAuthentication() {
    logDebug "Testing authentication..."
    
    try {
        // Clear existing token to force fresh auth
        state.idToken = null
        state.tokenExpiry = null
        
        // Try to get a new token
        updateIdToken()
        
        if (!state.idToken) {
            return [success: false, error: "Failed to obtain ID token"]
        }
        
        // Try to fetch feeders to verify full API access
        def feeders = fetchFeeders()
        def count = feeders?.size() ?: 0
        
        log.info "Authentication test successful - found ${count} feeder(s)"
        return [success: true, feederCount: count]
        
    } catch (e) {
        log.error "Authentication test failed: ${e.message}"
        return [success: false, error: e.message]
    }
}

def discoverPage() {
    // Process any pending selections first (handles back-navigation)
    processFeederSelectionsIfNeeded()
    
    // Fetch feeders when page loads
    def feeders = []
    def errorMessage = null
    try {
        feeders = fetchFeeders()
    } catch (e) {
        log.error "Error discovering feeders: ${e.message}"
        errorMessage = e.message
    }

    dynamicPage(name: "discoverPage", title: "Discover Feeders", install: false, uninstall: false) {
        if (errorMessage) {
            section("Error") {
                paragraph "Failed to fetch feeders: ${errorMessage}"
                paragraph "Please check your refresh token and try again."
            }
        } else if (feeders) {
            section("Found Feeders") {
                feeders.each { feeder ->
                    def existingDevice = getChildDevice(feeder.thing_name)
                    def status = existingDevice ? "✓ Added" : "○ Not added"
                    def name = feeder.settings?.friendly_name ?: "Feeder ${feeder.id}"
                    input "feeder_${feeder.thing_name}", "bool", title: "${name} (${status})", 
                        description: "ID: ${feeder.id}", defaultValue: existingDevice != null, 
                        submitOnChange: true
                }
            }

            section {
                paragraph "Toggle feeders above to add or remove them. Changes are applied automatically."
            }
        } else {
            section {
                paragraph "No feeders found. Make sure your account has feeders registered in the PetSafe app."
            }
        }
    }
}

/**
 * Process feeder selections if token is available (can be called from pages)
 */
private void processFeederSelectionsIfNeeded() {
    // Only process if we have a valid token
    if (!state.idToken && settings.refreshToken) {
        try {
            updateIdToken()
        } catch (e) {
            logDebug "Could not refresh token for selection processing: ${e.message}"
            return
        }
    }
    
    if (state.idToken) {
        processFeederSelections()
    }
}

def installed() {
    logDebug "PetSafe Manager installed"
    initialize()
}

def updated() {
    logDebug "PetSafe Manager updated"
    unschedule()
    initialize()
}

def uninstalled() {
    logDebug "PetSafe Manager uninstalled"
    unschedule()
    getChildDevices().each { child ->
        deleteChildDevice(child.deviceNetworkId)
    }
}

def initialize() {
    logDebug "Initializing PetSafe Manager"

    // Clear existing token to force refresh
    state.idToken = null
    state.tokenExpiry = null

    // Validate refresh token
    if (settings.refreshToken) {
        try {
            updateIdToken()
            logDebug "Authentication successful"
        } catch (e) {
            log.error "Authentication failed: ${e.message}"
            return
        }
    } else {
        log.warn "No refresh token configured"
        return
    }

    // Process feeder selections from discovery page
    processFeederSelections()

    // Schedule polling
    schedulePolling()

    // Initial refresh of all devices
    runIn(5, "pollFeeders")
}

/**
 * Process feeder selections from the discovery page
 */
private void processFeederSelections() {
    def feeders = []
    try {
        feeders = fetchFeeders()
    } catch (e) {
        log.error "Could not fetch feeders for selection processing: ${e.message}"
        return
    }

    logDebug "Processing ${feeders.size()} feeders for selection changes"

    feeders.each { feeder ->
        def settingKey = "feeder_${feeder.thing_name}"
        def settingValue = settings[settingKey]
        // Handle both boolean true and string "true"
        def isSelected = (settingValue == true || settingValue == "true")
        def existingDevice = getChildDevice(feeder.thing_name)

        logDebug "Feeder ${feeder.thing_name}: setting=${settingValue}, isSelected=${isSelected}, exists=${existingDevice != null}"

        if (isSelected && !existingDevice) {
            // Create new device
            logDebug "Creating device for feeder: ${feeder.thing_name}"
            createFeederDevice(feeder)
        } else if (!isSelected && existingDevice) {
            // Remove device
            logDebug "Removing feeder: ${feeder.thing_name}"
            deleteChildDevice(feeder.thing_name)
        }
    }
}

/**
 * Create a child device for a feeder
 */
private void createFeederDevice(Map feeder) {
    def name = feeder.settings?.friendly_name ?: "PetSafe Feeder ${feeder.id}"
    logDebug "Creating feeder device: ${name} (${feeder.thing_name})"

    try {
        def child = addChildDevice(
            "rbyrbt.petsafe",
            "PetSafe Smart Feeder",
            feeder.thing_name,
            [
                name: "PetSafe Smart Feeder",
                label: name,
                isComponent: false
            ]
        )

        // Initial state update
        child.updateFromFeederInfo(feeder)
        
        log.info "Created feeder device: ${name}"
    } catch (e) {
        log.error "Failed to create feeder device: ${e.message}"
    }
}

/**
 * Schedule polling based on user preference
 */
private void schedulePolling() {
    def interval = settings.pollingInterval ?: "30"
    
    switch (interval) {
        case "1":
            runEvery1Minute("pollFeeders")
            break
        case "5":
            runEvery5Minutes("pollFeeders")
            break
        case "10":
            runEvery10Minutes("pollFeeders")
            break
        case "15":
            runEvery15Minutes("pollFeeders")
            break
        case "30":
            runEvery30Minutes("pollFeeders")
            break
        case "45":
            // No built-in method for 45 minutes, use schedule with cron
            schedule("0 0/45 * * * ?", "pollFeeders")
            break
        case "60":
            runEvery1Hour("pollFeeders")
            break
        default:
            runEvery15Minutes("pollFeeders")
    }

    def intervalText = (interval == "60") ? "1 hour" : "${interval} minute(s)"
    logDebug "Polling scheduled every ${intervalText}"
}

/**
 * Poll all feeders for status updates
 */
def pollFeeders() {
    logDebug "Polling all feeders"

    getChildDevices().each { child ->
        try {
            refreshDevice(child.deviceNetworkId)
        } catch (e) {
            log.error "Error polling ${child.label}: ${e.message}"
        }
    }
}

// ============================================
// API Communication Methods
// ============================================

/**
 * Refresh the ID token using the refresh token (AWS Cognito)
 * Ported from auth.ts refreshTokens()
 */
private void updateIdToken() {
    logDebug "Refreshing ID token"

    if (!settings.refreshToken) {
        throw new Exception("No refresh token configured")
    }

    def body = [
        AuthFlow: "REFRESH_TOKEN_AUTH",
        ClientId: PETSAFE_CLIENT_ID,
        AuthParameters: [
            REFRESH_TOKEN: settings.refreshToken
        ]
    ]

    def params = [
        uri: COGNITO_URL,
        headers: [
            "Content-Type": "application/x-amz-json-1.1",
            "X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth"
        ],
        requestContentType: "application/json",
        body: body
    ]

    try {
        httpPostJson(params) { response ->
            if (response.status == 200) {
                def data = response.data
                state.idToken = data.AuthenticationResult?.IdToken
                def expiresIn = data.AuthenticationResult?.ExpiresIn ?: 3600

                // Schedule token refresh before expiry (10 seconds buffer)
                def refreshTime = (expiresIn - 10) > 0 ? (expiresIn - 10) : expiresIn
                state.tokenExpiry = now() + (refreshTime * 1000)
                runIn(refreshTime, "updateIdToken")

                logDebug "ID token refreshed, expires in ${expiresIn} seconds"
            } else {
                throw new Exception("Token refresh failed with status ${response.status}")
            }
        }
    } catch (e) {
        state.idToken = null
        state.tokenExpiry = null
        throw new Exception("Failed to refresh token: ${e.message}")
    }
}

/**
 * Ensure we have a valid ID token
 */
private void ensureValidToken() {
    if (!state.idToken || (state.tokenExpiry && now() >= state.tokenExpiry)) {
        updateIdToken()
    }
}

/**
 * Make an authenticated request to the PetSafe API
 * Ported from rest-client.ts request()
 */
private Object apiRequest(String method, String path, Map body = null, boolean retry = true) {
    ensureValidToken()

    def url = PETSAFE_API_BASE + path
    logDebug "API ${method} ${url}"

    def params = [
        uri: url,
        headers: [
            "Authorization": state.idToken
        ],
        contentType: "application/json",
        requestContentType: "application/json"
    ]

    if (body) {
        params.body = body
    }

    def result = null

    try {
        switch (method.toUpperCase()) {
            case "GET":
                httpGet(params) { response ->
                    result = response.data
                }
                break
            case "POST":
                httpPostJson(params) { response ->
                    result = response.data
                }
                break
            case "PUT":
                // For PUT requests, explicitly set Content-Type header and JSON-encode body
                params.headers["Content-Type"] = "application/json"
                if (body != null) {
                    params.body = groovy.json.JsonOutput.toJson(body)
                }
                logDebug "PUT body: ${params.body}"
                httpPut(params) { response ->
                    logDebug "PUT response status: ${response.status}"
                    result = response.data
                }
                break
            case "DELETE":
                httpDelete(params) { response ->
                    result = response.data
                }
                break
            default:
                throw new Exception("Unsupported HTTP method: ${method}")
        }
    } catch (Exception e) {
        // Check for 401 status in exception message or response
        def is401 = e.message?.contains("401") || e.message?.contains("Unauthorized")
        if (is401 && retry) {
            logDebug "Got 401, refreshing token and retrying"
            state.idToken = null
            updateIdToken()
            return apiRequest(method, path, body, false)
        }
        throw e
    }

    return result
}

/**
 * Fetch all feeders from the API
 */
private List fetchFeeders() {
    logDebug "Fetching feeders"
    def feeders = apiRequest("GET", "feeders")
    logDebug "Found ${feeders?.size() ?: 0} feeders"
    return feeders ?: []
}

/**
 * Fetch status for a specific feeder
 */
private Map fetchFeederInfo(String thingName) {
    return apiRequest("GET", "feeders/${thingName}")
}

/**
 * Fetch feeding history for a feeder
 */
private List fetchFeederHistory(String thingName, int days = 15) {
    return apiRequest("GET", "feeders/${thingName}/messages?days=${days}") ?: []
}

// ============================================
// Methods called by child devices
// ============================================

/**
 * Refresh a specific device
 */
def refreshDevice(String deviceNetworkId) {
    logDebug "Refreshing device: ${deviceNetworkId}"

    def child = getChildDevice(deviceNetworkId)
    if (!child) {
        log.warn "Device not found: ${deviceNetworkId}"
        return
    }

    try {
        // Fetch feeder info
        def info = fetchFeederInfo(deviceNetworkId)
        if (info) {
            child.updateFromFeederInfo(info)
        }

        // Fetch last feed from history
        def history = fetchFeederHistory(deviceNetworkId)
        def lastFeed = history?.find { it.message_type == "FEED_DONE" }
        if (lastFeed) {
            child.updateLastFeed(lastFeed)
        }

    } catch (e) {
        log.error "Error refreshing ${deviceNetworkId}: ${e.message}"
    }
}

/**
 * Trigger feeding for a device
 * Ported from smart-feed.ts feed()
 */
def feedDevice(String deviceNetworkId, int amount, boolean slowFeed = false) {
    logDebug "Feeding device ${deviceNetworkId}: ${amount}/8 cup, slowFeed=${slowFeed}"

    try {
        apiRequest("POST", "feeders/${deviceNetworkId}/meals", [
            amount: amount,
            slow_feed: slowFeed
        ])

        log.info "Feed command sent to ${deviceNetworkId}: ${amount}/8 cup"

        // Refresh device status after a short delay
        runIn(5, "refreshDeviceDelayed", [data: [deviceNetworkId: deviceNetworkId]])

    } catch (e) {
        log.error "Failed to feed ${deviceNetworkId}: ${e.message}"
        throw e
    }
}

def refreshDeviceDelayed(Map data) {
    refreshDevice(data.deviceNetworkId)
}

// NOTE: setSlowFeed and setChildLock API methods removed
// The PetSafe API returns 403 Forbidden for PUT requests to /settings
// These settings can only be changed via the PetSafe mobile app
// Slow feed for Hubitat-triggered feeds is handled in the driver by passing
// the slow_feed parameter with each feed request

// ============================================
// Schedule Management Methods
// ============================================

/**
 * Add a new feeding schedule
 * @param deviceNetworkId The device network ID (thing_name)
 * @param time Time in HH:mm format
 * @param amount Amount in 1/8 cup (1-8)
 */
def addSchedule(String deviceNetworkId, String time, int amount) {
    logDebug "Adding schedule for ${deviceNetworkId}: ${time} - ${amount}/8 cup"

    try {
        apiRequest("POST", "feeders/${deviceNetworkId}/schedules", [
            time: time,
            amount: amount
        ])

        log.info "Schedule added for ${deviceNetworkId}: ${time} - ${amount}/8 cup"
        
        // Refresh device status to get new schedule
        runIn(2, "refreshDeviceDelayed", [data: [deviceNetworkId: deviceNetworkId]])

    } catch (e) {
        log.error "Failed to add schedule for ${deviceNetworkId}: ${e.message}"
        throw e
    }
}

/**
 * Update an existing feeding schedule
 * @param deviceNetworkId The device network ID (thing_name)
 * @param scheduleId The schedule ID to update
 * @param time New time in HH:mm format
 * @param amount New amount in 1/8 cup (1-8)
 */
def updateSchedule(String deviceNetworkId, int scheduleId, String time, int amount) {
    logDebug "Updating schedule ${scheduleId} for ${deviceNetworkId}: ${time} - ${amount}/8 cup"

    try {
        apiRequest("PUT", "feeders/${deviceNetworkId}/schedules/${scheduleId}", [
            time: time,
            amount: amount
        ])

        log.info "Schedule ${scheduleId} updated for ${deviceNetworkId}: ${time} - ${amount}/8 cup"
        
        // Refresh device status
        runIn(2, "refreshDeviceDelayed", [data: [deviceNetworkId: deviceNetworkId]])

    } catch (e) {
        log.error "Failed to update schedule ${scheduleId} for ${deviceNetworkId}: ${e.message}"
        throw e
    }
}

/**
 * Delete a feeding schedule
 * @param deviceNetworkId The device network ID (thing_name)
 * @param scheduleId The schedule ID to delete
 */
def deleteSchedule(String deviceNetworkId, int scheduleId) {
    logDebug "Deleting schedule ${scheduleId} for ${deviceNetworkId}"

    try {
        apiRequest("DELETE", "feeders/${deviceNetworkId}/schedules/${scheduleId}")

        log.info "Schedule ${scheduleId} deleted for ${deviceNetworkId}"
        
        // Refresh device status
        runIn(2, "refreshDeviceDelayed", [data: [deviceNetworkId: deviceNetworkId]])

    } catch (e) {
        log.error "Failed to delete schedule ${scheduleId} for ${deviceNetworkId}: ${e.message}"
        throw e
    }
}

// Logging helper
private void logDebug(String msg) {
    if (settings.logEnable) {
        log.debug msg
    }
}
