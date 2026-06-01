import groovy.json.*

metadata {
    definition(
        name: "Emporia Vue Driver 2.x",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "https://raw.githubusercontent.com/ke7lvb/Emporia-Vue-Hubitat/refs/heads/main/emporia.groovy",
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

def version() { return "2.4.4" }

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

    // Schedule data refresh
    if (settings.refresh_interval != "0") {
        if (settings.refresh_interval == "60") {
            schedule("7 0 * ? * * *", refresh, [overwrite: true])
        } else {
            schedule("7 */${settings.refresh_interval} * ? * *", refresh, [overwrite: true])
        }
    } else {
        unschedule(refresh)
    }

    // Schedule daily reset if enabled
    if (settings.resetDaily) {
        schedule("0 0 0 * * ?", resetEnergyInternal, [overwrite: true])
        if (logEnable) log.info "Daily energy reset scheduled at midnight"
    } else {
        unschedule(resetEnergyInternal)
    }

    // Schedule token refresh 5 minutes before expiry
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
            def combinedTotals = 0

            // --- First pass: collect TotalUsage and Solar to compute grid flow ---
            def totalUsageWh = 0
            def solarWh = 0
            def solarName = settings.solarChannelName ?: "Rooftop Solar Panels"

            devices.each { value ->
                value.channelUsages.each { next_value ->
                    def name = next_value.name
                    def usage = next_value.usage ?: 0
                    def Wh = convertToWh(usage)
                    if (name == "TotalUsage") { totalUsageWh += Wh }
                    if (name == solarName) { solarWh += Wh }
                }
            }

            // Solar is positive when producing energy.
            // MainsFromGrid = energy drawn from grid  = max(0, TotalUsage - Solar)
            // MainsToGrid   = energy exported to grid = max(0, Solar - TotalUsage)
            def mainsFromGridWh = Math.max(0, totalUsageWh - solarWh)
            def mainsToGridWh   = Math.max(0, solarWh - totalUsageWh)

            if (debugLog) log.debug "TotalUsage: ${totalUsageWh} Wh | Solar: ${solarWh} Wh | FromGrid: ${mainsFromGridWh} Wh | ToGrid: ${mainsToGridWh} Wh"

            // Update MainsFromGrid child
            def cdFromGrid = fetchChild("MainsFromGrid")
            cdFromGrid.sendEvent(name: "power", value: mainsFromGridWh)
            def existingFromGrid = (cdFromGrid.getDataValue("cumulativeEnergy") ?: "0").toBigDecimal()
            def newFromGrid = existingFromGrid + (mainsFromGridWh / 1000)
            cdFromGrid.updateDataValue("cumulativeEnergy", newFromGrid.toString())
            cdFromGrid.sendEvent(name: "energy", value: newFromGrid.setScale(6, BigDecimal.ROUND_HALF_UP))

            // Update MainsToGrid child
            def cdToGrid = fetchChild("MainsToGrid")
            cdToGrid.sendEvent(name: "power", value: mainsToGridWh)
            def existingToGrid = (cdToGrid.getDataValue("cumulativeEnergy") ?: "0").toBigDecimal()
            def newToGrid = existingToGrid + (mainsToGridWh / 1000)
            cdToGrid.updateDataValue("cumulativeEnergy", newToGrid.toString())
            cdToGrid.sendEvent(name: "energy", value: newToGrid.setScale(6, BigDecimal.ROUND_HALF_UP))

            // --- Second pass: update all channel child devices ---
            devices.each { value ->
                value.channelUsages.each { next_value ->
                    if (debugLog) log.debug next_value
                    def name = next_value.name
                    def usage = next_value.usage ?: 0
                    def Wh = convertToWh(usage)
                    def kWh = Wh / 1000

                    if (name == "Main") {
                        combinedTotals += Wh
                    }

                    if (name == "Main" || name == "TotalUsage" || name == "Balance") {
                        def Gid = next_value.deviceGid
                        name = name + "_" + Gid
                    }

                    def cd = fetchChild(name)
                    cd.sendEvent(name: "power", value: Wh)

                    def existingEnergy = (cd.getDataValue("cumulativeEnergy") ?: "0").toBigDecimal()
                    def newEnergy = existingEnergy + kWh
                    cd.updateDataValue("cumulativeEnergy", newEnergy.toString())
                    cd.sendEvent(name: "energy", value: newEnergy.setScale(6, BigDecimal.ROUND_HALF_UP))
                }
            }

            // Accumulate energy on parent device
            if (state.cumulativeEnergy == null) state.cumulativeEnergy = 0
            state.cumulativeEnergy = (state.cumulativeEnergy as BigDecimal) + (combinedTotals / 1000)

            sendEvent(name: "power", value: combinedTotals)
            sendEvent(name: "energy", value: state.cumulativeEnergy.setScale(6, BigDecimal.ROUND_HALF_UP))
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

def convertToWh(usage) {
    if (usage != null) {
        switch (scale) {
            case "1S":
                return Math.round(usage * 60 * 60 * 1000)
            case "1MIN":
                return Math.round(usage * 60 * 1000)
            default:
                return Math.round(usage * 1000)
        }
    }
    return 0
}

def fetchChild(name) {
    String thisId = device.id
    def cd = getChildDevice(name)
    if (!cd) {
        cd = addChildDevice("ke7lvb", "Emporia Vue Child Device", name, [name: name, isComponent: false])
    }
    return cd
}
