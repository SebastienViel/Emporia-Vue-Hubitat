import groovy.json.*

metadata {
    definition(
        name: "Emporia Vue Driver 2.4.x",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "https://raw.githubusercontent.com/SebastienViel/Emporia-Vue-Hubitat/refs/heads/main/emporia.groovy",
    ){
        capability "Refresh"
        capability "PowerSource"
        capability "PowerMeter"
        capability "EnergyMeter"

        command "authToken", [[name: "Update Authtoken*", type: "STRING"]]
        command "getDeviceGid"
        command "generateToken"
        command "refreshToken"
        command "resetEnergy", [[name: "confirm", type: "ENUM", constraints: ["I understand this will reset all cumulative energy totals"], description: "Select to confirm reset"]]
        command "migrateChildDevices", [[name: "forceDeleteDuplicates", type: "ENUM", constraints: ["No - skip if stable ID already exists", "Yes - delete old device if stable ID already exists"], description: "What to do if a migrated device already exists"]]

        attribute "lastUpdate", "string"
        attribute "tokenExpiry", "string"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable Info logging", defaultValue: true
        input name: "debugLog", type: "bool", title: "Enable Debug logging", defaultValue: true
        input name: "jsonState", type: "bool", title: "Show JSON state", defaultValue: true
        input name: "email", type: "string", title: "Emporia Email", required: true
        input name: "password", type: "password", title: "Emporia Password", required: true
        input("scale", "enum", title: "Scale", options: ["1S", "1MIN", "1H", "1D", "1W", "1Mon", "1Y"], required: true, defaultValue: "1H")
        input("energyUnit", "enum", title: "Energy Unit", options: ["KilowattHours"], required: true, defaultValue: "KilowattHours")
        input("refresh_interval", "enum", title: "How often to refresh the Emporia data", options: [
            0: "Do NOT update",
            1: "1 Minute",
            5: "5 Minutes",
            10: "10 Minutes",
            15: "15 Minutes",
            20: "20 Minutes",
            30: "30 Minutes",
            45: "45 Minutes",
            60: "1 Hour"
        ], required: true, defaultValue: "60")
        input name: "resetDaily", type: "bool", title: "Reset cumulative energy totals daily at midnight", defaultValue: false
        input name: "solarChannelName", type: "string", title: "Solar Channel Name (must match Emporia app exactly)", defaultValue: "Rooftop Solar Panels"
    }
}

def version() { return "2.4.7" }

def installed() {
    if (logEnable) log.info "Driver installed"
    state.version = version()
    state.deviceGID = []
    state.deviceNames = []
    state.cumulativeEnergy = 0
}

def uninstalled() {
    unschedule()
    if (logEnable) log.info "Driver uninstalled"
}

def updated() {
    if (logEnable) log.info "Settings updated"

    if (settings.refresh_interval != "0") {
        if (settings.refresh_interval == "60") {
            schedule("7 0 * ? * * *", refresh, [overwrite: true])
        } else {
            schedule("7 */${settings.refresh_interval} * ? * *", refresh, [overwrite: true])
        }
    } else {
        unschedule(refresh)
    }

    if (settings.resetDaily) {
        schedule("0 0 0 * * ?", resetEnergyInternal, [overwrite: true])
        if (logEnable) log.info "Daily energy reset scheduled at midnight"
    } else {
        unschedule(resetEnergyInternal)
    }

    if (state.tokenExpiry) {
        def refreshTime = (state.tokenExpiry - now() - 300000) / 1000
        runIn(refreshTime.toInteger(), refreshToken)
        if (logEnable) log.info "Token refresh scheduled in ${refreshTime.toInteger()} seconds"
    }

    state.version = version()
    if (!jsonState) {
        state.remove("JSON")
    }
}

// ---------------------------------------------------------------------------
// Returns the scale window duration in milliseconds.
// This is used to prevent double-counting energy when the refresh interval
// is shorter than the scale window.
// ---------------------------------------------------------------------------
def scaleWindowMs() {
    switch (scale) {
        case "1S":   return 1000L
        case "1MIN": return 60000L
        case "1H":   return 3600000L
        case "1D":   return 86400000L
        case "1W":   return 604800000L
        case "1Mon": return 2592000000L  // ~30 days
        case "1Y":   return 31536000000L // ~365 days
        default:     return 3600000L
    }
}

// ---------------------------------------------------------------------------
// Accumulate energy only if enough time has elapsed since the last
// accumulation — i.e. at least one full scale window has passed.
// This prevents double-counting when refresh_interval < scale window.
// Returns the kWh to add (the raw API usage value) if accumulation should
// happen, or 0 if we are still within the same scale window.
// ---------------------------------------------------------------------------
def shouldAccumulateEnergy(String deviceKey) {
    def now = now()
    def windowMs = scaleWindowMs()
    def stateKey = "lastEnergyAccum_${deviceKey}"
    def lastAccum = state[stateKey] ?: 0L

    if ((now - lastAccum) >= windowMs) {
        state[stateKey] = now
        return true
    }
    return false
}

// ---------------------------------------------------------------------------
// Migration: re-keys existing child devices from name-based IDs to
// stable "GID_channelNum" IDs, preserving cumulative energy totals.
// Special synthetic devices (MainsFromGrid, MainsToGrid) are left as-is.
// Run this command once after upgrading to v2.4.5+.
// ---------------------------------------------------------------------------
def migrateChildDevices(forceDeleteDuplicates) {
    if (logEnable) log.info "Starting child device migration to stable network IDs"

    def host = "https://api.emporiaenergy.com/"
    def command = "customers/devices"
    try {
        def response = httpGet([uri: "${host}${command}", headers: ['authtoken': state.idToken]]) { resp -> resp.data }

        def nameToStableId = [:]

        response.devices.each { value ->
            if (value.devices && value.devices.size() > 0) {
                value.devices[0].channels.each { ch ->
                    if (ch.name) {
                        nameToStableId[ch.name] = "${ch.deviceGid}_${ch.channelNum}"
                    }
                }
            }
            def gid = value.deviceGid
            nameToStableId["Main_${gid}"]       = "${gid}_Main"
            nameToStableId["TotalUsage_${gid}"] = "${gid}_TotalUsage"
            nameToStableId["Balance_${gid}"]    = "${gid}_Balance"
        }

        int migrated = 0
        int skipped  = 0

        getChildDevices().each { cd ->
            def oldNetId = cd.deviceNetworkId

            if (oldNetId == "MainsFromGrid" || oldNetId == "MainsToGrid") {
                if (logEnable) log.info "Skipping synthetic device: ${oldNetId}"
                skipped++
                return
            }

            if (oldNetId ==~ /\d+_.+/) {
                if (logEnable) log.info "Already migrated, skipping: ${oldNetId}"
                skipped++
                return
            }

            def newNetId = nameToStableId[oldNetId]
            if (newNetId) {
                def savedEnergy = cd.getDataValue("cumulativeEnergy") ?: "0"
                def existing = getChildDevice(newNetId)
                if (existing) {
                    if (forceDeleteDuplicates == "Yes - delete old device if stable ID already exists") {
                        log.warn "Device with new ID ${newNetId} already exists — force-deleting old device: ${oldNetId}"
                        deleteChildDevice(oldNetId)
                        skipped++
                    } else {
                        log.warn "Device with new ID ${newNetId} already exists — skipping migration of ${oldNetId} (use forceDeleteDuplicates=Yes to remove it)"
                        skipped++
                    }
                    return
                }

                def newLabel = cd.label ?: cd.name ?: oldNetId
                def newCd = addChildDevice("ke7lvb", "Emporia Vue Child Device", newNetId, [name: newNetId, label: newLabel, isComponent: false])
                newCd.updateDataValue("cumulativeEnergy", savedEnergy)
                newCd.sendEvent(name: "energy", value: savedEnergy.toBigDecimal().setScale(6, BigDecimal.ROUND_HALF_UP))
                deleteChildDevice(oldNetId)
                if (logEnable) log.info "Migrated: '${oldNetId}' -> '${newNetId}' (label: ${newLabel}, energy preserved: ${savedEnergy})"
                migrated++
            } else {
                log.warn "No stable ID mapping found for child device: ${oldNetId} — skipping"
                skipped++
            }
        }

        log.info "Migration complete. Migrated: ${migrated}, Skipped: ${skipped}"

    } catch (e) {
        log.error "Error during migration: ${e.message}"
        log.error "Error details: ${e}"
    }
}

def resetEnergy(confirm) {
    if (confirm == "I understand this will reset all cumulative energy totals") {
        resetEnergyInternal()
    } else {
        log.warn "Energy reset not confirmed. No action taken."
    }
}

def resetEnergyInternal() {
    if (logEnable) log.info "Resetting all cumulative energy totals"
    state.cumulativeEnergy = 0
    sendEvent(name: "energy", value: 0)
    // Also clear all lastEnergyAccum timestamps so accumulation restarts cleanly
    state.keySet().findAll { it.startsWith("lastEnergyAccum_") }.each { state.remove(it) }
    getChildDevices().each { cd ->
        cd.sendEvent(name: "energy", value: 0)
        cd.updateDataValue("cumulativeEnergy", "0")
    }
    if (logEnable) log.info "All cumulative energy totals reset to 0"
}

def generateToken() {
    def authEndpoint = "https://cognito-idp.us-east-2.amazonaws.com/"
    def headers = [
        "Content-Type": "application/x-amz-json-1.1",
        "X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth"
    ]
    def body = JsonOutput.toJson([
        AuthFlow: "USER_PASSWORD_AUTH",
        ClientId: "4qte47jbstod8apnfic0bunmrq",
        AuthParameters: [
            USERNAME: settings.email,
            PASSWORD: settings.password
        ]
    ])
    def params = [uri: authEndpoint, headers: headers, body: body]
    try {
        httpPost(params) { resp ->
            if (resp.status == 200) {
                def responseText = resp.getData().getText('UTF-8')
                def responseData = new JsonSlurper().parseText(responseText)
                if (responseData.AuthenticationResult) {
                    state.idToken = responseData.AuthenticationResult.IdToken
                    state.accessToken = responseData.AuthenticationResult.AccessToken
                    state.refreshToken = responseData.AuthenticationResult.RefreshToken
                    state.tokenExpiry = now() + (responseData.AuthenticationResult.ExpiresIn * 1000)
                    sendEvent(name: "tokenExpiry", value: new Date(state.tokenExpiry).format("yyyy-MM-dd'T'HH:mm:ss'Z'"))
                    if (logEnable) log.info "Token generated successfully."
                    updated()
                } else {
                    log.error "AuthenticationResult missing in response. Response: ${responseData}"
                }
            } else {
                log.error "Failed to generate token. HTTP status: ${resp.status}"
                if (debugLog) log.debug "Response data: ${resp.getData().getText('UTF-8')}"
            }
        }
    } catch (e) {
        log.error "Error generating token: ${e.message}"
    }
}

def refreshToken() {
    def authEndpoint = "https://cognito-idp.us-east-2.amazonaws.com/"
    def headers = [
        "Content-Type": "application/x-amz-json-1.1",
        "X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth"
    ]
    def body = JsonOutput.toJson([
        AuthFlow: "REFRESH_TOKEN_AUTH",
        ClientId: "4qte47jbstod8apnfic0bunmrq",
        AuthParameters: [
            REFRESH_TOKEN: state.refreshToken
        ]
    ])
    def params = [uri: authEndpoint, headers: headers, body: body]
    try {
        httpPost(params) { resp ->
            if (resp.status == 200) {
                def responseText = resp.getData().getText('UTF-8')
                def responseData = new JsonSlurper().parseText(responseText)
                if (responseData.AuthenticationResult) {
                    state.idToken = responseData.AuthenticationResult.IdToken
                    state.accessToken = responseData.AuthenticationResult.AccessToken
                    state.tokenExpiry = now() + (responseData.AuthenticationResult.ExpiresIn * 1000)
                    sendEvent(name: "tokenExpiry", value: new Date(state.tokenExpiry).format("yyyy-MM-dd'T'HH:mm:ss'Z'"))
                    if (logEnable) log.info "Token refreshed successfully."
                    updated()
                } else {
                    log.error "AuthenticationResult missing in refresh response. Response: ${responseData}"
                }
            } else {
                log.error "Failed to refresh token. HTTP status: ${resp.status}"
                if (debugLog) log.debug "Response data: ${resp.getData().getText('UTF-8')}"
            }
        }
    } catch (e) {
        log.error "Error refreshing token: ${e.message}"
    }
}

def getDeviceGid() {
    def host = "https://api.emporiaenergy.com/"
    def command = "customers/devices"
    try {
        def response = httpGet([uri: "${host}${command}", headers: ['authtoken': state.idToken]]) { resp -> resp.data }
        if (debugLog) log.debug JsonOutput.toJson(response.devices)
        def deviceGID = []
        def deviceNames = []
        response.devices.each { value ->
            if (debugLog) log.debug value.deviceGid
            deviceGID.add(value.deviceGid)
            if (value.devices && value.devices.size() > 0) {
                value.devices[0].channels.each { next_value ->
                    deviceNames.add(next_value.name)
                }
            }
        }
        if (logEnable) log.info "Saving deviceGID: ${deviceGID}"
        if (logEnable) log.info "Saving deviceNames: ${deviceNames}"
        state.deviceGID = deviceGID
        state.deviceNames = deviceNames - null - ''
    } catch (e) {
        log.error "Error fetching device GID: ${e.message}"
        log.error "Error details: ${e}"
    }
}

def refresh() {
    if (state.deviceGID) {
        def Gid_string = state.deviceGID.join("+")
        def outputTZ = TimeZone.getTimeZone('UTC')
        def instant = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", outputTZ)

        def host = "https://api.emporiaenergy.com/"
        def command = "AppAPI?apiMethod=getDeviceListUsages&deviceGids=${Gid_string}&instant=${instant}&scale=${scale}&energyUnit=${energyUnit}"
        if (debugLog) log.debug "${host}${command}"
        try {
            def JSON = httpGet([uri: "${host}${command}", headers: ['authtoken': state.idToken]]) { resp -> resp.data }
            if (jsonState) {
                state.JSON = JsonOutput.toJson(JSON)
            }
            def devices = JSON.deviceListUsages.devices
            def combinedTotalsWh = 0

            // --- First pass: collect TotalUsage and Solar to compute grid flow ---
            def totalUsageKwh = 0
            def solarKwh = 0
            def solarName = settings.solarChannelName ?: "Rooftop Solar Panels"

            devices.each { value ->
                value.channelUsages.each { next_value ->
                    def name  = next_value.name
                    // usage is the raw kWh value from the API for the scale window
                    def usage = (next_value.usage ?: 0) as BigDecimal
                    if (name == "TotalUsage") { totalUsageKwh += usage }
                    if (name == solarName)    { solarKwh += usage }
                }
            }

            // Power values for MainsFromGrid/ToGrid (convert raw kWh to Wh for power display)
            def mainsFromGridWh = Math.max(0, convertToWh(totalUsageKwh / 1000) - convertToWh(solarKwh / 1000))
            def mainsToGridWh   = Math.max(0, convertToWh(solarKwh / 1000) - convertToWh(totalUsageKwh / 1000))

            // Energy values for MainsFromGrid/ToGrid — raw kWh from API, no double conversion
            def mainsFromGridKwh = (totalUsageKwh - solarKwh).max(0)
            def mainsToGridKwh   = (solarKwh - totalUsageKwh).max(0)

            if (debugLog) log.debug "TotalUsage: ${totalUsageKwh} kWh | Solar: ${solarKwh} kWh | FromGrid: ${mainsFromGridKwh} kWh | ToGrid: ${mainsToGridKwh} kWh"

            // Update MainsFromGrid child
            def cdFromGrid = fetchChild("MainsFromGrid", "MainsFromGrid")
            cdFromGrid.sendEvent(name: "power", value: mainsFromGridWh)
            if (shouldAccumulateEnergy("MainsFromGrid")) {
                def existingFromGrid = (cdFromGrid.getDataValue("cumulativeEnergy") ?: "0").toBigDecimal()
                def newFromGrid = existingFromGrid + mainsFromGridKwh
                cdFromGrid.updateDataValue("cumulativeEnergy", newFromGrid.toString())
                cdFromGrid.sendEvent(name: "energy", value: newFromGrid.setScale(6, BigDecimal.ROUND_HALF_UP))
            }

            // Update MainsToGrid child
            def cdToGrid = fetchChild("MainsToGrid", "MainsToGrid")
            cdToGrid.sendEvent(name: "power", value: mainsToGridWh)
            if (shouldAccumulateEnergy("MainsToGrid")) {
                def existingToGrid = (cdToGrid.getDataValue("cumulativeEnergy") ?: "0").toBigDecimal()
                def newToGrid = existingToGrid + mainsToGridKwh
                cdToGrid.updateDataValue("cumulativeEnergy", newToGrid.toString())
                cdToGrid.sendEvent(name: "energy", value: newToGrid.setScale(6, BigDecimal.ROUND_HALF_UP))
            }

            // --- Second pass: update all channel child devices ---
            devices.each { value ->
                value.channelUsages.each { next_value ->
                    if (debugLog) log.debug next_value
                    def channelName = next_value.name
                    def gid         = next_value.deviceGid
                    def channelNum  = next_value.channelNum
                    // usage is raw kWh from the API for the scale window
                    def usageKwh    = (next_value.usage ?: 0) as BigDecimal
                    // Power = convert raw kWh back to instantaneous Wh equivalent
                    def Wh          = convertToWh(usageKwh)

                    if (channelName == "Main") {
                        combinedTotalsWh += Wh
                    }

                    def stableId = "${gid}_${channelNum}"
                    def label = channelName
                    if (channelName == "Main" || channelName == "TotalUsage" || channelName == "Balance") {
                        label = "${channelName}_${gid}"
                    }

                    def cd = fetchChild(stableId, label)
                    cd.sendEvent(name: "power", value: Wh)

                    // Accumulate energy using raw kWh from API — only once per scale window
                    if (shouldAccumulateEnergy(stableId)) {
                        def existingEnergy = (cd.getDataValue("cumulativeEnergy") ?: "0").toBigDecimal()
                        def newEnergy = existingEnergy + usageKwh
                        cd.updateDataValue("cumulativeEnergy", newEnergy.toString())
                        cd.sendEvent(name: "energy", value: newEnergy.setScale(6, BigDecimal.ROUND_HALF_UP))
                    }
                }
            }

            // Accumulate energy on parent device
            if (state.cumulativeEnergy == null) state.cumulativeEnergy = 0
            if (shouldAccumulateEnergy("parent")) {
                // Use TotalUsage kWh directly from API for parent accumulation
                state.cumulativeEnergy = (state.cumulativeEnergy as BigDecimal) + totalUsageKwh
            }

            sendEvent(name: "power", value: combinedTotalsWh)
            sendEvent(name: "energy", value: (state.cumulativeEnergy as BigDecimal).setScale(6, BigDecimal.ROUND_HALF_UP))
            sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"))

        } catch (e) {
            log.error "Error during refresh: ${e.message}"
        }
    } else {
        log.error "Device GID not found. Please run the command to Get Device GID"
    }
}

def authToken(token) {
    state.idToken = token
    now = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
    state.lastTokenUpdate = timeToday(now)
}

// ---------------------------------------------------------------------------
// convertToWh: converts the raw API kWh usage value into an instantaneous
// power representation in Wh, scaled to a per-hour rate.
// The API returns kWh consumed over the scale window. To get an approximate
// instantaneous power (Wh), we extrapolate to an hourly rate:
//   1S   → usage was per-second  → multiply by 3600 to get Wh/h
//   1MIN → usage was per-minute  → multiply by 60   to get Wh/h
//   1H+  → usage already in kWh/h window → multiply by 1000 to get Wh
// Note: usageKwh here is already in kWh (raw from API, NOT pre-multiplied).
// ---------------------------------------------------------------------------
def convertToWh(usageKwh) {
    if (usageKwh != null) {
        switch (scale) {
            case "1S":
                return Math.round(usageKwh * 1000 * 3600)
            case "1MIN":
                return Math.round(usageKwh * 1000 * 60)
            default:
                return Math.round(usageKwh * 1000)
        }
    }
    return 0
}

// fetchChild: uses a stable networkId as the device key and a separate
// human-readable label. If the label changes (circuit renamed in Emporia),
// only the label is updated — the device itself is never recreated.
def fetchChild(String networkId, String label) {
    def cd = getChildDevice(networkId)
    if (!cd) {
        cd = addChildDevice("ke7lvb", "Emporia Vue Child Device", networkId, [name: networkId, label: label, isComponent: false])
        if (logEnable) log.info "Created child device: ${label} (${networkId})"
    } else if (cd.label != label) {
        cd.setLabel(label)
        if (logEnable) log.info "Updated label for ${networkId}: '${cd.label}' -> '${label}'"
    }
    return cd
}
