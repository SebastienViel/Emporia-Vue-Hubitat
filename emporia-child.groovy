metadata {
    definition(
        name: "Emporia Vue Child Device",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "https://raw.githubusercontent.com/SebastienViel/Emporia-Vue-Hubitat/refs/heads/main/emporia-child.groovy",
    ){
        capability "PowerMeter"
        capability "EnergyMeter"

        command "setEnergy", [[name: "kWh*", type: "NUMBER", description: "Manually set the cumulative energy value in kWh. The next refresh will continue accumulating from this value."]]

        attribute "lastUpdate", "string"
    }
    preferences {
    }
}

def installed() {
    log.info "Driver installed"
}

def uninstalled() {
    log.info "Driver uninstalled"
}

def setEnergy(kWh) {
    if (kWh == null) {
        log.error "setEnergy: invalid value — must be a number"
        return
    }
    def rounded = kWh.toBigDecimal().setScale(6, BigDecimal.ROUND_HALF_UP)
    updateDataValue("cumulativeEnergy", rounded.toString())
    sendEvent(name: "energy", value: rounded)
    log.info "Energy manually set to ${rounded} kWh — accumulation will continue from this value"
}
